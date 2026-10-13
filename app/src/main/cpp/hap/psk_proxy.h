/*
 * Local HTTP -> HTTPS (TLS 1.2 PSK) proxy for AirPlay video v2 from macOS. For a local file the
 * Mac serves the media itself over TLS-PSK (cipher suites PSK-AES128-GCM-SHA256 /
 * PSK-AES128-CBC-SHA256, key derived from the pairing secret); ExoPlayer can't speak that, so it
 * plays http://127.0.0.1:<port>/<path> and each request, Range included, is forwarded to the
 * one sender this proxy was started for. Listens on loopback only.
 */
#ifndef PSK_PROXY_H
#define PSK_PROXY_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct psk_proxy_s psk_proxy_t;

/* Starts the proxy for https://host:port; returns NULL on failure. host may be an IPv6
 * literal without brackets. */
psk_proxy_t *psk_proxy_start(const char *host, int port, const uint8_t *psk, size_t psk_len);

/* loopback port to point the player at */
int psk_proxy_port(const psk_proxy_t *proxy);

/* Stops accepting; transfers in flight end when their sockets close. */
void psk_proxy_stop(psk_proxy_t *proxy);

#ifdef __cplusplus
}
#endif

#endif
