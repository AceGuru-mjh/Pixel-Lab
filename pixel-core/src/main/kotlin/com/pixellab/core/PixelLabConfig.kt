package com.pixellab.core

/**
 * Minimal SLF4J-style logging seam. Hosts inject their logger; the library
 * never touches android.util.Log or stdout directly, so pixel-core stays
 * console-quiet inside a host application.
 */
interface PixelLogger {
    fun d(tag: String, message: String)
    fun i(tag: String, message: String)
    fun w(tag: String, message: String)
    fun e(tag: String, message: String, throwable: Throwable? = null)

    companion object {
        /** Logger that discards everything (default). */
        val NOOP: PixelLogger = object : PixelLogger {
            override fun d(tag: String, message: String) {}
            override fun i(tag: String, message: String) {}
            override fun w(tag: String, message: String) {}
            override fun e(tag: String, message: String, throwable: Throwable?) {}
        }
    }
}

/**
 * Immutable library configuration. Create with [PixelLabConfig.Builder] and
 * pass to [PixelLab.create]; one config instance can back many engine
 * instances safely.
 */
class PixelLabConfig private constructor(
    /** Maximum undo snapshots kept per project. Default 100. */
    val maxUndoDepth: Int,
    /** Prefer the native (JNI/C++) implementations when available. Default true. */
    val preferNative: Boolean,
    /** Diagnostics sink. Default discards. */
    val logger: PixelLogger,
) {

    /** The logger actually used: [logger] or the no-op fallback. */
    val effectiveLogger: PixelLogger get() = logger

    class Builder {
        private var maxUndoDepth: Int = 100
        private var preferNative: Boolean = true
        private var logger: PixelLogger = PixelLogger.NOOP

        /** Undo history depth per project; values < 1 are clamped to 1. */
        fun maxUndoDepth(value: Int) = apply { maxUndoDepth = value }

        /** Whether to dispatch hot paths to native code when loaded. */
        fun preferNative(value: Boolean) = apply { preferNative = value }

        /** Install a host logger. */
        fun logger(value: PixelLogger) = apply { logger = value }

        fun build(): PixelLabConfig = PixelLabConfig(
            maxUndoDepth = maxUndoDepth.coerceAtLeast(1),
            preferNative = preferNative,
            logger = logger,
        )
    }

    companion object {
        /** Configuration with all defaults. */
        fun default(): PixelLabConfig = Builder().build()
    }
}
