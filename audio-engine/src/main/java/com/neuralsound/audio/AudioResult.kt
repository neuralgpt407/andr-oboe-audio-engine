package com.neuralsound.audio

/**
 * Outcome of an engine call that can fail.
 *
 * Every session reports failures through the single [AudioError] hierarchy, so callers handle
 * one error model for players, recorders and waveform analysis.
 */
sealed interface AudioResult<out T> {
    data class Ok<out T>(val value: T) : AudioResult<T>

    data class Err(val error: AudioError) : AudioResult<Nothing>

    val isOk: Boolean
        get() = this is Ok

    fun getOrNull(): T? = (this as? Ok)?.value

    fun errorOrNull(): AudioError? = (this as? Err)?.error

    companion object {
        /** Shared success value for calls that return nothing. */
        @JvmField
        val Done: AudioResult<Unit> = Ok(Unit)
    }
}

inline fun <T> AudioResult<T>.onOk(action: (T) -> Unit): AudioResult<T> {
    if (this is AudioResult.Ok) action(value)
    return this
}

inline fun <T> AudioResult<T>.onErr(action: (AudioError) -> Unit): AudioResult<T> {
    if (this is AudioResult.Err) action(error)
    return this
}
