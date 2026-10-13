/*
 * Local HTTP -> HTTPS (TLS 1.2 PSK) proxy. See psk_proxy.h.
 */
#include "psk_proxy.h"

#include <errno.h>
#include <netdb.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>
#include <arpa/inet.h>
#include <openssl/ssl.h>
#include <openssl/err.h>

#ifndef MSG_NOSIGNAL
#define MSG_NOSIGNAL 0
#endif

#define MAX_HEADER 16384
#define IO_TIMEOUT_S 20
#define CIPHERS "PSK-AES128-GCM-SHA256:PSK-AES128-CBC-SHA256"

struct psk_proxy_s {
    int listen_fd;
    int port;
    char host[INET6_ADDRSTRLEN + 1];
    int remote_port;
    uint8_t psk[64];
    unsigned int psk_len;
    SSL_CTX *ctx;
    pthread_t thread;
    atomic_int refs;
    atomic_int stopping;
};

static void proxy_unref(psk_proxy_t *p) {
    if (atomic_fetch_sub(&p->refs, 1) != 1) return;
    SSL_CTX_free(p->ctx);
    OPENSSL_cleanse(p->psk, sizeof(p->psk));
    free(p);
}

static unsigned int psk_client_cb(SSL *ssl, const char *hint, char *identity, unsigned int max_identity_len,
                                  unsigned char *psk, unsigned int max_psk_len) {
    (void) hint;
    psk_proxy_t *p = SSL_CTX_get_app_data(SSL_get_SSL_CTX(ssl));
    if (!p || p->psk_len > max_psk_len || max_identity_len < 1) return 0;
    /* the sender's server is configured without an identity; send an empty one */
    identity[0] = '\0';
    memcpy(psk, p->psk, p->psk_len);
    return p->psk_len;
}

static void set_timeouts(int fd) {
    struct timeval tv = { IO_TIMEOUT_S, 0 };
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
}

static int send_all(int fd, const char *buf, size_t len) {
    while (len > 0) {
        ssize_t n = send(fd, buf, len, MSG_NOSIGNAL);
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        buf += n;
        len -= (size_t) n;
    }
    return 0;
}

static void reply_error(int fd, const char *status) {
    char msg[128];
    int n = snprintf(msg, sizeof(msg), "HTTP/1.1 %s\r\nContent-Length: 0\r\nConnection: close\r\n\r\n", status);
    send_all(fd, msg, (size_t) n);
}

static int connect_remote(const psk_proxy_t *p) {
    char port[8];
    snprintf(port, sizeof(port), "%d", p->remote_port);
    struct addrinfo hints = { 0 }, *res = NULL;
    hints.ai_socktype = SOCK_STREAM;
    hints.ai_flags = AI_NUMERICHOST | AI_NUMERICSERV;
    if (getaddrinfo(p->host, port, &hints, &res) != 0 || !res) return -1;
    int fd = socket(res->ai_family, res->ai_socktype, res->ai_protocol);
    if (fd >= 0) {
        set_timeouts(fd);
        if (connect(fd, res->ai_addr, res->ai_addrlen) != 0) {
            close(fd);
            fd = -1;
        }
    }
    freeaddrinfo(res);
    return fd;
}

/* value of a request header (case-insensitive name), copied into out; 0 if found */
static int header_value(const char *headers, const char *name, char *out, size_t outlen) {
    size_t nlen = strlen(name);
    const char *line = strstr(headers, "\r\n");
    while (line) {
        line += 2;
        if (!strncasecmp(line, name, nlen) && line[nlen] == ':') {
            const char *v = line + nlen + 1;
            while (*v == ' ' || *v == '\t') v++;
            const char *end = strstr(v, "\r\n");
            size_t len = end ? (size_t) (end - v) : strlen(v);
            if (len >= outlen) return -1;
            memcpy(out, v, len);
            out[len] = '\0';
            return 0;
        }
        line = strstr(line, "\r\n");
    }
    return -1;
}

static void *serve_connection(void *arg) {
    void **args = arg;
    psk_proxy_t *p = args[0];
    int fd = (int) (intptr_t) args[1];
    free(args);

    char req[MAX_HEADER + 1];
    size_t len = 0;
    int remote = -1;
    SSL *ssl = NULL;
    set_timeouts(fd);

    /* read the request head */
    while (len < MAX_HEADER) {
        ssize_t n = recv(fd, req + len, MAX_HEADER - len, 0);
        if (n <= 0) goto done;
        len += (size_t) n;
        req[len] = '\0';
        if (strstr(req, "\r\n\r\n")) break;
    }
    if (!strstr(req, "\r\n\r\n")) { reply_error(fd, "431 Request Header Fields Too Large"); goto done; }

    /* request line: only GET/HEAD of a path is forwarded */
    char method[8], path[4096];
    if (sscanf(req, "%7s %4095s", method, path) != 2 || path[0] != '/' ||
        (strcmp(method, "GET") && strcmp(method, "HEAD"))) {
        reply_error(fd, "400 Bad Request");
        goto done;
    }
    char range[128] = { 0 };
    int has_range = header_value(req, "Range", range, sizeof(range)) == 0;

    remote = connect_remote(p);
    if (remote < 0) { reply_error(fd, "502 Bad Gateway"); goto done; }
    ssl = SSL_new(p->ctx);
    if (!ssl || !SSL_set_fd(ssl, remote) || SSL_connect(ssl) != 1) {
        unsigned long e = ERR_get_error();
        char why[160];
        ERR_error_string_n(e, why, sizeof(why));
        fprintf(stderr, "psk_proxy: TLS-PSK handshake failed: %s\n", why);
        reply_error(fd, "502 Bad Gateway");
        goto done;
    }

    char host_hdr[INET6_ADDRSTRLEN + 16];
    if (strchr(p->host, ':')) snprintf(host_hdr, sizeof(host_hdr), "[%s]:%d", p->host, p->remote_port);
    else snprintf(host_hdr, sizeof(host_hdr), "%s:%d", p->host, p->remote_port);
    char up[MAX_HEADER];
    int n = snprintf(up, sizeof(up), "%s %s HTTP/1.1\r\nHost: %s\r\n%s%s%sUser-Agent: AppleCoreMedia/1.0\r\n"
                     "Accept: */*\r\nConnection: close\r\n\r\n",
                     method, path, host_hdr, has_range ? "Range: " : "", has_range ? range : "",
                     has_range ? "\r\n" : "");
    if (n <= 0 || n >= (int) sizeof(up) || SSL_write(ssl, up, n) != n) {
        reply_error(fd, "502 Bad Gateway");
        goto done;
    }

    /* relay the sender's response unchanged until it closes */
    char buf[16384];
    for (;;) {
        int r = SSL_read(ssl, buf, sizeof(buf));
        if (r <= 0) break;
        if (send_all(fd, buf, (size_t) r)) break;
    }

done:
    if (ssl) {
        SSL_shutdown(ssl);
        SSL_free(ssl);
    }
    if (remote >= 0) close(remote);
    close(fd);
    proxy_unref(p);
    return NULL;
}

static void *accept_loop(void *arg) {
    psk_proxy_t *p = arg;
    while (!atomic_load(&p->stopping)) {
        int fd = accept(p->listen_fd, NULL, NULL);
        if (fd < 0) {
            if (errno == EINTR) continue;
            break;
        }
        void **args = malloc(2 * sizeof(void *));
        pthread_t t;
        pthread_attr_t attr;
        if (!args) { close(fd); continue; }
        args[0] = p;
        args[1] = (void *) (intptr_t) fd;
        atomic_fetch_add(&p->refs, 1);
        pthread_attr_init(&attr);
        pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
        if (pthread_create(&t, &attr, serve_connection, args) != 0) {
            close(fd);
            free(args);
            proxy_unref(p);
        }
        pthread_attr_destroy(&attr);
    }
    return NULL;
}

psk_proxy_t *psk_proxy_start(const char *host, int port, const uint8_t *psk, size_t psk_len) {
    if (!host || strlen(host) > INET6_ADDRSTRLEN || port <= 0 || port > 65535 ||
        !psk || psk_len == 0 || psk_len > 64) {
        return NULL;
    }
    psk_proxy_t *p = calloc(1, sizeof(*p));
    if (!p) return NULL;
    strcpy(p->host, host);
    p->remote_port = port;
    memcpy(p->psk, psk, psk_len);
    p->psk_len = (unsigned int) psk_len;
    atomic_init(&p->refs, 1);
    atomic_init(&p->stopping, 0);

    p->ctx = SSL_CTX_new(TLS_client_method());
    if (!p->ctx ||
        !SSL_CTX_set_min_proto_version(p->ctx, TLS1_2_VERSION) ||
        !SSL_CTX_set_max_proto_version(p->ctx, TLS1_2_VERSION) ||
        !SSL_CTX_set_cipher_list(p->ctx, CIPHERS)) {
        proxy_unref(p);
        return NULL;
    }
    SSL_CTX_set_app_data(p->ctx, p);
    SSL_CTX_set_psk_client_callback(p->ctx, psk_client_cb);
    SSL_CTX_set_verify(p->ctx, SSL_VERIFY_NONE, NULL);

    p->listen_fd = socket(AF_INET, SOCK_STREAM, 0);
    struct sockaddr_in addr = { 0 };
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    socklen_t alen = sizeof(addr);
    if (p->listen_fd < 0 || bind(p->listen_fd, (struct sockaddr *) &addr, sizeof(addr)) != 0 ||
        listen(p->listen_fd, 8) != 0 || getsockname(p->listen_fd, (struct sockaddr *) &addr, &alen) != 0) {
        if (p->listen_fd >= 0) close(p->listen_fd);
        proxy_unref(p);
        return NULL;
    }
    p->port = ntohs(addr.sin_port);
    if (pthread_create(&p->thread, NULL, accept_loop, p) != 0) {
        close(p->listen_fd);
        proxy_unref(p);
        return NULL;
    }
    return p;
}

int psk_proxy_port(const psk_proxy_t *p) {
    return p ? p->port : 0;
}

void psk_proxy_stop(psk_proxy_t *p) {
    if (!p) return;
    atomic_store(&p->stopping, 1);
    shutdown(p->listen_fd, SHUT_RDWR);
    close(p->listen_fd);
    pthread_join(p->thread, NULL);
    proxy_unref(p);
}
