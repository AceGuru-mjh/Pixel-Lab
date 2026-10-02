// JNI bridge for all native entry points of libpixel_lab_native. Method
// signatures mirror com.pixellab.core.nativelib exactly (see the Kotlin
// objects; the JVM resolves these by name+descriptor).
//
// Every entry point is wrapped in PIXEL_LAB_JNI_TRY / PIXEL_LAB_JNI_CATCH:
// C++ exceptions (std::bad_alloc from big vector allocations, anything the
// algorithm code throws) must never cross the JNI boundary — the JVM would
// terminate the process instead of raising a catchable Java exception, so
// the Kotlin-side fallback (which catches UnsatisfiedLinkError and
// IllegalArgumentException) would never run. ScopedIntArray / PinnedFrames
// release their pins while the stack unwinds, keeping the guards leak-free.
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

} // namespace

#define PIXEL_LAB_JNI_TRY try {
#define PIXEL_LAB_JNI_CATCH(env, ret)                                     \
    } catch (...) {                                                       \
        pixel_lab::translatePendingCppException(env);                    \
        return ret;                                                       \
    }

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_pixellab_core_nativelib_NativeLib_nativeVersion(JNIEnv* env, jobject) {
    PIXEL_LAB_JNI_TRY
    return env->NewStringUTF("1.0.0");
    PIXEL_LAB_JNI_CATCH(env, nullptr)
}

// ---- NativeQuantizer ---------------------------------------------------------

JNIEXPORT jint JNICALL
Java_com_pixellab_core_nativelib_NativeQuantizer_nativeQuantize(
        JNIEnv* env, jobject, jintArray pixels, jint pixelCount, jint targetColors,
        jint algorithmId, jintArray outPalette, jintArray outMapped) {
    PIXEL_LAB_JNI_TRY
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
    pixel_lab::ScopedIntArray in(env, pixels, /*copyBack=*/false);
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
    PIXEL_LAB_JNI_CATCH(env, 0)
}

// ---- NativeDitherer ----------------------------------------------------------

JNIEXPORT void JNICALL
Java_com_pixellab_core_nativelib_NativeDitherer_nativeDither(
        JNIEnv* env, jobject, jintArray pixels, jint width, jint height,
        jintArray palette, jint paletteCount, jint algorithmId, jfloat intensity,
        jintArray outPixels) {
    PIXEL_LAB_JNI_TRY
    if (pixels == nullptr || palette == nullptr || outPixels == nullptr) {
        pixel_lab::throwIAE(env, "null array argument");
        return;
    }
    if (width <= 0 || height <= 0 || paletteCount < 1 ||
        static_cast<jlong>(env->GetArrayLength(pixels)) != static_cast<jlong>(width) * height ||
        env->GetArrayLength(palette) != paletteCount ||
        static_cast<jlong>(env->GetArrayLength(outPixels)) != static_cast<jlong>(width) * height) {
        pixel_lab::throwIAE(env, "dither argument shape mismatch");
        return;
    }
    // Inputs are read-only: on a direct-pin runtime (isCopy == false)
    // GetIntArrayElements returns the CALLER's array memory, so writing
    // through the view would mutate the immutable input frame before any
    // release mode could matter. Compute into local scratch and copy out.
    pixel_lab::ScopedIntArray in(env, pixels, /*copyBack=*/false);
    pixel_lab::ScopedIntArray pal(env, palette, /*copyBack=*/false);
    if (!in.valid() || !pal.valid()) {
        return;
    }
    const auto kernel = static_cast<pixel_lab::DitherKernel>(
        algorithmId < 0 ? 0 : (algorithmId > 6 ? 6 : algorithmId));
    std::vector<uint32_t> scratch(static_cast<size_t>(width) * height);
    pixel_lab::applyDither(reinterpret_cast<const uint32_t*>(in.get()), width, height,
                           reinterpret_cast<const uint32_t*>(pal.get()),
                           static_cast<size_t>(paletteCount), kernel, intensity,
                           scratch.data());
    env->SetIntArrayRegion(outPixels, 0, env->GetArrayLength(outPixels),
                           reinterpret_cast<const jint*>(scratch.data()));
    PIXEL_LAB_JNI_CATCH(env, )
}

// ---- NativePixelOps ----------------------------------------------------------

JNIEXPORT jint JNICALL
Java_com_pixellab_core_nativelib_NativePixelOps_nativeSetPixelsBatch(
        JNIEnv* env, jobject, jintArray pixels, jint width, jint height,
        jintArray points, jint pointCount, jint argb, jintArray out) {
    PIXEL_LAB_JNI_TRY
    if (pixels == nullptr || points == nullptr || out == nullptr) {
        pixel_lab::throwIAE(env, "null array argument");
        return -1;
    }
    if (width <= 0 || height <= 0 || pointCount < 0 ||
        env->GetArrayLength(points) != pointCount * 2 ||
        static_cast<jlong>(env->GetArrayLength(pixels)) != static_cast<jlong>(width) * height ||
        static_cast<jlong>(env->GetArrayLength(out)) != static_cast<jlong>(width) * height) {
        pixel_lab::throwIAE(env, "batch write argument shape mismatch");
        return -1;
    }
    // Read-only inputs: the result goes to a local scratch buffer (a
    // direct-pin input view is the caller's own memory — see the dither
    // note) and is copied to out.
    pixel_lab::ScopedIntArray in(env, pixels, /*copyBack=*/false);
    pixel_lab::ScopedIntArray pts(env, points, /*copyBack=*/false);
    if (!in.valid() || !pts.valid()) {
        return -1;
    }
    std::vector<uint32_t> scratch(static_cast<size_t>(width) * height);
    const int64_t written = pixel_lab::setPixelsBatch(
        reinterpret_cast<const uint32_t*>(in.get()), width, height, pts.get(),
        static_cast<size_t>(pointCount), static_cast<uint32_t>(argb),
        scratch.data());
    env->SetIntArrayRegion(out, 0, env->GetArrayLength(out),
                           reinterpret_cast<const jint*>(scratch.data()));
    return static_cast<jint>(written);
    PIXEL_LAB_JNI_CATCH(env, -1)
}

JNIEXPORT jint JNICALL
Java_com_pixellab_core_nativelib_NativePixelOps_nativeFloodFill(
        JNIEnv* env, jobject, jintArray pixels, jint width, jint height, jint x,
        jint y, jint replacement, jint tolerance, jintArray out) {
    PIXEL_LAB_JNI_TRY
    if (pixels == nullptr || out == nullptr) {
        pixel_lab::throwIAE(env, "null array argument");
        return -1;
    }
    if (width <= 0 || height <= 0 ||
        static_cast<jlong>(env->GetArrayLength(pixels)) != static_cast<jlong>(width) * height ||
        static_cast<jlong>(env->GetArrayLength(out)) != static_cast<jlong>(width) * height) {
        pixel_lab::throwIAE(env, "flood fill argument shape mismatch");
        return -1;
    }
    // Read-only input: the filled result goes to local scratch (direct-pin
    // views alias the caller's immutable array) and is copied to out.
    pixel_lab::ScopedIntArray in(env, pixels, /*copyBack=*/false);
    if (!in.valid()) {
        return -1;
    }
    std::vector<uint32_t> scratch(static_cast<size_t>(width) * height);
    const int64_t changed = pixel_lab::floodFill(
        reinterpret_cast<const uint32_t*>(in.get()), width, height, x, y,
        static_cast<uint32_t>(replacement), tolerance,
        scratch.data());
    env->SetIntArrayRegion(out, 0, env->GetArrayLength(out),
                           reinterpret_cast<const jint*>(scratch.data()));
    return static_cast<jint>(changed);
    PIXEL_LAB_JNI_CATCH(env, -1)
}

JNIEXPORT jint JNICALL
Java_com_pixellab_core_nativelib_NativePixelOps_nativeCompositeLayers(
        JNIEnv* env, jobject, jobjectArray layers, jintArray layerSizes,
        jintArray widths, jintArray heights, jfloatArray opacities, jint layerCount,
        jintArray out) {
    PIXEL_LAB_JNI_TRY
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
    // RAII pin: every exit path (including C++ exception unwind) releases.
    pixel_lab::PinnedFrameArrays pinned(env, layers);
    if (!pinned.valid()) {
        return -1;
    }
    pixel_lab::ScopedIntArray w(env, widths, /*copyBack=*/false);
    pixel_lab::ScopedIntArray h(env, heights, /*copyBack=*/false);
    pixel_lab::ScopedFloatArray op(env, opacities);
    if (!w.valid() || !h.valid() || !op.valid()) {
        return -1;
    }
    std::vector<const uint32_t*> layerPtrs(frameCount);
    std::vector<int> layerW(frameCount), layerH(frameCount);
    std::vector<float> layerOp(frameCount);
    for (jsize i = 0; i < frameCount; ++i) {
        layerW[i] = w.get()[i];
        layerH[i] = h.get()[i];
        layerOp[i] = op.get()[i];
        layerPtrs[i] = reinterpret_cast<const uint32_t*>(pinned.buffers()[static_cast<size_t>(i)]);
        // Validate the PINNED length against the claimed geometry BEFORE
        // compositing: layerSizes was never consulted, so a short layer
        // array (e.g. IntArray(5) with width=64) passed every earlier
        // check and compositeLayers read out of bounds.
        const jlong expected = static_cast<jlong>(layerW[i]) * layerH[i];
        if (layerW[i] <= 0 || layerH[i] <= 0 ||
            env->GetArrayLength(pinned.refs()[static_cast<size_t>(i)]) != static_cast<jsize>(expected)) {
            pixel_lab::throwIAE(env, "composite layer " + std::to_string(i) +
                                      " buffer length does not match its claimed geometry");
            return -1;
        }
    }
    const size_t pixelCount = static_cast<size_t>(layerW[0]) * layerH[0];
    if (env->GetArrayLength(out) != static_cast<jsize>(pixelCount)) {
        pixel_lab::throwIAE(env, "composite output size mismatch");
        return -1;
    }
    std::vector<uint32_t> composite(pixelCount);
    const int ok = pixel_lab::compositeLayers(layerPtrs, layerW, layerH, layerOp, composite.data());
    if (ok != 0) {
        pixel_lab::throwIAE(env, "native composite failed");
        return -1;
    }
    env->SetIntArrayRegion(out, 0, static_cast<jsize>(pixelCount),
                           reinterpret_cast<const jint*>(composite.data()));
    return 0;
    PIXEL_LAB_JNI_CATCH(env, -1)
}

// ---- NativeGifEncoder --------------------------------------------------------

JNIEXPORT jbyteArray JNICALL
Java_com_pixellab_core_nativelib_NativeGifEncoder_nativeEncodeGif(
        JNIEnv* env, jobject, jint width, jint height, jobjectArray frames,
        jintArray frameSizes, jint frameCount, jintArray delaysMs, jint loopCount,
        jint quantAlgorithmId, jint ditherId) {
    PIXEL_LAB_JNI_TRY
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
    // RAII pin: every exit path (including C++ exception unwind) releases.
    pixel_lab::PinnedFrameArrays pinned(env, frames);
    if (!pinned.valid()) {
        return nullptr;
    }
    pixel_lab::ScopedIntArray sizes(env, frameSizes, /*copyBack=*/false);
    pixel_lab::ScopedIntArray delays(env, delaysMs, /*copyBack=*/false);
    if (!sizes.valid() || !delays.valid()) {
        return nullptr;
    }
    std::vector<const uint32_t*> framePtrs(frameCount);
    std::vector<size_t> frameLen(frameCount);
    std::vector<int> delayList(frameCount);
    for (jsize i = 0; i < frameCount; ++i) {
        framePtrs[i] = reinterpret_cast<const uint32_t*>(pinned.buffers()[static_cast<size_t>(i)]);
        delayList[i] = delays.get()[i];
        // Use the ACTUAL pinned length, never the caller's claim: a short
        // frame array with an inflated frameSizes entry read OOB in
        // encodeGif. GIF frames are full-canvas by construction.
        const jlong actual = env->GetArrayLength(pinned.refs()[static_cast<size_t>(i)]);
        if (actual != static_cast<jlong>(width) * height) {
            pixel_lab::throwIAE(env, "gif frame length mismatch");
            return nullptr;
        }
        frameLen[i] = static_cast<size_t>(actual);
    }
    std::vector<uint8_t> gif;
    const bool ok = pixel_lab::encodeGif(width, height, framePtrs, frameLen, delayList,
                                         loopCount, quantAlgorithmId, ditherId, gif);
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
    PIXEL_LAB_JNI_CATCH(env, nullptr)
}

} // extern "C"
