package com.theveloper.pixelplay.data.service.player

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PlaybackWorkBudgetTest {
    @Test
    fun cacheFillAllowsOnlyOneOwnerUntilReleased() {
        val budget = PlaybackWorkBudget()
        val first = budget.tryAcquire(PlaybackWorkSlot.CACHE_FILL, "first")

        assertThat(first).isNotNull()
        assertThat(budget.tryAcquire(PlaybackWorkSlot.CACHE_FILL, "second")).isNull()

        first?.close()
        assertThat(budget.tryAcquire(PlaybackWorkSlot.CACHE_FILL, "second")).isNotNull()
    }

    @Test
    fun playbackGenerationInvalidatesExistingCacheFillLease() {
        val budget = PlaybackWorkBudget()
        val lease = requireNotNull(budget.tryAcquire(PlaybackWorkSlot.CACHE_FILL, "old-song"))

        assertThat(lease.isValid).isTrue()
        budget.advancePlaybackGeneration()

        assertThat(lease.isValid).isFalse()
        lease.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun newerAuxiliaryPreparationCancelsOlderCallerJob() = runTest {
        val controller = PlaybackPrefetchController(PlaybackWorkBudget())
        val firstStarted = CompletableDeferred<Unit>()
        val firstCancelled = CompletableDeferred<Unit>()

        val first = async {
            try {
                controller.runLatest(PlaybackWorkSlot.AUXILIARY_PLAYER, "old-song") {
                    firstStarted.complete(Unit)
                    awaitCancellation()
                }
            } finally {
                firstCancelled.complete(Unit)
            }
        }
        firstStarted.await()

        val second = async {
            controller.runLatest(PlaybackWorkSlot.AUXILIARY_PLAYER, "new-song") { "prepared" }
        }
        advanceUntilIdle()

        assertThat(first.isCancelled).isTrue()
        assertThat(firstCancelled.isCompleted).isTrue()
        assertThat(second.await()).isEqualTo("prepared")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun advancingPlaybackGenerationCancelsAllPrefetchJobs() = runTest {
        val controller = PlaybackPrefetchController(PlaybackWorkBudget())
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val job = controller.launchLatest(
            scope = this,
            slot = PlaybackWorkSlot.REPLAY_GAIN,
            key = "old-song"
        ) {
            try {
                started.complete(Unit)
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
        started.await()

        controller.advancePlaybackGeneration()
        advanceUntilIdle()

        assertThat(job.isCancelled).isTrue()
        assertThat(cancelled.isCompleted).isTrue()
    }
}
