// Scoped JNI array access helpers: RAII pinning with exception safety.
// JNI critical regions are deliberately avoided (GetPrimitiveArrayCritical
// disables GC for its duration); plain Get<type>ArrayElements is fine for the
// millisecond-scale bursts Pixel Lab performs.
#ifndef PIXEL_LAB_JNI_COMMON_H
#define PIXEL_LAB_JNI_COMMON_H

#include <jni.h>
#include <cstdint>
#include <exception>
#include <new>
#include <string>
#include <vector>

namespace pixel_lab {

// Pins a jintArray and exposes it as a mutable int32_t view. Copies back on
// destruction (mode 0) unless constructed with copyBack=false, which releases
// with JNI_ABORT — the correct mode for input-only buffers whose contents
// were only ever *read*.
//
// IMPORTANT: GetIntArrayElements may legally return a DIRECT pointer
// (isCopy == false) into the caller's array. Writing through such a view
// mutates the caller-side array IMMEDIATELY — release mode is irrelevant.
// Input-pinned views must therefore never be used as output scratch;
// compute into a local std::vector and SetIntArrayRegion instead.
class ScopedIntArray {
public:
    explicit ScopedIntArray(JNIEnv* env, jintArray array, bool copyBack = true)
        : env_(env), array_(array), copyBack_(copyBack),
          size_(array ? env->GetArrayLength(array) : 0) {
        elements_ = array ? env->GetIntArrayElements(array, nullptr) : nullptr;
        if (array != nullptr && elements_ == nullptr) {
            failed_ = true;
        }
    }

    ~ScopedIntArray() {
        if (elements_ != nullptr) {
            env_->ReleaseIntArrayElements(array_, elements_, copyBack_ ? 0 : JNI_ABORT);
        }
    }

    ScopedIntArray(const ScopedIntArray&) = delete;
    ScopedIntArray& operator=(const ScopedIntArray&) = delete;

    ScopedIntArray(ScopedIntArray&& other) noexcept
        : env_(other.env_), array_(other.array_), copyBack_(other.copyBack_),
          elements_(other.elements_), size_(other.size_), failed_(other.failed_) {
        other.elements_ = nullptr;
        other.array_ = nullptr;
    }

    bool valid() const { return !failed_ && elements_ != nullptr; }
    int32_t* get() const { return elements_; }
    jsize size() const { return size_; }

private:
    JNIEnv* env_;
    jintArray array_ = nullptr;
    bool copyBack_ = true;
    int32_t* elements_ = nullptr;
    jsize size_ = 0;
    bool failed_ = false;
};

// Read-only pinned jfloatArray view.
class ScopedFloatArray {
public:
    ScopedFloatArray(JNIEnv* env, jfloatArray array)
        : env_(env), array_(array), size_(array ? env->GetArrayLength(array) : 0) {
        elements_ = array ? env->GetFloatArrayElements(array, nullptr) : nullptr;
        if (array != nullptr && elements_ == nullptr) {
            failed_ = true;
        }
    }

    ~ScopedFloatArray() {
        if (elements_ != nullptr) {
            env_->ReleaseFloatArrayElements(array_, elements_, JNI_ABORT);
        }
    }

    ScopedFloatArray(const ScopedFloatArray&) = delete;
    ScopedFloatArray& operator=(const ScopedFloatArray&) = delete;

    bool valid() const { return !failed_ && elements_ != nullptr; }
    const float* get() const { return elements_; }
    jsize size() const { return size_; }

private:
    JNIEnv* env_;
    jfloatArray array_ = nullptr;
    float* elements_ = nullptr;
    jsize size_ = 0;
    bool failed_ = false;
};

// Extracts the pixel buffers from a jobjectArray of jintArray frames.
// Returns false (with a pending IllegalArgumentException) on malformed input.
// Grows the local-reference capacity first: one object ref per frame plus
// the array refs would otherwise overflow the default local table for
// multi-thousand-frame GIF exports (a bounded table aborts the process).
inline bool pinFrameArrays(JNIEnv* env, jobjectArray frames, std::vector<int32_t*>& out,
                           std::vector<jintArray>& outRefs) {
    const jsize count = env->GetArrayLength(frames);
    if (count < 0) {
        return false;
    }
    if (env->EnsureLocalCapacity(count + 16) != JNI_OK) {
        // Pending OutOfMemoryError propagates to the caller.
        return false;
    }
    out.resize(static_cast<size_t>(count));
    outRefs.resize(static_cast<size_t>(count));
    for (jsize i = 0; i < count; ++i) {
        jintArray frame = static_cast<jintArray>(env->GetObjectArrayElement(frames, i));
        if (frame == nullptr) {
            env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),
                          "frame array contains null element");
            return false;
        }
        outRefs[static_cast<size_t>(i)] = frame;
        out[static_cast<size_t>(i)] = env->GetIntArrayElements(frame, nullptr);
        if (out[static_cast<size_t>(i)] == nullptr) {
            return false;
        }
    }
    return true;
}

inline void releaseFrameArrays(JNIEnv* env, const std::vector<int32_t*>& buffers,
                               const std::vector<jintArray>& refs, int mode) {
    for (size_t i = 0; i < buffers.size(); ++i) {
        if (buffers[i] != nullptr) {
            env->ReleaseIntArrayElements(refs[i], buffers[i], mode);
        }
    }
}

// Throws java.lang.IllegalArgumentException with a formatted message.
inline void throwIAE(JNIEnv* env, const char* message) {
    jclass cls = env->FindClass("java/lang/IllegalArgumentException");
    if (cls != nullptr) {
        env->ThrowNew(cls, message);
    }
}

inline void throwIAE(JNIEnv* env, const std::string& message) {
    throwIAE(env, message.c_str());
}

// Translates a pending C++ exception into a JVM exception. Called from
// JNI_CATCH guards only (inside a catch block). A std::bad_alloc escaping a
// JNI entry point otherwise crosses the language boundary and terminates the
// whole process — the Kotlin-side catch (Exception) fallback never sees it.
// Returns true when a JVM exception is now pending.
inline bool translatePendingCppException(JNIEnv* env) noexcept {
    try {
        throw;  // rethrow the in-flight exception
    } catch (const std::bad_alloc&) {
        jclass cls = env->FindClass("java/lang/OutOfMemoryError");
        if (cls != nullptr) {
            env->ThrowNew(cls, "native allocation failed (out of memory)");
        }
        return true;
    } catch (const std::exception& e) {
        throwIAE(env, std::string("native error: ") + e.what());
        return true;
    } catch (...) {
        jclass cls = env->FindClass("java/lang/IllegalStateException");
        if (cls != nullptr) {
            env->ThrowNew(cls, "unknown native failure");
        }
        return true;
    }
}

// RAII owner for object-array frame pins: releases every successfully pinned
// buffer with JNI_ABORT on scope exit, INCLUDING when a C++ exception unwinds
// through the frame (the manual release calls previously only covered the
// explicit error returns).
class PinnedFrameArrays {
public:
    PinnedFrameArrays(JNIEnv* env, jobjectArray frames) : env_(env) {
        valid_ = pinFrameArrays(env, frames, buffers_, refs_);
    }
    ~PinnedFrameArrays() {
        releaseFrameArrays(env_, buffers_, refs_, JNI_ABORT);
    }
    PinnedFrameArrays(const PinnedFrameArrays&) = delete;
    PinnedFrameArrays& operator=(const PinnedFrameArrays&) = delete;

    bool valid() const { return valid_; }
    const std::vector<int32_t*>& buffers() const { return buffers_; }
    const std::vector<jintArray>& refs() const { return refs_; }

private:
    JNIEnv* env_;
    std::vector<int32_t*> buffers_;
    std::vector<jintArray> refs_;
    bool valid_ = false;
};

} // namespace pixel_lab

#endif // PIXEL_LAB_JNI_COMMON_H
