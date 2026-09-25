package com.pixellab.core.nativelib

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Loader and availability oracle for the `pixel_lab_native` shared library.
 *
 * Loading is attempted at most once per process; failures are cached so hosts
 * never pay repeated link errors. Every native facade checks [available]
 * before dispatching and falls back to the pure-Kotlin implementation
 * otherwise.
 */
object NativeLib {

    private const val LIB_NAME = "pixel_lab_native"

    private val loaded = AtomicBoolean(false)
    private val attempted = AtomicBoolean(false)

    /** Native build identifier reported by the library, or null when absent. */
    val version: String? by lazy {
        if (load()) {
            runCatching { nativeVersion() }.getOrNull()
        } else {
            null
        }
    }

    /** True once [load] succeeded in this process. */
    val available: Boolean get() = loaded.get()

    /**
     * Attempts to link the native library. Safe to call repeatedly; only the
     * first call performs work. Returns true when the library is available.
     */
    fun load(): Boolean {
        if (loaded.get()) return true
        if (!attempted.compareAndSet(false, true)) return loaded.get()
        return try {
            System.loadLibrary(LIB_NAME)
            loaded.set(true)
            true
        } catch (t: UnsatisfiedLinkError) {
            false
        } catch (t: SecurityException) {
            false
        }
    }

    private external fun nativeVersion(): String
}
