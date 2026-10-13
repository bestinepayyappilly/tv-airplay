/*
 * HomeKit-style transient pairing (pair-setup M1-M4, SRP-6a with PIN 3939) and the
 * ChaCha20-Poly1305 channel encryption AirPlay 2 senders use after it. macOS needs both before it
 * will cast a file (AirPlay video v2). Written against pyatv's client (MIT) and the HAP spec;
 * uses the bundled OpenSSL.
 */
#ifndef HAP_H
#define HAP_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define HAP_SECRET_LEN 64

/* ---- transient pair-setup ---- */

typedef struct hap_pair_setup_s hap_pair_setup_t;

hap_pair_setup_t *hap_pair_setup_new(void);
void hap_pair_setup_free(hap_pair_setup_t *ps);

/* Handles one pair-setup request body (TLV8). Returns 0 and a malloc'd TLV8 reply in *out
 * (also on protocol errors, which are answered with an error TLV), -1 on internal failure.
 * *done is set once M4 has been answered: the secret is ready and the connection must switch
 * to encryption right after this reply. */
int hap_pair_setup_handle(hap_pair_setup_t *ps, const uint8_t *in, size_t inlen,
                          uint8_t **out, size_t *outlen, int *done);

/* the 64-byte SRP session key K, valid once done */
const uint8_t *hap_pair_setup_secret(const hap_pair_setup_t *ps);

/* ---- channel encryption ---- */

typedef struct hap_cipher_s hap_cipher_t;

/* keys = HKDF-SHA512(secret, salt, info) (32 bytes each): read_info for data we receive,
 * write_info for data we send */
hap_cipher_t *hap_cipher_new(const uint8_t *secret, size_t secret_len, const char *salt,
                             const char *read_info, const char *write_info);
void hap_cipher_free(hap_cipher_t *c);

/* Decrypts as many whole frames as have arrived; a partial frame is kept for the next call.
 * Returns plaintext length (may be 0) in *out (malloc'd, NULL if 0), or -1 on auth failure. */
int hap_cipher_decrypt(hap_cipher_t *c, const uint8_t *in, size_t inlen, uint8_t **out);

/* Encrypts into 1024-byte frames. Returns ciphertext length in *out (malloc'd), -1 on error. */
int hap_cipher_encrypt(hap_cipher_t *c, const uint8_t *in, size_t inlen, uint8_t **out);

/* HKDF-SHA512 into out (outlen bytes); 0 on success */
int hap_hkdf(const uint8_t *secret, size_t secret_len, const char *salt, const char *info,
             uint8_t *out, size_t outlen);

#ifdef __cplusplus
}
#endif

#endif
