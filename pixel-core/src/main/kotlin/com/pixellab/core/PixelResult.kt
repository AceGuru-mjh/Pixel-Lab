package com.pixellab.core

/** Machine-readable failure kinds surfaced through [PixelResult]. */
enum class PixelErrorKind {
    /** Caller passed malformed arguments (message has details). */
    INVALID_INPUT,
    /** Requested operation is not supported in this build. */
    UNSUPPORTED,
    /** Image conversion failed mid-pipeline. */
    CONVERT_FAILED,
    /** Encoding/export failed. */
    EXPORT_FAILED,
    /** Native library unavailable; a pure-Kotlin fallback exists or retried later. */
    NATIVE_UNAVAILABLE,
    /** Requested entity (template, palette, frame...) does not exist. */
    NOT_FOUND,
}

/** The failed branch of [PixelResult]. */
data class PixelFailure(
    val kind: PixelErrorKind,
    val message: String,
    val cause: Throwable? = null,
)

/**
 * Result envelope for library boundary calls. Engine-level APIs throw
 * [IllegalArgumentException] directly (fail-fast for programmer errors), while
 * facade-level operations (conversion, export, MCP tool calls) return results
 * so hosts can branch without exceptions.
 */
sealed class PixelResult<out T> {

    /** Successful outcome carrying [value]. */
    data class Ok<out T>(val value: T) : PixelResult<T>()

    /** Failed outcome carrying structured [failure]. */
    data class Err(val failure: PixelFailure) : PixelResult<Nothing>()

    val isOk: Boolean get() = this is Ok
    val isError: Boolean get() = this is Err

    /** Unwraps the value or throws [IllegalStateException]. */
    fun getOrThrow(): T = when (this) {
        is Ok -> value
        is Err -> throw IllegalStateException("${failure.kind}: ${failure.message}", failure.cause)
    }

    /** Value or [default] when failed. */
    fun getOrDefault(default: @UnsafeVariance T): T = when (this) {
        is Ok -> value
        is Err -> default
    }

    /** Maps the success value, passing failures through. */
    fun <R> map(transform: (T) -> R): PixelResult<R> = when (this) {
        is Ok -> Ok(transform(value))
        is Err -> this
    }

    companion object {
        fun <T> ok(value: T): PixelResult<T> = Ok(value)
        fun err(kind: PixelErrorKind, message: String, cause: Throwable? = null): PixelResult<Nothing> =
            Err(PixelFailure(kind, message, cause))
    }
}
