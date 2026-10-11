#pragma once

#include <mutex>

namespace neuralsound::audio::engine {

class RenderLifecycleCoordinator {
public:
    using Guard = std::unique_lock<std::mutex>;

    Guard acquireForPlay() {
        return Guard(mutex_);
    }

    Guard acquireForPause() {
        return Guard(mutex_);
    }

    Guard acquireForSeek() {
        return Guard(mutex_);
    }

    Guard acquireForRestart() {
        return Guard(mutex_);
    }

    Guard acquireForResync() {
        return Guard(mutex_);
    }

    Guard tryAcquireForPlay() {
        return Guard(mutex_, std::try_to_lock);
    }

    Guard tryAcquireForPause() {
        return Guard(mutex_, std::try_to_lock);
    }

    Guard tryAcquireForSeek() {
        return Guard(mutex_, std::try_to_lock);
    }

    Guard tryAcquireForRestart() {
        return Guard(mutex_, std::try_to_lock);
    }

    template <
        typename PlaybackIntentActive,
        typename StopRendering,
        typename PublishTrack,
        typename ResumeRendering
    >
    bool append(
        PlaybackIntentActive playbackIntentActive,
        StopRendering stopRendering,
        PublishTrack publishTrack,
        ResumeRendering resumeRendering
    ) {
        Guard guard(mutex_);
        const bool shouldResumeRendering = playbackIntentActive();
        if (shouldResumeRendering) stopRendering();
        const bool appended = publishTrack();
        if (shouldResumeRendering) resumeRendering();
        return appended;
    }

private:
    std::mutex mutex_;
};

} // namespace neuralsound::audio::engine
