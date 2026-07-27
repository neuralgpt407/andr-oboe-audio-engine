package com.neuralsound.audio.consumer;

import static org.junit.Assert.assertEquals;

import com.neuralsound.audio.AudioEngine;
import com.neuralsound.audio.FramePreciseRecorderSession;
import com.neuralsound.audio.MixerSession;
import com.neuralsound.audio.MixerStatus;
import com.neuralsound.audio.NativeAudioWaveformAnalyzer;
import com.neuralsound.audio.RecorderStatus;
import com.neuralsound.audio.media3.Media3AudioEngine;
import com.neuralsound.audio.media3.Media3MixerSession;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public final class PublishedApiSmokeTest {
    @Test
    public void media3PublicationExposesRunnableCoreEntryPointsTransitively() {
        AudioEngine audioEngine = new AudioEngine(RuntimeEnvironment.getApplication());

        MixerSession mixer = audioEngine.createMixerSession();
        assertEquals(MixerStatus.IDLE, mixer.getState().getValue().getStatus());
        mixer.close();

        FramePreciseRecorderSession recorder =
                audioEngine.createFramePreciseRecorderSession();
        assertEquals(
                recorder.getStatus().getValue(),
                recorder.getState().getValue().getStatus());
        recorder.close();
        assertEquals(RecorderStatus.RELEASED, recorder.getState().getValue().getStatus());

        NativeAudioWaveformAnalyzer waveformAnalyzer =
                new NativeAudioWaveformAnalyzer();
        assertEquals(3, NativeAudioWaveformAnalyzer.ALGORITHM_VERSION);
        assertEquals(1024, NativeAudioWaveformAnalyzer.MAX_OUTPUT_SAMPLES);
        waveformAnalyzer.cancel();

        Media3MixerSession media3 =
                new Media3AudioEngine(RuntimeEnvironment.getApplication()).createSession();
        assertEquals(MixerStatus.IDLE, media3.getMixerState().getValue().getStatus());
        long revision = media3.getVideoState().getValue().getRevision();
        media3.notifyVideoSurfaceRecreated();
        assertEquals(revision + 1L, media3.getVideoState().getValue().getRevision());
        media3.close();
    }
}
