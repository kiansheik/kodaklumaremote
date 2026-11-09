#include "NesEngine.h"
#include <jni.h>
#include <memory>
#include <mutex>

static std::mutex engineMutex;
static void fail(JNIEnv* env, const char* message) { env->ThrowNew(env->FindClass("java/io/IOException"), message); }
static std::vector<uint8_t> bytes(JNIEnv* env, jbyteArray value) {
    std::vector<uint8_t> result(env->GetArrayLength(value));
    env->GetByteArrayRegion(value, 0, result.size(), reinterpret_cast<jbyte*>(result.data()));
    return result;
}
#define JNI(name) Java_dev_framextv_lumareceiver_NesNative_##name
extern "C" JNIEXPORT jlong JNICALL JNI(create)(JNIEnv* env, jclass, jbyteArray rom) {
    std::lock_guard<std::mutex> lock(engineMutex);
    try { return reinterpret_cast<jlong>(new NesEngine(bytes(env, rom))); }
    catch (const std::exception& e) { fail(env, e.what()); return 0; }
}
extern "C" JNIEXPORT jint JNICALL JNI(step)(JNIEnv* env, jclass, jlong handle, jint buttons, jintArray pixels, jshortArray audio) {
    std::lock_guard<std::mutex> lock(engineMutex);
    if (!handle || env->GetArrayLength(pixels) != 256 * 240 || env->GetArrayLength(audio) < 4096) {
        fail(env, "Invalid NES frame buffers."); return 0;
    }
    uint32_t frame[256 * 240];
    int16_t samples[4096];
    try {
        int count = reinterpret_cast<NesEngine*>(handle)->step(buttons, frame, samples);
        env->SetIntArrayRegion(pixels, 0, 256 * 240, reinterpret_cast<jint*>(frame));
        env->SetShortArrayRegion(audio, 0, count, samples);
        return count;
    } catch (const std::exception& e) { fail(env, e.what()); return 0; }
}
extern "C" JNIEXPORT jbyteArray JNICALL JNI(save)(JNIEnv* env, jclass, jlong handle, jboolean battery) {
    std::lock_guard<std::mutex> lock(engineMutex);
    if (!handle) { fail(env, "No game loaded."); return nullptr; }
    try {
        auto state = reinterpret_cast<NesEngine*>(handle)->save(battery);
        auto result = env->NewByteArray(state.size());
        if (result) env->SetByteArrayRegion(result, 0, state.size(), reinterpret_cast<jbyte*>(state.data()));
        return result;
    } catch (const std::exception& e) { fail(env, e.what()); return nullptr; }
}
extern "C" JNIEXPORT void JNICALL JNI(restore)(JNIEnv* env, jclass, jlong handle, jbyteArray state, jboolean battery) {
    std::lock_guard<std::mutex> lock(engineMutex);
    if (!handle) { fail(env, "No game loaded."); return; }
    try { reinterpret_cast<NesEngine*>(handle)->restore(bytes(env, state), battery); }
    catch (const std::exception& e) { fail(env, e.what()); }
}
extern "C" JNIEXPORT void JNICALL JNI(destroy)(JNIEnv*, jclass, jlong handle) {
    std::lock_guard<std::mutex> lock(engineMutex);
    delete reinterpret_cast<NesEngine*>(handle);
}
