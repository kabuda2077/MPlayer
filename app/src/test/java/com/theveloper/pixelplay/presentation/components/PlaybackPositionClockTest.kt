package com.theveloper.pixelplay.presentation.components

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PlaybackPositionClockTest {
    @Test
    fun advancesFromMonotonicBaselineWhilePlaying() {
        val clock = PlaybackPositionClock()
        clock.synchronize(1_000L, 5_000L, true, false, 1f, 10_000L)

        assertThat(clock.positionAt(5_125L)).isEqualTo(1_125L)
    }

    @Test
    fun respectsPlaybackSpeedAndDuration() {
        val clock = PlaybackPositionClock()
        clock.synchronize(9_800L, 1_000L, true, false, 2f, 10_000L)

        assertThat(clock.positionAt(1_500L)).isEqualTo(10_000L)
    }

    @Test
    fun freezesWhilePausedOrBuffering() {
        val paused = PlaybackPositionClock().apply {
            synchronize(2_000L, 1_000L, false, false, 1f, 10_000L)
        }
        val buffering = PlaybackPositionClock().apply {
            synchronize(3_000L, 1_000L, true, true, 1f, 10_000L)
        }

        assertThat(paused.positionAt(9_000L)).isEqualTo(2_000L)
        assertThat(buffering.positionAt(9_000L)).isEqualTo(3_000L)
    }

    @Test
    fun synchronizeResetsAfterSeek() {
        val clock = PlaybackPositionClock()
        clock.synchronize(1_000L, 1_000L, true, false, 1f, 20_000L)
        assertThat(clock.positionAt(1_500L)).isEqualTo(1_500L)

        clock.synchronize(12_000L, 1_500L, true, false, 1f, 20_000L)

        assertThat(clock.positionAt(1_600L)).isEqualTo(12_100L)
    }
}
