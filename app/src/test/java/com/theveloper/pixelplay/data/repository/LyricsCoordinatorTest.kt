package com.theveloper.pixelplay.data.repository

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.model.Lyrics
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LyricsCoordinatorTest {
    @Test
    fun storedLyricsPreventLocalAndRemoteRequests() = runTest {
        val coordinator = LyricsCoordinator()
        var requests = 0

        val result = coordinator.resolve(
            cacheKey = "library:1",
            forceRefresh = false,
            loadStored = { Lyrics(plain = listOf("stored")) },
            attempts = listOf(LyricsLoadAttempt("remote") {
                requests++
                Lyrics(plain = listOf("remote"))
            })
        )

        assertThat(result?.plain).containsExactly("stored")
        assertThat(requests).isEqualTo(0)
    }

    @Test
    fun forceRefreshSkipsMemoryAndStoredLyrics() = runTest {
        val coordinator = LyricsCoordinator()
        coordinator.putMemory("library:1", Lyrics(plain = listOf("memory")))
        var storedReads = 0

        val result = coordinator.resolve(
            cacheKey = "library:1",
            forceRefresh = true,
            loadStored = {
                storedReads++
                Lyrics(plain = listOf("stored"))
            },
            attempts = listOf(LyricsLoadAttempt("remote") { Lyrics(plain = listOf("fresh")) })
        )

        assertThat(result?.plain).containsExactly("fresh")
        assertThat(storedReads).isEqualTo(0)
    }

    @Test
    fun highPriorityHitCancelsUnneededLowerPriorityRequest() = runTest {
        val coordinator = LyricsCoordinator()
        val lowerStarted = CompletableDeferred<Unit>()
        val lowerCancelled = CompletableDeferred<Unit>()

        val selected = coordinator.selectFirstByPriority(
            attempts = listOf(
                PrioritizedLyricsAttempt("high", 10) { "high" },
                PrioritizedLyricsAttempt("low", 20) {
                    lowerStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        lowerCancelled.complete(Unit)
                    }
                }
            ),
            isUsable = String::isNotBlank
        )
        advanceUntilIdle()

        assertThat(selected).isEqualTo("high")
        assertThat(lowerStarted.isCompleted).isTrue()
        assertThat(lowerCancelled.isCompleted).isTrue()
    }
}
