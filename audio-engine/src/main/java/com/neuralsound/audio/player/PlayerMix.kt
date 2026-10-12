package com.neuralsound.audio.player

import com.neuralsound.audio.AudioError
import com.neuralsound.audio.AudioResult
import com.neuralsound.audio.TrackId
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** One track's mix settings. Mute is independent of volume; solo makes only soloed (and unmuted) tracks audible. */
data class TrackMix(
    val volume: Float = 1f,
    val muted: Boolean = false,
    val solo: Boolean = false,
    val balance: ChannelGain = ChannelGain.Center,
    val offsetMs: Long = 0L,
    val boostDb: Int = 0,
) {
    init {
        require(volume.isFinite()) { "Volume must be finite" }
    }

    /** The per-track settings part of this mix (solo is a mix-level concept and is not included). */
    fun toTrackSettings(): TrackSettings = TrackSettings(
        volume = volume,
        muted = muted,
        balance = balance,
        offsetMs = offsetMs,
        boostDb = boostDb,
    )

    companion object {
        /** Builds a [TrackMix] from existing [TrackSettings]. */
        @JvmStatic
        @JvmOverloads
        fun from(settings: TrackSettings, solo: Boolean = false): TrackMix = TrackMix(
            volume = settings.volume,
            muted = settings.muted,
            solo = solo,
            balance = settings.balance,
            offsetMs = settings.offsetMs,
            boostDb = settings.boostDb,
        )
    }
}

/** A player's whole mix, readable and applicable in one call, and storable as text (see [toText]). */
data class PlayerMix(
    val tracks: Map<TrackId, TrackMix> = emptyMap(),
    val pitchTempo: PitchTempo = PitchTempo(),
    val range: PlaybackRange? = null,
    val loop: Boolean = false,
) {
    /** Versioned JSON text for app persistence (e.g. a Room column). */
    fun toText(): String {
        val trackJson = JSONObject()
        for ((id, mix) in tracks) {
            trackJson.put(
                id.value,
                JSONObject()
                    .put("volume", mix.volume.toDouble())
                    .put("muted", mix.muted)
                    .put("solo", mix.solo)
                    .put("left", mix.balance.left.toDouble())
                    .put("right", mix.balance.right.toDouble())
                    .put("offsetMs", mix.offsetMs)
                    .put("boostDb", mix.boostDb),
            )
        }
        val root = JSONObject()
            .put("v", FORMAT_VERSION)
            .put("tracks", trackJson)
            .put("pitch", pitchTempo.semitones)
            .put("tempo", pitchTempo.tempo.toDouble())
        range?.let { root.put("range", JSONArray().put(it.startMs).put(it.endMs)) }
        root.put("loop", loop)
        return root.toString()
    }

    companion object {
        const val FORMAT_VERSION = 1

        /** Parses [toText] output from this or other SDK versions; unknown fields are ignored, missing ones default. */
        @JvmStatic
        fun fromText(text: String): AudioResult<PlayerMix> = try {
            AudioResult.Ok(parse(text))
        } catch (_: JSONException) {
            invalid("malformed mix text")
        } catch (_: IllegalArgumentException) {
            invalid("invalid mix value")
        } catch (_: MixFormatException) {
            invalid("unexpected field type")
        }

        private class MixFormatException : RuntimeException()

        private fun invalid(reason: String): AudioResult<Nothing> =
            AudioResult.Err(AudioError.InvalidConfiguration(reason))

        private fun parse(text: String): PlayerMix {
            val root = JSONObject(text)
            root.number("v") // must be numeric if present; any version is accepted
            val tracks = LinkedHashMap<TrackId, TrackMix>()
            val trackJson = root.obj("tracks")
            if (trackJson != null) {
                val keys = trackJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val t = trackJson.obj(key) ?: throw MixFormatException()
                    tracks[TrackId(key)] = TrackMix(
                        volume = t.number("volume")?.toFloat() ?: 1f,
                        muted = t.bool("muted") ?: false,
                        solo = t.bool("solo") ?: false,
                        balance = ChannelGain(
                            left = t.number("left")?.toFloat() ?: 1f,
                            right = t.number("right")?.toFloat() ?: 1f,
                        ),
                        offsetMs = t.integer("offsetMs") ?: 0L,
                        boostDb = t.integer("boostDb")?.let {
                            if (it !in Int.MIN_VALUE..Int.MAX_VALUE) throw MixFormatException()
                            it.toInt()
                        } ?: 0,
                    )
                }
            }
            val semitones = root.integer("pitch")?.let {
                if (it !in Int.MIN_VALUE..Int.MAX_VALUE) throw MixFormatException()
                it.toInt()
            } ?: 0
            val pitchTempo = PitchTempo(semitones, root.number("tempo")?.toFloat() ?: 1f)
            val range = if (root.isNull("range")) {
                null
            } else {
                val arr = root.opt("range") as? JSONArray ?: throw MixFormatException()
                if (arr.length() != 2) throw MixFormatException()
                PlaybackRange(arr.longAt(0), arr.longAt(1))
            }
            return PlayerMix(tracks, pitchTempo, range, root.bool("loop") ?: false)
        }

        private fun JSONObject.obj(key: String): JSONObject? {
            if (isNull(key)) return null
            return opt(key) as? JSONObject ?: throw MixFormatException()
        }

        private fun JSONObject.number(key: String): Double? {
            if (isNull(key)) return null
            val n = opt(key) as? Number ?: throw MixFormatException()
            return n.toDouble()
        }

        private fun JSONObject.integer(key: String): Long? {
            if (isNull(key)) return null
            return (opt(key) as? Number ?: throw MixFormatException()).toIntegral()
        }

        private fun JSONObject.bool(key: String): Boolean? {
            if (isNull(key)) return null
            return opt(key) as? Boolean ?: throw MixFormatException()
        }

        private fun JSONArray.longAt(index: Int): Long =
            (opt(index) as? Number ?: throw MixFormatException()).toIntegral()

        private fun Number.toIntegral(): Long {
            if (this is Long || this is Int) return toLong()
            val d = toDouble()
            if (!d.isFinite() || d != Math.floor(d) || d > Long.MAX_VALUE.toDouble() || d < Long.MIN_VALUE.toDouble()) {
                throw MixFormatException()
            }
            return d.toLong()
        }
    }
}

/** Result of applying a mix: tracks present now, and tracks whose settings wait for [Player.appendTrack]. */
data class MixApplyResult(val applied: Set<TrackId>, val pending: Set<TrackId>)
