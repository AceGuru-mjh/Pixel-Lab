// Scoped JNI array access helpers: RAII pinning with exception safety.
// JNI critical regions are deliberately avoided (GetPrimitiveArrayCritical
// disables GC for its duration); plain Get<type>ArrayElements is fine for the
// millisecond-scale bursts Pixel Lab performs.
#ifndef PIXEL_LAB_JNI_COMMON_H
#define PIXEL_LAB_JNI_COMMON_H

#include <jni.h>
#include <cstdint>
#include <string>
#include <vector>

namespace pixel_lab {

// Pins a jintArray and exposes it as a mutable int32_t view. Copies back on
// destruction unless released early.
class ScopedIntArray {
public:
    ScopedIntArray(JNIEnv* env, jintArray array)
        : env_(env), array_(array), size_(array ? env->GetArrayLength(array) : 0) {
        elements_ = array ? env->GetIntArrayElements(array, nullptr) : nullptr;
        if (array != nullptr && elements_ == nullptr) {
            failed_ = true;
        }
    }

    ~ScopedIntArray() {
        if (elements_ != nullptr) {
            env_->ReleaseIntArrayElements(array_, elements_, 0);
        }
    }

    ScopedIntArray(const ScopedIntArray&) = delete;
    ScopedIntArray& operator=(const ScopedIntArray&) = delete;

    ScopedIntArray(ScopedIntArray&& other) noexcept
        : env_(other.env_), array_(other.array_), elements_(other.elements_),
          size_(other.size_), failed_(other.failed_) {
        other.elements_ = nullptr;
        other.array_ = nullptr;
    }

    bool valid() const { return !failed_ && elements_ != nullptr; }
    int32_t* get() const { return elements_; }
    jsize size() const { return size_; }

private:
    JNIEnv* env_;
    jintArray array_ = nullptr;
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
inline bool pinFrameArrays(JNIEnv* env, jobjectArray frames, std::vector<int32_t*>& out,
                           std::vector<jintArray>& outRefs) {
    const jsize count = env->GetArrayLength(frames);
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

} // namespace pixel_lab

#endif // PIXEL_LAB_JNI_COMMON_H
