/* Copied from besliky/airplay-tv (core/third_party/alac). */
/*
 * ALAC (Apple Lossless Audio Codec) decoder
 * Copyright (c) 2005 David Hammerton
 * All rights reserved.
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or
 * sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 *
 * Modified for use in AirPlay TV: bounded bit reader, validated frame
 * headers and sample counts, 16-bit stereo output only.
 */

#ifndef ALAC_DECOMP_H
#define ALAC_DECOMP_H

#include <stdint.h>

typedef struct alac_file alac_file;

#define ALAC_MAX_FRAME_SAMPLES 4096

/* Creates a decoder for interleaved 16-bit output with the given channel count (1 or 2). */
alac_file *alac_create(int samplesize, int numchannels);

/* Configures the stream parameters normally carried in the ALAC magic cookie.
 * Returns 0 on success, -1 if the parameters are unsupported. */
int alac_set_config(alac_file *alac, uint32_t max_samples_per_frame, uint8_t sample_size,
                    uint8_t rice_historymult, uint8_t rice_initialhistory, uint8_t rice_kmodifier);

/* Decodes one frame. out_capacity is the size of outbuffer in bytes.
 * On success returns 0 and stores the number of bytes written in *outputsize.
 * Returns -1 for malformed or unsupported input; nothing useful is written then. */
int alac_decode_frame(alac_file *alac, const unsigned char *inbuffer, int inbuffer_len,
                      void *outbuffer, int out_capacity, int *outputsize);

void alac_free(alac_file *alac);

#endif /* ALAC_DECOMP_H */
