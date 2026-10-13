/*
 * HomeKit-style transient pairing and AirPlay 2 channel encryption. See hap.h.
 * SRP conventions follow srptools as used by pyatv (MIT), which interoperates with Apple
 * devices: integers are hashed as minimal big-endian bytes except where RFC 5054 pads (k, u).
 */
#include "hap.h"

#include <stdlib.h>
#include <string.h>
#include <openssl/bn.h>
#include <openssl/evp.h>
#include <openssl/kdf.h>
#include <openssl/rand.h>
#include <openssl/crypto.h>

/* ---------------- TLV8 ---------------- */

enum {
    TLV_METHOD = 0x00, TLV_SALT = 0x02, TLV_PUBLIC_KEY = 0x03, TLV_PROOF = 0x04,
    TLV_STATE = 0x06, TLV_ERROR = 0x07, TLV_FLAGS = 0x13,
};
#define TLV_ERROR_AUTHENTICATION 0x02
#define TLV_ERROR_UNKNOWN 0x01

typedef struct {
    uint8_t *data;
    size_t len, cap;
} buf_t;

static int buf_put(buf_t *b, const uint8_t *p, size_t n) {
    if (b->len + n > b->cap) {
        size_t cap = b->cap ? b->cap : 256;
        while (cap < b->len + n) cap *= 2;
        uint8_t *d = realloc(b->data, cap);
        if (!d) return -1;
        b->data = d;
        b->cap = cap;
    }
    memcpy(b->data + b->len, p, n);
    b->len += n;
    return 0;
}

/* values over 255 bytes are split into consecutive items of the same type */
static int tlv_add(buf_t *b, uint8_t type, const uint8_t *v, size_t n) {
    do {
        uint8_t hdr[2] = { type, (uint8_t) (n > 255 ? 255 : n) };
        if (buf_put(b, hdr, 2) || buf_put(b, v, hdr[1])) return -1;
        v += hdr[1];
        n -= hdr[1];
    } while (n > 0);
    return 0;
}

static int tlv_add_byte(buf_t *b, uint8_t type, uint8_t v) {
    return tlv_add(b, type, &v, 1);
}

/* concatenates every item of the given type; returns malloc'd value or NULL */
static uint8_t *tlv_get(const uint8_t *in, size_t inlen, uint8_t type, size_t *outlen) {
    buf_t b = { 0 };
    int found = 0;
    size_t i = 0;
    while (i + 2 <= inlen) {
        uint8_t t = in[i], n = in[i + 1];
        if (i + 2 + n > inlen) break;
        if (t == type) {
            found = 1;
            if (n && buf_put(&b, in + i + 2, n)) { free(b.data); return NULL; }
        }
        i += 2 + n;
    }
    if (!found) return NULL;
    if (!b.data) b.data = malloc(1);
    *outlen = b.len;
    return b.data;
}

/* ---------------- SRP-6a (RFC 5054 3072-bit group, g = 5, SHA-512) ---------------- */

static const char *N_HEX =
    "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74"
    "020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F1437"
    "4FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED"
    "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF05"
    "98DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB"
    "9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B"
    "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF695581718"
    "3995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D04507A33"
    "A85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7"
    "ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864"
    "D87602733EC86A64521F2B18177B200CBBE117577A615D6C770988C0BAD946E2"
    "08E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF";

#define N_BYTES 384
#define SHA512_LEN 64

struct hap_pair_setup_s {
    int state;               /* last state answered: 0, 2 or 4 */
    BN_CTX *bn;
    BIGNUM *N, *g, *k, *v, *b, *B;
    uint8_t salt[16];
    uint8_t K[SHA512_LEN];
};

static void sha512(const uint8_t *const *parts, const size_t *lens, int count, uint8_t out[SHA512_LEN]) {
    EVP_MD_CTX *ctx = EVP_MD_CTX_new();
    EVP_DigestInit_ex(ctx, EVP_sha512(), NULL);
    for (int i = 0; i < count; i++) EVP_DigestUpdate(ctx, parts[i], lens[i]);
    EVP_DigestFinal_ex(ctx, out, NULL);
    EVP_MD_CTX_free(ctx);
}

/* minimal big-endian bytes (srptools int_to_bytes); malloc'd */
static uint8_t *bn_min(const BIGNUM *x, size_t *len) {
    int n = BN_num_bytes(x);
    uint8_t *p = malloc(n ? n : 1);
    if (!n) { p[0] = 0; *len = 1; return p; }
    BN_bn2bin(x, p);
    *len = (size_t) n;
    return p;
}

static uint8_t *bn_pad(const BIGNUM *x) {
    uint8_t *p = malloc(N_BYTES);
    BN_bn2binpad(x, p, N_BYTES);
    return p;
}

/* a 64-byte hash read as an integer, then back to minimal bytes */
static uint8_t *hash_min(const uint8_t h[SHA512_LEN], size_t *len) {
    size_t skip = 0;
    while (skip < SHA512_LEN - 1 && h[skip] == 0) skip++;
    *len = SHA512_LEN - skip;
    uint8_t *p = malloc(*len);
    memcpy(p, h + skip, *len);
    return p;
}

hap_pair_setup_t *hap_pair_setup_new(void) {
    hap_pair_setup_t *ps = calloc(1, sizeof(*ps));
    if (!ps) return NULL;
    ps->bn = BN_CTX_new();
    ps->N = NULL;
    BN_hex2bn(&ps->N, N_HEX);
    ps->g = BN_new();
    BN_set_word(ps->g, 5);
    return ps;
}

void hap_pair_setup_free(hap_pair_setup_t *ps) {
    if (!ps) return;
    BN_free(ps->N); BN_free(ps->g); BN_free(ps->k); BN_free(ps->v);
    BN_clear_free(ps->b); BN_free(ps->B);
    BN_CTX_free(ps->bn);
    OPENSSL_cleanse(ps->K, sizeof(ps->K));
    free(ps);
}

const uint8_t *hap_pair_setup_secret(const hap_pair_setup_t *ps) {
    return ps->state == 4 ? ps->K : NULL;
}

/* M1 -> M2: salt and server public key B */
static int srp_start(hap_pair_setup_t *ps) {
    /* a leading zero byte would be dropped by the client's int conversion */
    do { RAND_bytes(ps->salt, sizeof(ps->salt)); } while (ps->salt[0] == 0);

    uint8_t h[SHA512_LEN];
    /* x = H(s | H("Pair-Setup:3939")) */
    const char *ip = "Pair-Setup:3939";
    const uint8_t *p1[] = { (const uint8_t *) ip };
    size_t l1[] = { strlen(ip) };
    sha512(p1, l1, 1, h);
    const uint8_t *p2[] = { ps->salt, h };
    size_t l2[] = { sizeof(ps->salt), SHA512_LEN };
    uint8_t xh[SHA512_LEN];
    sha512(p2, l2, 2, xh);
    BIGNUM *x = BN_bin2bn(xh, SHA512_LEN, NULL);

    /* v = g^x mod N */
    ps->v = BN_new();
    BN_mod_exp(ps->v, ps->g, x, ps->N, ps->bn);
    BN_clear_free(x);

    /* k = H(N | PAD(g)) */
    size_t nlen;
    uint8_t *nb = bn_min(ps->N, &nlen);
    uint8_t *gp = bn_pad(ps->g);
    const uint8_t *p3[] = { nb, gp };
    size_t l3[] = { nlen, N_BYTES };
    sha512(p3, l3, 2, h);
    free(nb); free(gp);
    ps->k = BN_bin2bn(h, SHA512_LEN, NULL);

    /* b random, B = (k*v + g^b) mod N */
    ps->b = BN_new();
    BN_rand(ps->b, 256, BN_RAND_TOP_ANY, BN_RAND_BOTTOM_ANY);
    BIGNUM *kv = BN_new(), *gb = BN_new();
    ps->B = BN_new();
    BN_mod_mul(kv, ps->k, ps->v, ps->N, ps->bn);
    BN_mod_exp(gb, ps->g, ps->b, ps->N, ps->bn);
    BN_mod_add(ps->B, kv, gb, ps->N, ps->bn);
    BN_free(kv); BN_free(gb);
    return 0;
}

/* M3 -> M4: check the client's proof, derive K, answer with H(A | M1 | K). 0 if valid */
static int srp_finish(hap_pair_setup_t *ps, const uint8_t *A_bytes, size_t A_len,
                      const uint8_t *M1, size_t M1_len, uint8_t hamk[SHA512_LEN]) {
    int ret = -1;
    BIGNUM *A = BN_bin2bn(A_bytes, (int) A_len, NULL);
    BIGNUM *u = NULL, *S = BN_new(), *t = BN_new(), *chk = BN_new();
    uint8_t *Ap = NULL, *Bp = NULL, *Am = NULL, *Bm = NULL, *Sm = NULL, *xm = NULL, *gm = NULL;
    size_t slen = 0;
    uint8_t h[SHA512_LEN];

    /* A mod N must not be 0 */
    BN_mod(chk, A, ps->N, ps->bn);
    if (BN_is_zero(chk)) goto done;

    /* u = H(PAD(A) | PAD(B)) */
    Ap = bn_pad(A); Bp = bn_pad(ps->B);
    {
        const uint8_t *p[] = { Ap, Bp };
        size_t l[] = { N_BYTES, N_BYTES };
        sha512(p, l, 2, h);
    }
    u = BN_bin2bn(h, SHA512_LEN, NULL);

    /* S = (A * v^u)^b mod N, K = H(S) */
    BN_mod_exp(t, ps->v, u, ps->N, ps->bn);
    BN_mod_mul(t, A, t, ps->N, ps->bn);
    BN_mod_exp(S, t, ps->b, ps->N, ps->bn);
    Sm = bn_min(S, &slen);
    {
        const uint8_t *p[] = { Sm };
        size_t l[] = { slen };
        sha512(p, l, 1, ps->K);
    }

    /* M1 = H(H(N) xor H(g) | H(I) | s | A | B | K) */
    uint8_t hn[SHA512_LEN], hg[SHA512_LEN], hi[SHA512_LEN], hx[SHA512_LEN];
    size_t nlen, glen;
    uint8_t *nb = bn_min(ps->N, &nlen), *gb = bn_min(ps->g, &glen);
    { const uint8_t *p[] = { nb }; size_t l[] = { nlen }; sha512(p, l, 1, hn); }
    { const uint8_t *p[] = { gb }; size_t l[] = { glen }; sha512(p, l, 1, hg); }
    free(nb); free(gb);
    for (int i = 0; i < SHA512_LEN; i++) hx[i] = hn[i] ^ hg[i];
    const char *user = "Pair-Setup";
    { const uint8_t *p[] = { (const uint8_t *) user }; size_t l[] = { strlen(user) }; sha512(p, l, 1, hi); }
    size_t xlen, alen, blen, saltlen = sizeof(ps->salt);
    xm = hash_min(hx, &xlen);
    Am = bn_min(A, &alen);
    Bm = bn_min(ps->B, &blen);
    {
        const uint8_t *p[] = { xm, hi, ps->salt, Am, Bm, ps->K };
        size_t l[] = { xlen, SHA512_LEN, saltlen, alen, blen, SHA512_LEN };
        sha512(p, l, 6, h);
    }
    if (M1_len != SHA512_LEN || CRYPTO_memcmp(h, M1, SHA512_LEN) != 0) goto done;

    /* HAMK = H(A | M1 | K) */
    {
        const uint8_t *p[] = { Am, M1, ps->K };
        size_t l[] = { alen, SHA512_LEN, SHA512_LEN };
        sha512(p, l, 3, hamk);
    }
    ret = 0;

done:
    BN_free(A); BN_free(u); BN_clear_free(S); BN_clear_free(t); BN_free(chk);
    free(Ap); free(Bp); free(Am); free(Bm); free(xm); free(gm);
    if (Sm) { OPENSSL_cleanse(Sm, slen); free(Sm); }
    return ret;
}

static int reply_error(uint8_t state, uint8_t code, uint8_t **out, size_t *outlen) {
    buf_t r = { 0 };
    if (tlv_add_byte(&r, TLV_STATE, state) || tlv_add_byte(&r, TLV_ERROR, code)) { free(r.data); return -1; }
    *out = r.data;
    *outlen = r.len;
    return 0;
}

int hap_pair_setup_handle(hap_pair_setup_t *ps, const uint8_t *in, size_t inlen,
                          uint8_t **out, size_t *outlen, int *done) {
    *done = 0;
    size_t n;
    uint8_t *state = tlv_get(in, inlen, TLV_STATE, &n);
    uint8_t st = (state && n == 1) ? state[0] : 0;
    free(state);

    if (st == 1) {
        /* a new M1 restarts the exchange */
        BN_free(ps->k); BN_free(ps->v); BN_clear_free(ps->b); BN_free(ps->B);
        ps->k = ps->v = ps->b = ps->B = NULL;
        ps->state = 0;
        if (srp_start(ps)) return -1;
        uint8_t *Bb = bn_pad(ps->B);
        buf_t r = { 0 };
        int err = tlv_add_byte(&r, TLV_STATE, 2) || tlv_add(&r, TLV_SALT, ps->salt, sizeof(ps->salt)) ||
                  tlv_add(&r, TLV_PUBLIC_KEY, Bb, N_BYTES);
        free(Bb);
        if (err) { free(r.data); return -1; }
        ps->state = 2;
        *out = r.data;
        *outlen = r.len;
        return 0;
    }
    if (st == 3 && ps->state == 2) {
        size_t alen = 0, mlen = 0;
        uint8_t *A = tlv_get(in, inlen, TLV_PUBLIC_KEY, &alen);
        uint8_t *M1 = tlv_get(in, inlen, TLV_PROOF, &mlen);
        uint8_t hamk[SHA512_LEN];
        int ok = A && M1 && srp_finish(ps, A, alen, M1, mlen, hamk) == 0;
        free(A); free(M1);
        if (!ok) return reply_error(4, TLV_ERROR_AUTHENTICATION, out, outlen);
        buf_t r = { 0 };
        if (tlv_add_byte(&r, TLV_STATE, 4) || tlv_add(&r, TLV_PROOF, hamk, SHA512_LEN)) { free(r.data); return -1; }
        ps->state = 4;
        *done = 1;
        *out = r.data;
        *outlen = r.len;
        return 0;
    }
    return reply_error((uint8_t) (st + 1), TLV_ERROR_UNKNOWN, out, outlen);
}

/* ---------------- HKDF and ChaCha20-Poly1305 framing ---------------- */

int hap_hkdf(const uint8_t *secret, size_t secret_len, const char *salt, const char *info,
             uint8_t *out, size_t outlen) {
    EVP_PKEY_CTX *ctx = EVP_PKEY_CTX_new_id(EVP_PKEY_HKDF, NULL);
    if (!ctx) return -1;
    int ok = EVP_PKEY_derive_init(ctx) > 0 &&
             EVP_PKEY_CTX_set_hkdf_md(ctx, EVP_sha512()) > 0 &&
             EVP_PKEY_CTX_set1_hkdf_salt(ctx, (const unsigned char *) salt, (int) strlen(salt)) > 0 &&
             EVP_PKEY_CTX_set1_hkdf_key(ctx, secret, (int) secret_len) > 0 &&
             EVP_PKEY_CTX_add1_hkdf_info(ctx, (const unsigned char *) info, (int) strlen(info)) > 0 &&
             EVP_PKEY_derive(ctx, out, &outlen) > 0;
    EVP_PKEY_CTX_free(ctx);
    return ok ? 0 : -1;
}

#define FRAME_MAX 1024
#define TAG_LEN 16

struct hap_cipher_s {
    uint8_t read_key[32], write_key[32];
    uint64_t read_count, write_count;
    buf_t pending;           /* ciphertext of a partial frame */
};

hap_cipher_t *hap_cipher_new(const uint8_t *secret, size_t secret_len, const char *salt,
                             const char *read_info, const char *write_info) {
    hap_cipher_t *c = calloc(1, sizeof(*c));
    if (!c) return NULL;
    if (hap_hkdf(secret, secret_len, salt, read_info, c->read_key, 32) ||
        hap_hkdf(secret, secret_len, salt, write_info, c->write_key, 32)) {
        free(c);
        return NULL;
    }
    return c;
}

void hap_cipher_free(hap_cipher_t *c) {
    if (!c) return;
    OPENSSL_cleanse(c, sizeof(*c) - sizeof(buf_t));
    free(c->pending.data);
    free(c);
}

static void nonce_of(uint64_t count, uint8_t nonce[12]) {
    memset(nonce, 0, 4);
    for (int i = 0; i < 8; i++) nonce[4 + i] = (uint8_t) (count >> (8 * i));
}

/* one AEAD operation; aad is the 2-byte little-endian frame length */
static int aead(int enc, const uint8_t key[32], uint64_t count, const uint8_t aad[2],
                const uint8_t *in, int len, uint8_t *out, uint8_t tag[TAG_LEN]) {
    uint8_t nonce[12];
    nonce_of(count, nonce);
    EVP_CIPHER_CTX *ctx = EVP_CIPHER_CTX_new();
    int outl = 0, ok =
        EVP_CipherInit_ex(ctx, EVP_chacha20_poly1305(), NULL, NULL, NULL, enc) > 0 &&
        EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_AEAD_SET_IVLEN, 12, NULL) > 0 &&
        EVP_CipherInit_ex(ctx, NULL, NULL, key, nonce, enc) > 0 &&
        EVP_CipherUpdate(ctx, NULL, &outl, aad, 2) > 0 &&
        EVP_CipherUpdate(ctx, out, &outl, in, len) > 0;
    if (ok && !enc) ok = EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_AEAD_SET_TAG, TAG_LEN, tag) > 0;
    if (ok) ok = EVP_CipherFinal_ex(ctx, out + outl, &outl) > 0;
    if (ok && enc) ok = EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_AEAD_GET_TAG, TAG_LEN, tag) > 0;
    EVP_CIPHER_CTX_free(ctx);
    return ok ? 0 : -1;
}

int hap_aead_open(const uint8_t key[32], const uint8_t nonce8[8], const uint8_t *aad, size_t aadlen,
                  const uint8_t *in, size_t inlen, uint8_t *out) {
    if (inlen < TAG_LEN || inlen - TAG_LEN > (size_t) INT32_MAX) return -1;
    int len = (int) (inlen - TAG_LEN);
    uint8_t nonce[12] = { 0 }, tag[TAG_LEN];
    memcpy(nonce + 4, nonce8, 8);
    memcpy(tag, in + len, TAG_LEN);
    EVP_CIPHER_CTX *ctx = EVP_CIPHER_CTX_new();
    int outl = 0, ok = ctx &&
        EVP_DecryptInit_ex(ctx, EVP_chacha20_poly1305(), NULL, NULL, NULL) > 0 &&
        EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_AEAD_SET_IVLEN, 12, NULL) > 0 &&
        EVP_DecryptInit_ex(ctx, NULL, NULL, key, nonce) > 0 &&
        (aadlen == 0 || EVP_DecryptUpdate(ctx, NULL, &outl, aad, (int) aadlen) > 0) &&
        EVP_DecryptUpdate(ctx, out, &outl, in, len) > 0 &&
        EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_AEAD_SET_TAG, TAG_LEN, tag) > 0 &&
        EVP_DecryptFinal_ex(ctx, out + outl, &outl) > 0;
    EVP_CIPHER_CTX_free(ctx);
    return ok ? len : -1;
}

int hap_cipher_decrypt(hap_cipher_t *c, const uint8_t *in, size_t inlen, uint8_t **out) {
    *out = NULL;
    if (inlen && buf_put(&c->pending, in, inlen)) return -1;
    buf_t plain = { 0 };
    size_t pos = 0;
    while (c->pending.len - pos >= 2) {
        const uint8_t *f = c->pending.data + pos;
        size_t flen = (size_t) f[0] | ((size_t) f[1] << 8);
        if (c->pending.len - pos < 2 + flen + TAG_LEN) break;
        uint8_t tmp[FRAME_MAX + TAG_LEN];
        if (flen > sizeof(tmp)) { free(plain.data); return -1; }
        uint8_t tag[TAG_LEN];
        memcpy(tag, f + 2 + flen, TAG_LEN);
        if (aead(0, c->read_key, c->read_count, f, f + 2, (int) flen, tmp, tag)) {
            free(plain.data);
            return -1;
        }
        c->read_count++;
        if (buf_put(&plain, tmp, flen)) { free(plain.data); return -1; }
        pos += 2 + flen + TAG_LEN;
    }
    memmove(c->pending.data, c->pending.data + pos, c->pending.len - pos);
    c->pending.len -= pos;
    *out = plain.data;
    return (int) plain.len;
}

int hap_cipher_encrypt(hap_cipher_t *c, const uint8_t *in, size_t inlen, uint8_t **out) {
    buf_t r = { 0 };
    while (inlen > 0) {
        size_t n = inlen > FRAME_MAX ? FRAME_MAX : inlen;
        uint8_t hdr[2] = { (uint8_t) (n & 0xff), (uint8_t) (n >> 8) };
        uint8_t ct[FRAME_MAX], tag[TAG_LEN];
        if (aead(1, c->write_key, c->write_count, hdr, in, (int) n, ct, tag)) { free(r.data); return -1; }
        c->write_count++;
        if (buf_put(&r, hdr, 2) || buf_put(&r, ct, n) || buf_put(&r, tag, TAG_LEN)) { free(r.data); return -1; }
        in += n;
        inlen -= n;
    }
    *out = r.data;
    return (int) r.len;
}
