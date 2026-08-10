package com.theveloper.pixelplay.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class LyricsMetadataMatcherTest {

    @Test
    fun `accepts exact title compatible artists and close duration`() {
        val score = LyricsMetadataMatcher.strictScore(
            originalTitle = "Song Name",
            originalArtist = "Artist A",
            originalDurationMs = 200_000,
            candidateTitle = "Song Name (Album Version)",
            candidateArtist = "Artist A / Artist B",
            candidateDurationMs = 204_000
        )

        assertThat(score).isNotNull()
    }

    @Test
    fun `rejects different title artist duration and excluded remix`() {
        assertThat(
            LyricsMetadataMatcher.strictScore("Song", "Artist", 200_000, "Other", "Artist", 200_000)
        ).isNull()
        assertThat(
            LyricsMetadataMatcher.strictScore("Song", "Artist", 200_000, "Song", "Other", 200_000)
        ).isNull()
        assertThat(
            LyricsMetadataMatcher.strictScore("Song", "Artist", 200_000, "Song", "Artist", 240_000)
        ).isNull()
        assertThat(
            LyricsMetadataMatcher.strictScore("Song", "Artist", 200_000, "Song Remix", "Artist", 200_000)
        ).isNull()
    }
}
