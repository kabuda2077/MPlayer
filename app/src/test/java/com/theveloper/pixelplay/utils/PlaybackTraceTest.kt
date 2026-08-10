package com.theveloper.pixelplay.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackTraceTest {

    @Test
    fun newRequestId_returnsUniqueNonBlankIds() {
        val first = PlaybackTrace.newRequestId()
        val second = PlaybackTrace.newRequestId()

        assertThat(first).isNotEmpty()
        assertThat(second).isNotEmpty()
        assertThat(first).isNotEqualTo(second)
    }

    @Test
    fun newContext_keepsOperationMetadata() {
        val context = PlaybackTrace.newContext(
            mediaId = "42",
            sourceUri = "netease://42",
            origin = PlaybackTraceOrigin.PRELOAD
        )

        assertThat(context.requestId).isNotEmpty()
        assertThat(context.mediaId).isEqualTo("42")
        assertThat(context.sourceUri).isEqualTo("netease://42")
        assertThat(context.origin).isEqualTo(PlaybackTraceOrigin.PRELOAD)
    }

    @Test
    fun explicitContextCopy_reusesRequestIdAcrossPipelineStages() {
        val playback = PlaybackTrace.newContext(
            mediaId = "trace-song",
            sourceUri = "qqmusic://trace-song",
            origin = PlaybackTraceOrigin.UI
        )
        val lyrics = playback.copy(origin = PlaybackTraceOrigin.LYRICS)

        assertThat(lyrics.requestId).isEqualTo(playback.requestId)
        assertThat(lyrics.origin).isEqualTo(PlaybackTraceOrigin.LYRICS)
    }
}
