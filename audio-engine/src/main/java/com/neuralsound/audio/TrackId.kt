package com.neuralsound.audio

@JvmInline
value class TrackId(val value: String) {
    init {
        require(value.isNotBlank()) { "TrackId must not be blank" }
        require('\u0000' !in value) { "TrackId must not contain a null character" }
    }

    override fun toString(): String = value
}
