#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include "rnnoise.h"

#define FRAME_SIZE 480

JNIEXPORT jlong JNICALL
Java_com_clipforge_app_RnnoiseNative_create(JNIEnv *env, jobject thiz) {
    DenoiseState *st = rnnoise_create(NULL);
    return (jlong)(intptr_t)st;
}

JNIEXPORT void JNICALL
Java_com_clipforge_app_RnnoiseNative_destroy(JNIEnv *env, jobject thiz, jlong handle) {
    DenoiseState *st = (DenoiseState *)(intptr_t)handle;
    if (st) rnnoise_destroy(st);
}

JNIEXPORT jint JNICALL
Java_com_clipforge_app_RnnoiseNative_getFrameSize(JNIEnv *env, jobject thiz) {
    return rnnoise_get_frame_size();
}

JNIEXPORT jfloat JNICALL
Java_com_clipforge_app_RnnoiseNative_processFrame(
    JNIEnv *env, jobject thiz, jlong handle,
    jfloatArray inArray, jfloatArray outArray) {

    DenoiseState *st = (DenoiseState *)(intptr_t)handle;
    if (!st) return 0.0f;

    jfloat *in = (*env)->GetFloatArrayElements(env, inArray, NULL);
    jfloat *out = (*env)->GetFloatArrayElements(env, outArray, NULL);

    float vad = rnnoise_process_frame(st, out, in);

    (*env)->ReleaseFloatArrayElements(env, inArray, in, JNI_ABORT);
    (*env)->ReleaseFloatArrayElements(env, outArray, out, 0);

    return vad;
}
