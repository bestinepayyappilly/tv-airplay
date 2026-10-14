/*
 * Implements raop_callbacks_t by forwarding to Java/Kotlin via JNI.
 * All callbacks fire from RAOP's internal pthreads, so we AttachCurrentThread.
 */

#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <errno.h>
#include <android/log.h>
#include "android_raop_callbacks.h"
#include "audio_engine.h"
#include <plist/plist.h>

#define TAG "AirPlayNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static JNIEnv *_get_env(android_callback_ctx_t *ctx) {
    JNIEnv *env = NULL;
    int status = (*ctx->jvm)->GetEnv(ctx->jvm, (void **)&env, JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        (*ctx->jvm)->AttachCurrentThread(ctx->jvm, &env, NULL);
    }
    /* Clear any pending exception from a previous callback on this thread,
       otherwise JNI calls like NewByteArray will fatally abort. */
    if (env && (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
    return env;
}

void android_callbacks_init(android_callback_ctx_t *ctx, JNIEnv *env, jobject callback_obj) {
    (*env)->GetJavaVM(env, &ctx->jvm);
    ctx->callback_obj = (*env)->NewGlobalRef(env, callback_obj);
    ctx->h265_enabled = 1;
    ctx->require_pin = 0;
    ctx->registered_count = 0;
    ctx->audio_engine = NULL;
    memset(ctx->registered_keys, 0, sizeof(ctx->registered_keys));

    pthread_mutex_init(&ctx->playback_info_lock, NULL);
    pthread_cond_init(&ctx->play_ready_cond, NULL);
    ctx->play_ready = 0;
    ctx->playback_position = 0.0;
    /* -1.0 is the video finished sentinel, reserved for _video_stop */
    ctx->playback_duration = 0.0;
    ctx->playback_rate = 0.0f;
    ctx->playback_ready = 0;

    jclass cls = (*env)->GetObjectClass(env, callback_obj);
    ctx->on_video_data = (*env)->GetMethodID(env, cls, "onVideoData", "([BJZ)V");
    ctx->on_audio_format = (*env)->GetMethodID(env, cls, "onAudioFormat", "(IIZ)V");
    ctx->on_video_size = (*env)->GetMethodID(env, cls, "onVideoSize", "(FFFF)V");
    ctx->on_volume_change = (*env)->GetMethodID(env, cls, "onVolumeChange", "(F)V");
    ctx->on_client_volume = (*env)->GetMethodID(env, cls, "onClientVolume", "()F");
    ctx->on_audio_teardown = (*env)->GetMethodID(env, cls, "onAudioTeardown", "()V");
    ctx->on_conn_init = (*env)->GetMethodID(env, cls, "onConnectionInit", "()V");
    ctx->on_conn_destroy = (*env)->GetMethodID(env, cls, "onConnectionDestroy", "()V");
    ctx->on_conn_reset = (*env)->GetMethodID(env, cls, "onConnectionReset", "(I)V");
    ctx->on_display_pin = (*env)->GetMethodID(env, cls, "onDisplayPin", "(Ljava/lang/String;)V");
    ctx->on_metadata = (*env)->GetMethodID(env, cls, "onMetadata", "([B)V");
    ctx->on_coverart = (*env)->GetMethodID(env, cls, "onCoverArt", "([B)V");
    ctx->on_progress = (*env)->GetMethodID(env, cls, "onProgress", "(JJJ)V");
    ctx->on_dacp_id = (*env)->GetMethodID(env, cls, "onDacpId", "(Ljava/lang/String;Ljava/lang/String;)V");
    ctx->on_mirror_running = (*env)->GetMethodID(env, cls, "onMirrorRunning", "(Z)V");
    ctx->on_video_play = (*env)->GetMethodID(env, cls, "onVideoPlay", "(Ljava/lang/String;F)V");
    ctx->on_video_scrub = (*env)->GetMethodID(env, cls, "onVideoScrub", "(F)V");
    ctx->on_video_rate = (*env)->GetMethodID(env, cls, "onVideoRate", "(F)V");
    ctx->on_video_stop = (*env)->GetMethodID(env, cls, "onVideoStop", "()V");
    ctx->on_sender_replaced = (*env)->GetMethodID(env, cls, "onSenderReplaced", "()V");
    ctx->on_video_session_poll = (*env)->GetMethodID(env, cls, "onVideoSessionPoll", "()V");
    ctx->on_client_info = (*env)->GetMethodID(env, cls, "onClientInfo", "([B[B)V");
    ctx->on_av2_event_port = (*env)->GetMethodID(env, cls, "onAv2EventPort", "()I");
    ctx->on_av2_stream = (*env)->GetMethodID(env, cls, "onAv2Stream", "(J)V");
    ctx->on_av2_command = (*env)->GetMethodID(env, cls, "onAv2Command", "(J[B)[B");
    ctx->on_av2_info = (*env)->GetMethodID(env, cls, "onAv2Info", "([B)V");
    ctx->on_av2_record = (*env)->GetMethodID(env, cls, "onAv2Record", "()V");
    ctx->on_hap_paired = (*env)->GetMethodID(env, cls, "onHapPaired", "([B)V");
    (*env)->DeleteLocalRef(env, cls);
}

void android_callbacks_destroy(android_callback_ctx_t *ctx, JNIEnv *env) {
    if (ctx->callback_obj) {
        (*env)->DeleteGlobalRef(env, ctx->callback_obj);
        ctx->callback_obj = NULL;
    }
    for (int i = 0; i < ctx->registered_count; i++) {
        free(ctx->registered_keys[i]);
        ctx->registered_keys[i] = NULL;
    }
    ctx->registered_count = 0;
    pthread_cond_destroy(&ctx->play_ready_cond);
    pthread_mutex_destroy(&ctx->playback_info_lock);
}

void android_callbacks_update_playback_info(android_callback_ctx_t *ctx, double position,
                                             double duration, float rate, int ready) {
    pthread_mutex_lock(&ctx->playback_info_lock);
    ctx->playback_position = position;
    ctx->playback_duration = duration;
    ctx->playback_rate = rate;
    ctx->playback_ready = ready;
    if (ready && !ctx->play_ready) {
        ctx->play_ready = 1;
        pthread_cond_signal(&ctx->play_ready_cond);
    }
    pthread_mutex_unlock(&ctx->playback_info_lock);
}

/* --- RAOP callback implementations --- */

static void _audio_process(void *cls, raop_ntp_t *ntp, audio_decode_struct *data) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    if (!ctx->audio_engine || !data->data || data->data_len <= 0) return;
    audio_engine_decode(ctx->audio_engine, data->data, data->data_len,
                        (int)data->ct, (int64_t)data->ntp_time_local);
}

static void _video_process(void *cls, raop_ntp_t *ntp, video_decode_struct *data) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env || !data->data || data->data_len <= 0) return;
    /* raop_rtp_mirror marks undecryptable packets by setting byte 0 (start code) to 1 */
    if (data->data[0]) {
        LOGE("video packet decryption failed, dropping %d bytes", data->data_len);
        return;
    }

    jbyteArray arr = (*env)->NewByteArray(env, data->data_len);
    (*env)->SetByteArrayRegion(env, arr, 0, data->data_len, (jbyte *)data->data);
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_video_data,
                           arr, (jlong)data->ntp_time_local, (jboolean)data->is_h265);
    (*env)->DeleteLocalRef(env, arr);
}

static void _conn_init(void *cls) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    /* fires for every connection including the player's own hls fetches: clear only a stale sentinel */
    pthread_mutex_lock(&ctx->playback_info_lock);
    if (ctx->playback_duration == -1.0) {
        ctx->playback_position = 0.0;
        ctx->playback_duration = 0.0;
        ctx->playback_rate = 0.0f;
        ctx->playback_ready = 0;
    }
    pthread_mutex_unlock(&ctx->playback_info_lock);
    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_conn_init);
}

static void _conn_destroy(void *cls) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_conn_destroy);
}

static void _conn_reset(void *cls, int reason) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_conn_reset, (jint)reason);
}

static void _audio_set_volume(void *cls, float volume) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_volume_change, (jfloat)volume);
}

static void _audio_get_format(void *cls, unsigned char *ct, unsigned short *spf,
                               bool *usingScreen, bool *isMedia, uint64_t *audioFormat) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_audio_format,
                           (jint)*ct, (jint)*spf, (jboolean)*usingScreen);
}

static void _video_report_size(void *cls, float *w_src, float *h_src, float *w, float *h) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_video_size,
                           (jfloat)*w_src, (jfloat)*h_src, (jfloat)*w, (jfloat)*h);
}

static void _display_pin(void *cls, char *pin) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    
    /* certain senders trigger pair-pin-start even when we advertise no auth */
    if (!ctx->require_pin) return;

    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    jstring jpin = (*env)->NewStringUTF(env, pin);
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_display_pin, jpin);
    (*env)->DeleteLocalRef(env, jpin);
}

static jbyteArray _bytes(JNIEnv *env, const char *s) {
    jsize len = s ? (jsize)strlen(s) : 0;
    jbyteArray a = (*env)->NewByteArray(env, len);
    if (a && len > 0) (*env)->SetByteArrayRegion(env, a, 0, len, (const jbyte *)s);
    return a;
}

/* sender's name ("Someone's MacBook Pro") and model, from its pair-setup request */
static void _report_client_request(void *cls, char *deviceid, char *model, char *name, bool *admit) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    (void)deviceid; (void)admit;
    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    /* raw bytes: names can hold emoji, which NewStringUTF (modified UTF-8) rejects */
    jbyteArray jname = _bytes(env, name);
    jbyteArray jmodel = _bytes(env, model);
    if (jname && jmodel) (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_client_info, jname, jmodel);
    if (jname) (*env)->DeleteLocalRef(env, jname);
    if (jmodel) (*env)->DeleteLocalRef(env, jmodel);
}

/* --- AirPlay video v2 (QuickTime): plists cross JNI as XML, Kotlin parses and builds them --- */

static unsigned short _av2_event_port(void *cls) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env) return 0;
    jint port = (*env)->CallIntMethod(env, ctx->callback_obj, ctx->on_av2_event_port);
    return (unsigned short) port;
}

static void _av2_stream_setup(void *cls, uint64_t stream_id) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_av2_stream, (jlong) stream_id);
}

static void _av2_command(void *cls, const char *stream_id, const char *data, int datalen, char **reply, int *reply_len) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env || !data || datalen <= 0) return;
    plist_t root = NULL;
    plist_from_bin(data, (uint32_t) datalen, &root);
    if (!root) return;
    char *xml = NULL;
    uint32_t xml_len = 0;
    plist_to_xml(root, &xml, &xml_len);
    plist_free(root);
    if (!xml) return;
    jbyteArray jxml = _bytes(env, xml);
    plist_mem_free(xml);
    if (!jxml) return;
    /* -1: a session-level command with no X-Apple-StreamID */
    jlong sid = stream_id ? (jlong) strtoll(stream_id, NULL, 10) : -1;
    jbyteArray jreply = (jbyteArray) (*env)->CallObjectMethod(env, ctx->callback_obj, ctx->on_av2_command, sid, jxml);
    (*env)->DeleteLocalRef(env, jxml);
    if (!jreply) return;
    jsize rlen = (*env)->GetArrayLength(env, jreply);
    char *rxml = malloc(rlen + 1);
    if (rxml) {
        (*env)->GetByteArrayRegion(env, jreply, 0, rlen, (jbyte *) rxml);
        rxml[rlen] = 0;
        plist_t rroot = NULL;
        plist_from_xml(rxml, (uint32_t) rlen, &rroot);
        free(rxml);
        if (rroot) {
            char *bin = NULL;
            uint32_t bin_len = 0;
            plist_to_bin(rroot, &bin, &bin_len);
            plist_free(rroot);
            if (bin) {
                /* raop.c frees the response body with free() */
                *reply = malloc(bin_len);
                if (*reply) { memcpy(*reply, bin, bin_len); *reply_len = (int) bin_len; }
                plist_mem_free(bin);
            }
        }
    }
    (*env)->DeleteLocalRef(env, jreply);
}

static void _av2_info(void *cls, const char *data, int datalen) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env || !data || datalen <= 0) return;
    plist_t root = NULL;
    plist_from_bin(data, (uint32_t) datalen, &root);
    if (!root) return;
    char *xml = NULL;
    uint32_t xml_len = 0;
    plist_to_xml(root, &xml, &xml_len);
    plist_free(root);
    if (!xml) return;
    jbyteArray jxml = _bytes(env, xml);
    plist_mem_free(xml);
    if (!jxml) return;
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_av2_info, jxml);
    (*env)->DeleteLocalRef(env, jxml);
}

static void _av2_record(void *cls) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_av2_record);
}

static void _hap_paired(void *cls, const unsigned char *secret, int len) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env || !secret || len <= 0) return;
    jbyteArray js = (*env)->NewByteArray(env, len);
    if (!js) return;
    (*env)->SetByteArrayRegion(env, js, 0, len, (const jbyte *) secret);
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_hap_paired, js);
    (*env)->DeleteLocalRef(env, js);
}

/* Stubs for less critical callbacks */
static void _noop(void *cls) { (void)cls; }
static void _video_pause(void *cls) { LOGI("video_pause"); }
static void _video_resume(void *cls) { LOGI("video_resume"); }
static void _conn_feedback(void *cls) { (void)cls; }
static void _video_stop(void *cls);
static void _video_reset(void *cls, reset_type_t t) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    LOGI("video_reset %d", t);
    if (t == RESET_TYPE_NOHOLD) {
        JNIEnv *env = _get_env(ctx);
        if (env) (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_sender_replaced);
    }
    if (t == RESET_TYPE_HLS_SHUTDOWN || t == RESET_TYPE_HLS_EOS) {
        _video_stop(cls);
    }
    if (t == RESET_TYPE_HLS_CONN_CLOSED) {
        /* abandoned only if paused and ready: rate alone is also 0 while buffering */
        pthread_mutex_lock(&ctx->playback_info_lock);
        int paused = ctx->playback_ready && ctx->playback_rate <= 0.0f;
        pthread_mutex_unlock(&ctx->playback_info_lock);
        if (paused) {
            _video_stop(cls);
        }
    }
    if (t == RESET_TYPE_HLS_SHUTDOWN && ctx->raop) {
        raop_remove_hls_connections(ctx->raop);
    }
}
static void _audio_flush(void *cls) { LOGI("audio_flush"); }

static void _audio_stop_coverart_rendering(void *cls) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    LOGI("audio_teardown");
    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_audio_teardown);
}
static void _video_flush(void *cls) { LOGI("video_flush"); }
static double _audio_set_client_volume(void *cls) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env) return 0.0;
    return (double)(*env)->CallFloatMethod(env, ctx->callback_obj, ctx->on_client_volume);
}
static void _audio_set_metadata(void *cls, const void *buf, int len) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env || !buf || len <= 0) return;
    jbyteArray arr = (*env)->NewByteArray(env, len);
    (*env)->SetByteArrayRegion(env, arr, 0, len, (jbyte *)buf);
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_metadata, arr);
    (*env)->DeleteLocalRef(env, arr);
}

static void _audio_set_coverart(void *cls, const void *buf, int len) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env || !buf || len <= 0) return;
    jbyteArray arr = (*env)->NewByteArray(env, len);
    (*env)->SetByteArrayRegion(env, arr, 0, len, (jbyte *)buf);
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_coverart, arr);
    (*env)->DeleteLocalRef(env, arr);
}

static void _audio_remote_control_id(void *cls, const char *dacp_id, const char *active_remote) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    jstring jdacp = (*env)->NewStringUTF(env, dacp_id ? dacp_id : "");
    jstring jremote = (*env)->NewStringUTF(env, active_remote ? active_remote : "");
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_dacp_id, jdacp, jremote);
    (*env)->DeleteLocalRef(env, jdacp);
    (*env)->DeleteLocalRef(env, jremote);
}

static void _audio_set_progress(void *cls, uint32_t *start, uint32_t *curr, uint32_t *end) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env || !start || !curr || !end) return;
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_progress,
                           (jlong)*start, (jlong)*curr, (jlong)*end);
}

static void _mirror_video_running(void *cls, bool running) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    LOGI("mirror running: %d", running);
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_mirror_running, (jboolean)running);
}
static void _register_client(void *cls, const char *device_id, const char *pk_str, const char *name) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    (void)device_id; (void)name;
    if (ctx->registered_count >= 16) return;
    for (int i = 0; i < ctx->registered_count; i++) {
        if (ctx->registered_keys[i] && strcmp(ctx->registered_keys[i], pk_str) == 0) return;
    }
    ctx->registered_keys[ctx->registered_count++] = strdup(pk_str);
    LOGI("registered client pk (slot %d)", ctx->registered_count);
}

static bool _check_register(void *cls, const char *pk_str) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    for (int i = 0; i < ctx->registered_count; i++) {
        if (ctx->registered_keys[i] && strcmp(ctx->registered_keys[i], pk_str) == 0) return true;
    }
    return false;
}

/* --- AirPlay Video (HLS) playback callbacks --- */

static void _video_play(void *cls, const char *location, const float start_position) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    LOGI("video_play: %s @ %.2fs", location ? location : "(null)", start_position);
    pthread_mutex_lock(&ctx->playback_info_lock);
    ctx->play_ready = 0;
    pthread_mutex_unlock(&ctx->playback_info_lock);
    android_callbacks_update_playback_info(ctx, start_position, 0.0, 0.0f, 0);
    JNIEnv *env = _get_env(ctx);
    if (!env || !location) return;
    jstring jloc = (*env)->NewStringUTF(env, location);
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_video_play, jloc, (jfloat)start_position);
    (*env)->DeleteLocalRef(env, jloc);
    /* self-driven senders (macOS) latch their scrubber timeline at /play; hold the response
       until the player reports ready so that read must carry the real duration */
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    ts.tv_sec += 10; // hold for max 10s
    pthread_mutex_lock(&ctx->playback_info_lock);
    while (!ctx->play_ready) {
        if (pthread_cond_timedwait(&ctx->play_ready_cond, &ctx->playback_info_lock, &ts) == ETIMEDOUT) break;
    }
    pthread_mutex_unlock(&ctx->playback_info_lock);
}

static void _video_scrub(void *cls, const float position) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_video_scrub, (jfloat)position);
}

static void _video_rate(void *cls, const float rate) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_video_rate, (jfloat)rate);
}

static void _video_stop(void *cls) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    android_callbacks_update_playback_info(ctx, 0.0, -1.0, 0.0f, 0);
    JNIEnv *env = _get_env(ctx);
    if (!env) return;
    (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_video_stop);
}

/* httpd thread: reads the kotlin-pushed snapshot, never calls into the player */
static void _video_acquire_playback_info(void *cls, playback_info_t *info) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    pthread_mutex_lock(&ctx->playback_info_lock);
    info->position = ctx->playback_position;
    info->duration = ctx->playback_duration;
    info->rate = ctx->playback_rate;
    info->ready_to_play = ctx->playback_ready;
    info->playback_buffer_empty = false;
    info->playback_buffer_full = true;
    info->playback_likely_to_keep_up = true;
    info->seek_start = 0.0;
    info->seek_duration = ctx->playback_duration > 0.0 ? ctx->playback_duration : 0.0;
    pthread_mutex_unlock(&ctx->playback_info_lock);
    /* polls are the earliest video-channel signal, starting ~1s before /play */
    JNIEnv *env = _get_env(ctx);
    if (env) {
        (*env)->CallVoidMethod(env, ctx->callback_obj, ctx->on_video_session_poll);
    }
}

static float _video_playlist_remove(void *cls) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    pthread_mutex_lock(&ctx->playback_info_lock);
    double position = ctx->playback_position;
    pthread_mutex_unlock(&ctx->playback_info_lock);
    return (float) position;
}

static int _video_set_codec(void *cls, video_codec_t codec) {
    android_callback_ctx_t *ctx = (android_callback_ctx_t *)cls;
    LOGI("video_set_codec: %d (h265_enabled=%d)", codec, ctx->h265_enabled);
    if (codec == VIDEO_CODEC_H265 && !ctx->h265_enabled) return -1;
    return 0;
}

void android_callbacks_fill(raop_callbacks_t *cbs, android_callback_ctx_t *ctx) {
    memset(cbs, 0, sizeof(raop_callbacks_t));
    cbs->cls = ctx;

    cbs->audio_process = _audio_process;
    cbs->video_process = _video_process;
    cbs->video_pause = _video_pause;
    cbs->video_resume = _video_resume;
    cbs->conn_feedback = _conn_feedback;
    cbs->conn_reset = _conn_reset;
    cbs->video_reset = _video_reset;
    cbs->conn_init = _conn_init;
    cbs->conn_destroy = _conn_destroy;
    cbs->audio_flush = _audio_flush;
    cbs->video_flush = _video_flush;
    cbs->audio_set_client_volume = _audio_set_client_volume;
    cbs->audio_set_volume = _audio_set_volume;
    cbs->audio_set_metadata = _audio_set_metadata;
    cbs->audio_set_coverart = _audio_set_coverart;
    cbs->audio_stop_coverart_rendering = _audio_stop_coverart_rendering;
    cbs->audio_remote_control_id = _audio_remote_control_id;
    cbs->audio_set_progress = _audio_set_progress;
    cbs->audio_get_format = _audio_get_format;
    cbs->video_report_size = _video_report_size;
    cbs->mirror_video_running = _mirror_video_running;
    cbs->display_pin = _display_pin;
    cbs->report_client_request = _report_client_request;
#ifdef TVAIRPLAY_VIDEO_V2
    /* AirPlay video v2 (macOS QuickTime) is unfinished: macOS 27 stops after updateInfo.
       Off by default so senders keep using the paths that work. */
    cbs->av2_event_port = _av2_event_port;
    cbs->av2_stream_setup = _av2_stream_setup;
    cbs->av2_command = _av2_command;
    cbs->av2_info = _av2_info;
    cbs->av2_record = _av2_record;
    cbs->hap_paired = _hap_paired;
#endif
    cbs->video_set_codec = _video_set_codec;
    cbs->on_video_play = _video_play;
    cbs->on_video_scrub = _video_scrub;
    cbs->on_video_rate = _video_rate;
    cbs->on_video_stop = _video_stop;
    cbs->on_video_acquire_playback_info = _video_acquire_playback_info;
    cbs->on_video_playlist_remove = _video_playlist_remove;
    if (ctx->require_pin) {
        cbs->check_register = _check_register;
        cbs->register_client = _register_client;
    }
}
