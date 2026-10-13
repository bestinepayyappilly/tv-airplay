/* Copied from besliky/airplay-tv (core/third_party/alac). */
/*
 * ALAC (Apple Lossless Audio Codec) decoder
 * Copyright (c) 2005 David Hammerton
 * All rights reserved.
 *
 * This is the actual decoder.
 *
 * http://crazney.net/programs/itunes/alac.html
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
 * Modified for use in AirPlay TV. The decoding algorithm is unchanged;
 * the modifications make it safe to run on untrusted network input:
 *  - the bit reader never reads outside the frame and reports overruns,
 *  - sample counts, zero runs, predictor orders and shifts are validated,
 *  - integer arithmetic that could overflow is done in 64 bits,
 *  - only 16-bit output is produced, mono frames are duplicated to stereo,
 *  - global mutable state has been removed.
 */

#include <stdlib.h>
#include <string.h>
#include <stdint.h>

#include "alac.h"

struct alac_file
{
    const unsigned char *input_start;
    const unsigned char *input_buffer;
    const unsigned char *input_end;
    int input_buffer_bitaccumulator;
    int error;

    int samplesize;
    int numchannels;
    int bytespersample;

    int32_t *predicterror_buffer_a;
    int32_t *predicterror_buffer_b;

    int32_t *outputsamples_buffer_a;
    int32_t *outputsamples_buffer_b;

    int32_t *uncompressed_bytes_buffer_a;
    int32_t *uncompressed_bytes_buffer_b;

    uint32_t setinfo_max_samples_per_frame;
    uint8_t setinfo_sample_size;
    uint8_t setinfo_rice_historymult;
    uint8_t setinfo_rice_initialhistory;
    uint8_t setinfo_rice_kmodifier;
};

static int32_t sign_extend(int32_t val, int bits)
{
    if (bits <= 0 || bits >= 32) {
        return val;
    }
    uint32_t shift = (uint32_t) (32 - bits);
    return (int32_t) ((uint32_t) val << shift) >> shift;
}

static void free_buffers(alac_file *alac)
{
    free(alac->predicterror_buffer_a);
    free(alac->predicterror_buffer_b);
    free(alac->outputsamples_buffer_a);
    free(alac->outputsamples_buffer_b);
    free(alac->uncompressed_bytes_buffer_a);
    free(alac->uncompressed_bytes_buffer_b);
    alac->predicterror_buffer_a = alac->predicterror_buffer_b = NULL;
    alac->outputsamples_buffer_a = alac->outputsamples_buffer_b = NULL;
    alac->uncompressed_bytes_buffer_a = alac->uncompressed_bytes_buffer_b = NULL;
}

void alac_free(alac_file *alac)
{
    if (!alac) {
        return;
    }
    free_buffers(alac);
    free(alac);
}

int alac_set_config(alac_file *alac, uint32_t max_samples_per_frame, uint8_t sample_size,
                    uint8_t rice_historymult, uint8_t rice_initialhistory, uint8_t rice_kmodifier)
{
    if (!alac || max_samples_per_frame < 64 || max_samples_per_frame > ALAC_MAX_FRAME_SAMPLES ||
        sample_size != 16 || rice_kmodifier == 0 || rice_kmodifier > 30) {
        return -1;
    }
    free_buffers(alac);
    size_t bytes = (size_t) max_samples_per_frame * sizeof(int32_t);
    alac->predicterror_buffer_a = calloc(1, bytes);
    alac->predicterror_buffer_b = calloc(1, bytes);
    alac->outputsamples_buffer_a = calloc(1, bytes);
    alac->outputsamples_buffer_b = calloc(1, bytes);
    alac->uncompressed_bytes_buffer_a = calloc(1, bytes);
    alac->uncompressed_bytes_buffer_b = calloc(1, bytes);
    if (!alac->predicterror_buffer_a || !alac->predicterror_buffer_b ||
        !alac->outputsamples_buffer_a || !alac->outputsamples_buffer_b ||
        !alac->uncompressed_bytes_buffer_a || !alac->uncompressed_bytes_buffer_b) {
        free_buffers(alac);
        return -1;
    }
    alac->setinfo_max_samples_per_frame = max_samples_per_frame;
    alac->setinfo_sample_size = sample_size;
    alac->setinfo_rice_historymult = rice_historymult;
    alac->setinfo_rice_initialhistory = rice_initialhistory;
    alac->setinfo_rice_kmodifier = rice_kmodifier;
    return 0;
}

/* stream reading */

static unsigned byte_at(const alac_file *alac, int offset)
{
    const unsigned char *p = alac->input_buffer + offset;
    return (p < alac->input_end) ? *p : 0u;
}

static void check_overrun(alac_file *alac)
{
    if (alac->input_buffer > alac->input_end ||
        (alac->input_buffer == alac->input_end && alac->input_buffer_bitaccumulator > 0)) {
        alac->error = 1;
        /* park the reader at the end so later reads return zeros */
        alac->input_buffer = alac->input_end;
        alac->input_buffer_bitaccumulator = 0;
    }
}

/* supports reading 0 to 16 bits, in big endian format */
static uint32_t readbits_16(alac_file *alac, int bits)
{
    uint32_t result;
    int new_accumulator;

    if (bits <= 0) {
        return 0;
    }
    result = (byte_at(alac, 0) << 16) | (byte_at(alac, 1) << 8) | byte_at(alac, 2);

    /* shift left by the number of bits we've already read,
     * so that the top 'n' bits of the 24 bits we read will
     * be the return bits */
    result = result << alac->input_buffer_bitaccumulator;
    result = result & 0x00ffffff;

    /* and then only want the top 'n' bits from that, where n is 'bits' */
    result = result >> (24 - bits);

    new_accumulator = (alac->input_buffer_bitaccumulator + bits);

    /* increase the buffer pointer if we've read over n bytes. */
    alac->input_buffer += (new_accumulator >> 3);

    /* and the remainder goes back into the bit accumulator */
    alac->input_buffer_bitaccumulator = (new_accumulator & 7);

    check_overrun(alac);
    return result;
}

/* supports reading 0 to 32 bits, in big endian format */
static uint32_t readbits(alac_file *alac, int bits)
{
    uint32_t result = 0;

    if (bits > 32) {
        alac->error = 1;
        return 0;
    }
    if (bits > 16) {
        bits -= 16;
        result = readbits_16(alac, 16) << bits;
    }
    result |= readbits_16(alac, bits);
    return result;
}

/* reads a single bit */
static int readbit(alac_file *alac)
{
    int result;
    int new_accumulator;

    result = (int) byte_at(alac, 0);
    result = result << alac->input_buffer_bitaccumulator;
    result = result >> 7 & 1;

    new_accumulator = (alac->input_buffer_bitaccumulator + 1);
    alac->input_buffer += (new_accumulator / 8);
    alac->input_buffer_bitaccumulator = (new_accumulator % 8);

    check_overrun(alac);
    return result;
}

static void unreadbits(alac_file *alac, int bits)
{
    int new_accumulator = (alac->input_buffer_bitaccumulator - bits);

    alac->input_buffer += (new_accumulator >> 3);
    alac->input_buffer_bitaccumulator = (new_accumulator & 7);
    if (alac->input_buffer < alac->input_start) {
        alac->input_buffer = alac->input_start;
        alac->input_buffer_bitaccumulator = 0;
        alac->error = 1;
    }
}

static int count_leading_zeros(int32_t input)
{
    uint32_t v = (uint32_t) input;
    return v ? __builtin_clz(v) : 32;
}

#define RICE_THRESHOLD 8 // maximum number of bits for a rice prefix.

static int32_t entropy_decode_value(alac_file* alac,
                                    int readSampleSize,
                                    int k,
                                    int rice_kmodifier_mask)
{
    int32_t x = 0; // decoded value

    // read x, number of 1s before 0 represent the rice value.
    while (x <= RICE_THRESHOLD && readbit(alac)) {
        x++;
    }

    if (x > RICE_THRESHOLD) {
        // read the number from the bit stream (raw value)
        uint32_t value = readbits(alac, readSampleSize);

        // mask value
        if (readSampleSize < 32) {
            value &= (((uint32_t) 0xffffffff) >> (32 - readSampleSize));
        }
        x = (int32_t) value;
    } else if (k != 1) {
        if (k < 0 || k > 30) {
            alac->error = 1;
            return 0;
        }
        int32_t extraBits = (int32_t) readbits(alac, k);

        // x = x * (2^k - 1)
        x = (int32_t) ((int64_t) x * (((1 << k) - 1) & rice_kmodifier_mask));

        if (extraBits > 1) {
            x += extraBits - 1;
        } else {
            unreadbits(alac, 1);
        }
    }
    return x;
}

static void entropy_rice_decode(alac_file* alac,
                                int32_t* outputBuffer,
                                int outputSize,
                                int readSampleSize,
                                int rice_initialhistory,
                                int rice_kmodifier,
                                int rice_historymult,
                                int rice_kmodifier_mask)
{
    int outputCount;
    int32_t history = rice_initialhistory;
    int signModifier = 0;

    for (outputCount = 0; outputCount < outputSize && !alac->error; outputCount++) {
        int32_t decodedValue;
        int64_t finalValue;
        int32_t k;

        k = 31 - rice_kmodifier - count_leading_zeros((history >> 9) + 3);

        if (k < 0) {
            k += rice_kmodifier;
        } else {
            k = rice_kmodifier;
        }

        // note: don't use rice_kmodifier_mask here (set mask to 0xFFFFFFFF)
        decodedValue = entropy_decode_value(alac, readSampleSize, k, (int) 0xFFFFFFFF);

        int64_t dv = (int64_t) decodedValue + signModifier;
        finalValue = (dv + 1) / 2; // inc by 1 and shift out sign bit
        if (dv & 1) { // the sign is stored in the low bit
            finalValue *= -1;
        }
        outputBuffer[outputCount] = (int32_t) finalValue;

        signModifier = 0;

        // update history
        int64_t h = (int64_t) history + (dv * rice_historymult) - (((int64_t) history * rice_historymult) >> 9);
        history = (h > 0x7fffffff || h < -0x7fffffff) ? 0xFFFF : (int32_t) h;

        if (dv > 0xFFFF) {
            history = 0xFFFF;
        }

        // special case, for compressed blocks of 0
        if ((history < 128) && (outputCount + 1 < outputSize)) {
            int32_t blockSize;

            signModifier = 1;

            k = count_leading_zeros(history) + ((history + 16) / 64) - 24;

            // note: blockSize is always 16bit
            blockSize = entropy_decode_value(alac, 16, k, rice_kmodifier_mask);

            // got blockSize 0s
            if (blockSize > 0) {
                int remaining = outputSize - outputCount - 1;
                if (blockSize > remaining) {
                    alac->error = 1;
                    return;
                }
                memset(&outputBuffer[outputCount + 1], 0, (size_t) blockSize * sizeof(*outputBuffer));
                outputCount += blockSize;
            }

            if (blockSize > 0xFFFF) {
                signModifier = 0;
            }
            history = 0;
        }
    }
}

static void predictor_decompress_fir_adapt(int32_t *error_buffer,
                                           int32_t *buffer_out,
                                           int output_size,
                                           int readsamplesize,
                                           int16_t *predictor_coef_table,
                                           int predictor_coef_num,
                                           int predictor_quantitization)
{
    int i;

    /* first sample always copies */
    *buffer_out = *error_buffer;

    if (!predictor_coef_num) {
        if (output_size <= 1) {
            return;
        }
        memcpy(buffer_out + 1, error_buffer + 1, (size_t) (output_size - 1) * 4);
        return;
    }

    if (predictor_coef_num == 0x1f) { /* 11111 - max value of predictor_coef_num */
        /* second-best case scenario for fir decompression,
         * error describes a small difference from the previous sample only */
        if (output_size <= 1) {
            return;
        }
        for (i = 0; i < output_size - 1; i++) {
            int32_t prev_value = buffer_out[i];
            int32_t error_value = error_buffer[i + 1];
            buffer_out[i + 1] = sign_extend((int32_t) ((uint32_t) prev_value + (uint32_t) error_value), readsamplesize);
        }
        return;
    }

    /* the warm-up and the filter both need predictor_coef_num + 1 samples */
    if (output_size <= predictor_coef_num + 1) {
        memcpy(buffer_out, error_buffer, (size_t) output_size * 4);
        return;
    }

    /* read warm-up samples */
    for (i = 0; i < predictor_coef_num; i++) {
        int32_t val = (int32_t) ((uint32_t) buffer_out[i] + (uint32_t) error_buffer[i + 1]);
        buffer_out[i + 1] = sign_extend(val, readsamplesize);
    }

    /* general case */
    for (i = predictor_coef_num + 1; i < output_size; i++) {
        int j;
        int64_t sum = 0;
        int32_t outval;
        int32_t error_val = error_buffer[i];

        for (j = 0; j < predictor_coef_num; j++) {
            sum += ((int64_t) buffer_out[predictor_coef_num - j] - buffer_out[0]) * predictor_coef_table[j];
        }

        int64_t o = (predictor_quantitization > 0) ? ((int64_t) 1 << (predictor_quantitization - 1)) : 0;
        o += sum;
        o >>= predictor_quantitization;
        o += (int64_t) buffer_out[0] + error_val;
        outval = sign_extend((int32_t) (uint32_t) o, readsamplesize);

        buffer_out[predictor_coef_num + 1] = outval;

        if (error_val > 0) {
            int predictor_num = predictor_coef_num - 1;

            while (predictor_num >= 0 && error_val > 0) {
                int32_t val = buffer_out[0] - buffer_out[predictor_coef_num - predictor_num];
                int sign = (val < 0) ? -1 : ((val > 0) ? 1 : 0);

                predictor_coef_table[predictor_num] -= sign;
                val *= sign; /* absolute value */
                error_val -= ((val >> predictor_quantitization) * (predictor_coef_num - predictor_num));
                predictor_num--;
            }
        } else if (error_val < 0) {
            int predictor_num = predictor_coef_num - 1;

            while (predictor_num >= 0 && error_val < 0) {
                int32_t val = buffer_out[0] - buffer_out[predictor_coef_num - predictor_num];
                int sign = -((val < 0) ? -1 : ((val > 0) ? 1 : 0));

                predictor_coef_table[predictor_num] -= sign;
                val *= sign; /* neg value */
                error_val -= ((val >> predictor_quantitization) * (predictor_coef_num - predictor_num));
                predictor_num--;
            }
        }
        buffer_out++;
    }
}

static void deinterlace_16(int32_t *buffer_a, int32_t *buffer_b,
                           int16_t *buffer_out,
                           int numsamples,
                           uint8_t interlacing_shift,
                           uint8_t interlacing_leftweight)
{
    int i;

    if (interlacing_leftweight) {
        /* weighted interlacing */
        for (i = 0; i < numsamples; i++) {
            int32_t midright = buffer_a[i];
            int32_t difference = buffer_b[i];
            int16_t right = (int16_t) (midright - (int32_t) (((int64_t) difference * interlacing_leftweight) >> interlacing_shift));
            int16_t left = (int16_t) (right + difference);
            buffer_out[i * 2] = left;
            buffer_out[i * 2 + 1] = right;
        }
        return;
    }

    /* otherwise basic interlacing took place */
    for (i = 0; i < numsamples; i++) {
        buffer_out[i * 2] = (int16_t) buffer_a[i];
        buffer_out[i * 2 + 1] = (int16_t) buffer_b[i];
    }
}

typedef struct {
    int prediction_type;
    int prediction_quantitization;
    int ricemodifier;
    int predictor_coef_num;
    int16_t predictor_coef_table[32];
} channel_params_t;

static void read_channel_params(alac_file *alac, channel_params_t *p)
{
    int i;
    p->prediction_type = (int) readbits(alac, 4);
    p->prediction_quantitization = (int) readbits(alac, 4);
    p->ricemodifier = (int) readbits(alac, 3);
    p->predictor_coef_num = (int) readbits(alac, 5);
    for (i = 0; i < p->predictor_coef_num; i++) {
        p->predictor_coef_table[i] = (int16_t) readbits(alac, 16);
    }
}

static int decode_channel(alac_file *alac, channel_params_t *p, int32_t *error_buffer,
                          int32_t *output_buffer, int outputsamples, int readsamplesize)
{
    entropy_rice_decode(alac,
                        error_buffer,
                        outputsamples,
                        readsamplesize,
                        alac->setinfo_rice_initialhistory,
                        alac->setinfo_rice_kmodifier,
                        p->ricemodifier * alac->setinfo_rice_historymult / 4,
                        (1 << alac->setinfo_rice_kmodifier) - 1);
    if (alac->error || p->prediction_type != 0) {
        /* only adaptive FIR prediction is used by AirPlay senders */
        return -1;
    }
    predictor_decompress_fir_adapt(error_buffer,
                                   output_buffer,
                                   outputsamples,
                                   readsamplesize,
                                   p->predictor_coef_table,
                                   p->predictor_coef_num,
                                   p->prediction_quantitization);
    return 0;
}

int alac_decode_frame(alac_file *alac, const unsigned char *inbuffer, int inbuffer_len,
                      void *outbuffer, int out_capacity, int *outputsize)
{
    int channels;
    int hassize;
    int isnotcompressed;
    int readsamplesize;
    int uncompressed_bytes;
    int32_t outputsamples;
    int i;
    int16_t *out = (int16_t *) outbuffer;

    if (!alac || !inbuffer || inbuffer_len <= 0 || !outbuffer || !outputsize ||
        !alac->setinfo_max_samples_per_frame || alac->numchannels != 2) {
        return -1;
    }
    *outputsize = 0;

    /* setup the stream */
    alac->input_start = inbuffer;
    alac->input_buffer = inbuffer;
    alac->input_end = inbuffer + inbuffer_len;
    alac->input_buffer_bitaccumulator = 0;
    alac->error = 0;

    channels = (int) readbits(alac, 3);
    if (channels != 0 && channels != 1) {
        return -1;
    }

    readbits(alac, 4);   /* 2^result = something to do with output waiting */
    readbits(alac, 12);  /* unknown, skip 12 bits */
    hassize = (int) readbits(alac, 1);
    uncompressed_bytes = (int) readbits(alac, 2);
    isnotcompressed = (int) readbits(alac, 1);

    outputsamples = (int32_t) alac->setinfo_max_samples_per_frame;
    if (hassize) {
        uint32_t n = readbits(alac, 32);
        if (n == 0 || n > alac->setinfo_max_samples_per_frame) {
            return -1;
        }
        outputsamples = (int32_t) n;
    }
    if ((size_t) outputsamples * 4 > (size_t) out_capacity) {
        return -1;
    }

    readsamplesize = alac->setinfo_sample_size - (uncompressed_bytes * 8) + (channels == 1 ? 1 : 0);
    if (readsamplesize <= 0 || readsamplesize > 32) {
        return -1;
    }

    if (channels == 0) {
        /* mono */
        if (!isnotcompressed) {
            channel_params_t pa;
            readbits(alac, 8);
            readbits(alac, 8);
            read_channel_params(alac, &pa);
            if (uncompressed_bytes) {
                for (i = 0; i < outputsamples; i++) {
                    alac->uncompressed_bytes_buffer_a[i] = (int32_t) readbits(alac, uncompressed_bytes * 8);
                }
            }
            if (decode_channel(alac, &pa, alac->predicterror_buffer_a, alac->outputsamples_buffer_a,
                               outputsamples, readsamplesize) < 0) {
                return -1;
            }
        } else {
            for (i = 0; i < outputsamples; i++) {
                int32_t audiobits = (int32_t) readbits(alac, alac->setinfo_sample_size);
                alac->outputsamples_buffer_a[i] = sign_extend(audiobits, alac->setinfo_sample_size);
            }
        }
        if (alac->error) {
            return -1;
        }
        for (i = 0; i < outputsamples; i++) {
            int16_t sample = (int16_t) alac->outputsamples_buffer_a[i];
            out[i * 2] = sample;
            out[i * 2 + 1] = sample;
        }
    } else {
        /* stereo */
        uint8_t interlacing_shift = 0;
        uint8_t interlacing_leftweight = 0;

        if (!isnotcompressed) {
            channel_params_t pa;
            channel_params_t pb;

            interlacing_shift = (uint8_t) readbits(alac, 8);
            interlacing_leftweight = (uint8_t) readbits(alac, 8);
            if (interlacing_shift > 31) {
                return -1;
            }
            read_channel_params(alac, &pa);
            read_channel_params(alac, &pb);

            if (uncompressed_bytes) {
                for (i = 0; i < outputsamples; i++) {
                    alac->uncompressed_bytes_buffer_a[i] = (int32_t) readbits(alac, uncompressed_bytes * 8);
                    alac->uncompressed_bytes_buffer_b[i] = (int32_t) readbits(alac, uncompressed_bytes * 8);
                }
            }
            if (decode_channel(alac, &pa, alac->predicterror_buffer_a, alac->outputsamples_buffer_a,
                               outputsamples, readsamplesize) < 0 ||
                decode_channel(alac, &pb, alac->predicterror_buffer_b, alac->outputsamples_buffer_b,
                               outputsamples, readsamplesize) < 0) {
                return -1;
            }
        } else {
            for (i = 0; i < outputsamples; i++) {
                int32_t audiobits_a = (int32_t) readbits(alac, alac->setinfo_sample_size);
                int32_t audiobits_b = (int32_t) readbits(alac, alac->setinfo_sample_size);
                alac->outputsamples_buffer_a[i] = sign_extend(audiobits_a, alac->setinfo_sample_size);
                alac->outputsamples_buffer_b[i] = sign_extend(audiobits_b, alac->setinfo_sample_size);
            }
        }
        if (alac->error) {
            return -1;
        }
        deinterlace_16(alac->outputsamples_buffer_a, alac->outputsamples_buffer_b, out,
                       outputsamples, interlacing_shift, interlacing_leftweight);
    }

    *outputsize = outputsamples * 4;
    return 0;
}

alac_file *alac_create(int samplesize, int numchannels)
{
    if (samplesize != 16 || numchannels != 2) {
        return NULL;
    }
    alac_file *newfile = calloc(1, sizeof(alac_file));
    if (!newfile) {
        return NULL;
    }
    newfile->samplesize = samplesize;
    newfile->numchannels = numchannels;
    newfile->bytespersample = (samplesize / 8) * numchannels;
    return newfile;
}
