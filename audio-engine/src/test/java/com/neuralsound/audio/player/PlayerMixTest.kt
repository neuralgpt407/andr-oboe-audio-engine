package com.neuralsound.audio.player

import com.neuralsound.audio.AudioError
import com.neuralsound.audio.AudioResult
import com.neuralsound.audio.TrackId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlayerMixTest {
    private fun sample() = PlayerMix(
        tracks = linkedMapOf(
            TrackId("vocals") to TrackMix(0.6f, muted = true, solo = false, balance = ChannelGain.balance(-0.4f), offsetMs = -250L, boostDb = 6),
            TrackId("drums") to TrackMix(0.35f, solo = true, offsetMs = 120L, boostDb = 18),
            TrackId("bass") to TrackMix(),
        ),
        pitchTempo = PitchTempo(-3, 1.25f),
        range = PlaybackRange(10_000, 40_000),
        loop = true,
    )

    private fun assertInvalid(text: String) {
        val r = PlayerMix.fromText(text)
        assertTrue("expected Err for $text", r is AudioResult.Err)
        assertTrue((r as AudioResult.Err).error is AudioError.InvalidConfiguration)
    }

    @Test fun roundTripsEveryField() {
        val mix = sample()
        assertEquals(AudioResult.Ok(mix), PlayerMix.fromText(mix.toText()))
    }

    @Test fun preservesTrackOrder() {
        val parsed = (PlayerMix.fromText(sample().toText()) as AudioResult.Ok).value
        assertEquals(listOf("vocals", "drums", "bass"), parsed.tracks.keys.map { it.value })
    }

    @Test fun defaultMixRoundTripsAndEmptyObjectIsDefault() {
        assertEquals(AudioResult.Ok(PlayerMix()), PlayerMix.fromText(PlayerMix().toText()))
        assertEquals(AudioResult.Ok(PlayerMix()), PlayerMix.fromText("{}"))
    }

    @Test fun unknownFieldsIgnoredAndMissingDefault() {
        val r = PlayerMix.fromText("""{"v":1,"extra":[1],"tracks":{"a":{"volume":0.5,"future":true}},"loop":true}""")
        assertEquals(AudioResult.Ok(PlayerMix(mapOf(TrackId("a") to TrackMix(volume = 0.5f)), loop = true)), r)
    }

    @Test fun futureVersionWithKnownFieldsParses() {
        val r = PlayerMix.fromText("""{"v":7,"tracks":{"a":{"muted":true}},"pitch":2,"tempo":1.5}""")
        val mix = (r as AudioResult.Ok).value
        assertTrue(mix.tracks.getValue(TrackId("a")).muted)
        assertEquals(PitchTempo(2, 1.5f), mix.pitchTempo)
    }

    @Test fun malformedAndWrongTypesAreErrors() {
        assertInvalid("not json")
        assertInvalid("")
        assertInvalid("[]")
        assertInvalid("""{"v":"1"}""")
        assertInvalid("""{"tracks":[]}""")
        assertInvalid("""{"tracks":{"a":{"volume":"loud"}}}""")
        assertInvalid("""{"tracks":{"a":{"muted":"yes"}}}""")
        assertInvalid("""{"tracks":{"a":5}}""")
        assertInvalid("""{"tracks":{"a":{"offsetMs":1.5}}}""")
        assertInvalid("""{"loop":1}""")
        assertInvalid("""{"pitch":"x"}""")
        assertInvalid("""{"tracks":{" ":{}}}""")
    }

    @Test fun invalidRangeIsError() {
        assertInvalid("""{"range":[5,5]}""")
        assertInvalid("""{"range":[9,3]}""")
        assertInvalid("""{"range":[-1,3]}""")
        assertInvalid("""{"range":[1]}""")
        assertInvalid("""{"range":"a"}""")
    }

    @Test fun invalidValuesAreErrors() {
        assertInvalid("""{"tracks":{"a":{"left":2.5}}}""")
        assertInvalid("""{"tracks":{"a":{"right":-0.1}}}""")
        assertInvalid("""{"tempo":0}""")
        assertInvalid("""{"tempo":-1}""")
    }

    @Test fun errorReasonHasNoUserData() {
        val r = PlayerMix.fromText("""{"tracks":{"secret-track":{"volume":"x"}}}""") as AudioResult.Err
        assertFalse((r.error as AudioError.InvalidConfiguration).reason.contains("secret"))
    }

    @Test fun trackMixSettingsRoundTrip() {
        val settings = TrackSettings(0.7f, true, ChannelGain.balance(0.5f), -300L, 9)
        val mix = TrackMix.from(settings, solo = true)
        assertTrue(mix.solo)
        assertEquals(settings, mix.toTrackSettings())
        assertFalse(TrackMix.from(settings).solo)
    }
}
