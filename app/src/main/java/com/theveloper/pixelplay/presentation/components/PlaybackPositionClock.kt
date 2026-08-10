package com.theveloper.pixelplay.presentation.components

import kotlin.math.roundToLong

/** Extrapolates a low-frequency player position from a monotonic time baseline. */
internal class PlaybackPositionClock {
    private var baselinePositionMs = 0L
    private var baselineRealtimeMs = 0L
    private var durationMs = Long.MAX_VALUE
    private var speed = 1f
    private var advancing = false

    fun synchronize(
        positionMs: Long,
        realtimeMs: Long,
        isPlaying: Boolean,
        isBuffering: Boolean,
        playbackSpeed: Float,
        totalDurationMs: Long
    ) {
        baselinePositionMs = positionMs.coerceAtLeast(0L)
        baselineRealtimeMs = realtimeMs
        durationMs = totalDurationMs.takeIf { it > 0L } ?: Long.MAX_VALUE
        speed = playbackSpeed.takeIf { it.isFinite() && it > 0f } ?: 1f
        advancing = isPlaying && !isBuffering
    }

    fun positionAt(realtimeMs: Long): Long {
        if (!advancing) return baselinePositionMs.coerceAtMost(durationMs)
        val elapsedMs = (realtimeMs - baselineRealtimeMs).coerceAtLeast(0L)
        return (baselinePositionMs + elapsedMs * speed)
            .roundToLong()
            .coerceIn(0L, durationMs)
    }
}
