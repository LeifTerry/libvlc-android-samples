/*****************************************************************************
 * main.c
 *****************************************************************************
 * Copyright (C) 2016 VideoLAN
 * All rights reserved.
 *
 * This software may be modified and distributed under the terms
 * of the BSD license. See the LICENSE file for details.
 *****************************************************************************/

#include <stdlib.h>

#include <jni.h>
#include <vlc/vlc.h>
#include <vlc/libvlc_media_player.h>

#define SAMPLE_URL "http://download.blender.org/peach/bigbuckbunny_movies/BigBuckBunny_640x360.m4v"

enum surface_layout
{
    SURFACE_BEST_FIT,
    SURFACE_16_9,
    SURFACE_4_3,
    SURFACE_ORIGINAL,
};

static const enum surface_layout surface_layout = SURFACE_BEST_FIT;

static struct
{
    struct {
        jfieldID mInstance;
    } NativeActivity;
} fields;

struct context
{
    libvlc_instance_t *     p_libvlc;
    libvlc_media_player_t * p_mp;
    jobject                 jaWindow;
};

struct context *
getInstance(JNIEnv *p_env, jobject thiz)
{
    return (struct context*)(intptr_t)
        (*p_env)->GetLongField(p_env, thiz, fields.NativeActivity.mInstance);
}


static void
setInstance(JNIEnv *p_env, jobject thiz, struct context *p_ctx)
{
    (*p_env)->SetLongField(p_env, thiz, fields.NativeActivity.mInstance,
                         (jlong)(intptr_t)p_ctx);
}

bool
Java_org_videolan_nativesample_NativeActivity_nativeCreate(
    JNIEnv *p_env, jobject thiz)
{
    struct context *p_ctx = calloc(1, sizeof(struct context));
    if (p_ctx == NULL)
        return false;

    static const char *pp_argv[] = {
        "-vvv",
        "--audio-resampler", "soxr",
    };
    p_ctx->p_libvlc = libvlc_new(sizeof(pp_argv)/sizeof(*pp_argv), pp_argv);
    if (p_ctx->p_libvlc == NULL)
    {
        free(p_ctx);
        return false;
    }

    p_ctx->p_mp = libvlc_media_player_new(p_ctx->p_libvlc);
    if (p_ctx->p_mp == NULL)
    {
        libvlc_release(p_ctx->p_libvlc);
        free(p_ctx);
        return false;
    }

    setInstance(p_env, thiz, p_ctx);
    return true;
}

void
Java_org_videolan_nativesample_NativeActivity_nativeDestroy(
    JNIEnv *p_env, jobject thiz)
{
    struct context *p_ctx = getInstance(p_env, thiz);
    if (p_ctx == NULL)
        return;

    libvlc_media_player_release(p_ctx->p_mp);
    libvlc_release(p_ctx->p_libvlc);
    free(p_ctx);
}

static void
UpdateSurfaceLayout(struct context *p_ctx, enum surface_layout layout)
{
    switch (layout)
    {
        case SURFACE_BEST_FIT:
            libvlc_video_set_aspect_ratio(p_ctx->p_mp, NULL);
            libvlc_video_set_scale(p_ctx->p_mp, 0);
            break;
        case SURFACE_16_9:
            libvlc_video_set_aspect_ratio(p_ctx->p_mp, "16:9");
            libvlc_video_set_scale(p_ctx->p_mp, 0);
            break;
        case SURFACE_4_3:
            libvlc_video_set_aspect_ratio(p_ctx->p_mp, "4:3");
            libvlc_video_set_scale(p_ctx->p_mp, 0);
            break;
        case SURFACE_ORIGINAL:
            libvlc_video_set_aspect_ratio(p_ctx->p_mp, NULL);
            libvlc_video_set_scale(p_ctx->p_mp, 1);
            break;
    }
}

static bool
PlayUrl(struct context *p_ctx, const char *psz_url)
{
    libvlc_media_t *p_m;

    if (psz_url[0] == '/')
        p_m = libvlc_media_new_path(p_ctx->p_libvlc, psz_url);
    else
        p_m = libvlc_media_new_location(p_ctx->p_libvlc, psz_url);
    if (p_m == NULL)
        return false;

    libvlc_media_add_option(p_m, ":file-caching=1500");
    libvlc_media_add_option(p_m, ":network-caching=1500");
    libvlc_media_add_option(p_m, ":codec=mediacodec_ndk,mediacodec_jni,all");

    libvlc_media_player_set_media(p_ctx->p_mp, p_m);
    UpdateSurfaceLayout(p_ctx, surface_layout);
    int i_ret = libvlc_media_player_play(p_ctx->p_mp);

    /* Instance is now owned by the media player */
    libvlc_media_release(p_m);
    return i_ret == 0;
}

static void
Stop(struct context *p_ctx)
{
    libvlc_media_player_stop(p_ctx->p_mp);
}

bool
Java_org_videolan_nativesample_NativeActivity_nativeStart(
    JNIEnv *p_env, jobject thiz, jobject jaWindow)
{
    struct context *p_ctx = getInstance(p_env, thiz);
    if (p_ctx == NULL)
        return false;

    p_ctx->jaWindow = (*p_env)->NewGlobalRef(p_env, jaWindow);

    libvlc_media_player_set_android_context(p_ctx->p_mp, p_ctx->jaWindow);
    return PlayUrl(p_ctx, SAMPLE_URL);
}

void
Java_org_videolan_nativesample_NativeActivity_nativeStop(
    JNIEnv *p_env, jobject thiz)
{
    struct context *p_ctx = getInstance(p_env, thiz);
    if (p_ctx == NULL)
        return;

    Stop(p_ctx);

    libvlc_media_player_set_android_context(p_ctx->p_mp, NULL);
    (*p_env)->DeleteGlobalRef(p_env, p_ctx->jaWindow);
    p_ctx->jaWindow = NULL;
}

jint
JNI_OnLoad(JavaVM *vm, void *reserved)
{
    JNIEnv *p_env;

    if ((*vm)->GetEnv(vm, (void**) &p_env, JNI_VERSION_1_2) != JNI_OK)
        return -1;

    jclass cls =
        (*p_env)->FindClass(p_env, "org/videolan/nativesample/NativeActivity");
    if (!cls)
        return -1;

    fields.NativeActivity.mInstance =
        (*p_env)->GetFieldID(p_env, cls, "mInstance", "J");
    if (!fields.NativeActivity.mInstance)
        return -1;

    (*p_env)->DeleteLocalRef(p_env, cls);

    return JNI_VERSION_1_2;
}
