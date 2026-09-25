// JNI bridge for all native entry points of libpixel_lab_native. Method
// signatures mirror com.pixellab.core.nativelib exactly (see the Kotlin
// objects; the JVM resolves these by name+descriptor).
#include <jni.h>
#include <android/log.h>

#include "common/jni_common.h"
#include "quantization/quantization.h"
#include "dithering/dithering.h"
#include "pixel_ops/pixel_ops.h"
#include "gif/gif_encoder.h"

#include <cstring>
#include <string>
#include <vector>

namespace {

const char* kTag = "pixel-lab-native";

#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, kTag, __VA_ARGS__)

std::string fromJString(JNIEnv* env, jstring s) {
    if (s == nullptr) {
        return {};
    }
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars != nullptr ? chars : "");
    if (chars != nullptr) {
        env->ReleaseStringUTFChars(s, chars);
    }
    return out;
}

} // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_pixellab_core_nativelib_NativeLib_nativeVersion(JNIEnv* env, jobject) {
    return env->NewStringUTF("1.0.0");
}

// ---- NativeQuantizer ---------------------------------------------------------

JNIEXPORT jint JNICALL
Java_com_pixellab_core_nativelib_NativeQuantizer_nativeQuantize(
        JNIEnv* env, jobject, jintArray pixels, jint pixelCount, jint targetColors,
        jint algorithmId, jintArray outPalette, jintArray outMapped) {
    if (pixels == nullptr || outPalette == nullptr || outMapped == nullptr) {
        pixel_lab::throwIAE(env, "null array argument");
        return 0;
    }
    const jsize size = env->GetArrayLength(pixels);
    if (pixelCount != size || pixelCount <= 0 || targetColors < 1 ||
        env->GetArrayLength(outMapped) != size) {
        pixel_lab::throwIAE(env, "quantize argument shape mismatch");
        return 0;
    }
    pixel_lab::ScopedIntArray in(env, pixels);
    if (!in.valid()) {
        return 0;
    }
    pixel_lab::QuantizeOutput result;
    switch (algorithmId) {
        case 1:
            pixel_lab::kmeans(reinterpret_cast<const uint32_t*>(in.get()),
                              static_cast<size_t>(pixelCount), targetColors, result);
            break;
        case 2:
            pixel_lab::octreeQuantize(reinterpret_cast<const uint32_t*>(in.get()),
                                      static_cast<size_t>(pixelCount), targetColors, result);
            break;
        case 0:
        default:
            pixel_lab::medianCut(reinterpret_cast<const uint32_t*>(in.get()),
                                 static_cast<size_t>(pixelCount), targetColors, result);
            break;
    }
    if (result.palette.empty()) {
        pixel_lab::throwIAE(env, "quantization produced no colors");
        return 0;
    }
    const jsize paletteLen = env->GetArrayLength(outPalette);
    const jsize copyLen = static_cast<jsize>(result.palette.size()) < paletteLen
                              ? static_cast<jsize>(result.palette.size())
                              : paletteLen;
    env->SetIntArrayRegion(outPalette, 0, copyLen,
                           reinterpret_cast<const jint*>(result.palette.data()));
    env->SetIntArrayRegion(outMapped, 0, size,
                           reinterpret_cast<const jint*>(result.mapped.data()));
    return copyLen;
}

// ---- NativeDitherer ----------------------------------------------------------

JNIEXPORT void JNICALL
Java_com_pixellab_core_nativelib_NativeDitherer_nativeDither(
        JNIEnv* env, jobject, jintArray pixels, jint width, jint height,
        jintArray palette, jint paletteCount, jint algorithmId, jfloat intensity,
        jintArray outPixels) {
    if (pixels == nullptr || palette == nullptr || outPixels == nullptr) {
        pixel_lab::throwIAE(env, "null array argument");
        return;
    }
    if (width <= 0 || height <= 0 || paletteCount < 1 ||
        env->GetArrayLength(pixels) != width * height ||
        env->GetArrayLength(palette) != paletteCount ||
        env->GetArrayLength(outPixels) != width * height) {
        pixel_lab::throwIAE(env, "dither argument shape mismatch");
        return;
    }
    pixel_lab::ScopedIntArray in(env, pixels);
    pixel_lab::ScopedIntArray pal(env, palette);
    if (!in.valid() || !pal.valid()) {
        return;
    }
    const auto kernel = static_cast<pixel_lab::DitherKernel>(
        algorithmId < 0 ? 0 : (algorithmId > 6 ? 6 : algorithmId));
    pixel_lab::applyDither(reinterpret_cast<const uint32_t*>(in.get()), width, height,
                           reinterpret_cast<const uint32_t*>(pal.get()),
                           static_cast<size_t>(paletteCount), kernel, intensity,
                           reinterpret_cast<uint32_t*>(in.get()));
    // The in-place result in the pinned input buffer is copied to the output.
    env->SetIntArrayRegion(outPixels, 0, width * height, in.get());
}

// ---- NativePixelOps ----------------------------------------------------------

JNIEXPORT jint JNICALL
Java_com_pixellab_core_nativelib_NativePixelOps_nativeSetPixelsBatch(
        JNIEnv* env, jobject, jintArray pixels, jint width, jint height,
        jintArray points, jint pointCount, jint argb, jintArray out) {
    if (pixels == nullptr || points == nullptr || out == nullptr) {
        pixel_lab::throwIAE(env, "null array argument");
        return -1;
    }
    if (width <= 0 || height <= 0 || pointCount < 0 ||
        env->GetArrayLength(points) != pointCount * 2 ||
        env->GetArrayLength(pixels) != width * height ||
        env->GetArrayLength(out) != width * height) {
        pixel_lab::throwIAE(env, "batch write argument shape mismatch");
        return -1;
    }
    pixel_lab::ScopedIntArray in(env, pixels);
    pixel_lab::ScopedIntArray pts(env, points);
    if (!in.valid() || !pts.valid()) {
        return -1;
    }
    const int64_t written = pixel_lab::setPixelsBatch(
        reinterpret_cast<const uint32_t*>(in.get()), width, height, pts.get(),
        static_cast<size_t>(pointCount), static_cast<uint32_t>(argb),
        reinterpret_cast<uint32_t*>(in.get()));
    env->SetIntArrayRegion(out, 0, width * height, in.get());
    return static_cast<jint>(written);
}

JNIEXPORT jint JNICALL
Java_com_pixellab_core_nativelib_NativePixelOps_nativeFloodFill(
        JNIEnv* env, jobject, jintArray pixels, jint width, jint height, jint x,
        jint y, jint replacement, jint tolerance, jintArray out) {
    if (pixels == nullptr || out == nullptr) {
        pixel_lab::throwIAE(env, "null array argument");
        return -1;
    }
    if (width <= 0 || height <= 0 ||
        env->GetArrayLength(pixels) != width * height ||
        env->GetArrayLength(out) != width * height) {
        pixel_lab::throwIAE(env, "flood fill argument shape mismatch");
        return -1;
    }
    pixel_lab::ScopedIntArray in(env, pixels);
    if (!in.valid()) {
        return -1;
    }
    const int64_t changed = pixel_lab::floodFill(
        reinterpret_cast<const uint32_t*>(in.get()), width, height, x, y,
        static_cast<uint32_t>(replacement), tolerance,
        reinterpret_cast<uint32_t*>(in.get()));
    env->SetIntArrayRegion(out, 0, width * height, in.get());
    return static_cast<jint>(changed);
}

JNIEXPORT jint JNICALL
Java_com_pixellab_core_nativelib_NativePixelOps_nativeCompositeLayers(
        JNIEnv* env, jobject, jobjectArray layers, jintArray layerSizes,
        jintArray widths, jintArray heights, jfloatArray opacities, jint layerCount,
        jintArray out) {
    if (layers == nullptr || layerSizes == nullptr || widths == nullptr ||
        heights == nullptr || opacities == nullptr || out == nullptr) {
        pixel_lab::throwIAE(env, "null array argument");
        return -1;
    }
    const jsize frameCount = env->GetArrayLength(layers);
    if (layerCount != frameCount || frameCount < 1 ||
        env->GetArrayLength(layerSizes) != frameCount ||
        env->GetArrayLength(widths) != frameCount ||
        env->GetArrayLength(heights) != frameCount ||
        env->GetArrayLength(opacities) != frameCount) {
        pixel_lab::throwIAE(env, "composite argument shape mismatch");
        return -1;
    }
    std::vector<int32_t*> buffers;
    std::vector<jintArray> refs;
    if (!pixel_lab::pinFrameArrays(env, layers, buffers, refs)) {
        pixel_lab::releaseFrameArrays(env, buffers, refs, JNI_ABORT);
        return -1;
    }
    pixel_lab::ScopedIntArray w(env, widths);
    pixel_lab::ScopedIntArray h(env, heights);
    pixel_lab::ScopedFloatArray op(env, opacities);
    if (!w.valid() || !h.valid() || !op.valid()) {
        pixel_lab::releaseFrameArrays(env, buffers, refs, JNI_ABORT);
        return -1;
    }
    std::vector<const uint32_t*> layerPtrs(frameCount);
    std::vector<int> layerW(frameCount), layerH(frameCount);
    std::vector<float> layerOp(frameCount);
    for (jsize i = 0; i < frameCount; ++i) {
        layerPtrs[i] = reinterpret_cast<const uint32_t*>(buffers[i]);
        layerW[i] = w.get()[i];
        layerH[i] = h.get()[i];
        layerOp[i] = op.get()[i];
    }
    const size_t pixelCount = static_cast<size_t>(layerW[0]) * layerH[0];
    if (env->GetArrayLength(out) != static_cast<jsize>(pixelCount)) {
        pixel_lab::throwIAE(env, "composite output size mismatch");
        pixel_lab::releaseFrameArrays(env, buffers, refs, JNI_ABORT);
        return -1;
    }
    std::vector<uint32_t> composite(pixelCount);
    const int ok = pixel_lab::compositeLayers(layerPtrs, layerW, layerH, layerOp, composite.data());
    pixel_lab::releaseFrameArrays(env, buffers, refs, JNI_ABORT);
    if (ok != 0) {
        pixel_lab::throwIAE(env, "native composite failed");
        return -1;
    }
    env->SetIntArrayRegion(out, 0, static_cast<jsize>(pixelCount),
                           reinterpret_cast<const jint*>(composite.data()));
    return 0;
}

// ---- NativeGifEncoder --------------------------------------------------------

JNIEXPORT jbyteArray JNICALL
Java_com_pixellab_core_nativelib_NativeGifEncoder_nativeEncodeGif(
        JNIEnv* env, jobject, jint width, jint height, jobjectArray frames,
        jintArray frameSizes, jint frameCount, jintArray delaysMs, jint loopCount,
        jint quantAlgorithmId, jint ditherId) {
    if (frames == nullptr || frameSizes == nullptr || delaysMs == nullptr) {
        pixel_lab::throwIAE(env, "null array argument");
        return nullptr;
    }
    if (width <= 0 || height <= 0 || frameCount < 1 ||
        env->GetArrayLength(frames) != frameCount ||
        env->GetArrayLength(frameSizes) != frameCount ||
        env->GetArrayLength(delaysMs) != frameCount) {
        pixel_lab::throwIAE(env, "gif argument shape mismatch");
        return nullptr;
    }
    std::vector<int32_t*> buffers;
    std::vector<jintArray> refs;
    if (!pixel_lab::pinFrameArrays(env, frames, buffers, refs)) {
        pixel_lab::releaseFrameArrays(env, buffers, refs, JNI_ABORT);
        return nullptr;
    }
    pixel_lab::ScopedIntArray sizes(env, frameSizes);
    pixel_lab::ScopedIntArray delays(env, delaysMs);
    if (!sizes.valid() || !delays.valid()) {
        pixel_lab::releaseFrameArrays(env, buffers, refs, JNI_ABORT);
        return nullptr;
    }
    std::vector<const uint32_t*> framePtrs(frameCount);
    std::vector<size_t> frameLen(frameCount);
    std::vector<int> delayList(frameCount);
    for (jsize i = 0; i < frameCount; ++i) {
        framePtrs[i] = reinterpret_cast<const uint32_t*>(buffers[i]);
        frameLen[i] = static_cast<size_t>(sizes.get()[i]);
        delayList[i] = delays.get()[i];
    }
    std::vector<uint8_t> gif;
    const bool ok = pixel_lab::encodeGif(width, height, framePtrs, frameLen, delayList,
                                         loopCount, quantAlgorithmId, ditherId, gif);
    pixel_lab::releaseFrameArrays(env, buffers, refs, JNI_ABORT);
    if (!ok || gif.empty()) {
        pixel_lab::throwIAE(env, "native GIF encoding failed");
        return nullptr;
    }
    jbyteArray result = env->NewByteArray(static_cast<jsize>(gif.size()));
    if (result == nullptr) {
        return nullptr;
    }
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(gif.size()),
                            reinterpret_cast<const jbyte*>(gif.data()));
    LOGD("encoded GIF: %d x %d, %d frames, %zu bytes", width, height, frameCount, gif.size());
    return result;
}

} // extern "C"
