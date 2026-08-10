package com.theveloper.pixelplay.presentation.viewmodel

import android.os.Bundle
import com.theveloper.pixelplay.data.service.player.PlaybackRequestCoordinator
import com.theveloper.pixelplay.data.service.player.PlaybackRequestLane
import com.theveloper.pixelplay.data.service.player.PlaybackTransportCommand
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.utils.MediaItemBuilder
import com.theveloper.pixelplay.utils.PlaybackTrace
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackRequestCoordinatorTest {
    @Test
    fun newerRequestInvalidatesOlderRequest() = runTest {
        val coordinator = PlaybackRequestCoordinator()
        val firstStarted = CompletableDeferred<Unit>()
        val firstCanFinish = CompletableDeferred<Unit>()
        var firstCommitted = false
        var secondCommitted = false

        val first = coordinator.launchLatest(this, PlaybackRequestLane.DIRECT) {
            firstStarted.complete(Unit)
            firstCanFinish.await()
            ensureCurrent()
            firstCommitted = true
        }
        firstStarted.await()
        val second = coordinator.launchLatest(this, PlaybackRequestLane.DIRECT) {
            ensureCurrent()
            secondCommitted = true
        }
        firstCanFinish.complete(Unit)
        advanceUntilIdle()

        assertThat(first.isCurrent).isFalse()
        assertThat(second.isCurrent).isTrue()
        assertThat(firstCommitted).isFalse()
        assertThat(secondCommitted).isTrue()
    }

    @Test
    fun transportNextPreparesAndPlaysThroughSingleCommand() {
        val player = mockk<Player>(relaxed = true)
        every { player.hasNextMediaItem() } returns true
        every { player.playbackState } returns Player.STATE_IDLE
        every { player.mediaItemCount } returns 2

        val handled = PlaybackRequestCoordinator().dispatchTransport(
            player,
            PlaybackTransportCommand.Next
        )

        assertThat(handled).isTrue()
        verifyOrder {
            player.seekToNext()
            player.prepare()
            player.play()
        }
    }

    @Test
    fun previousAlwaysMovesToPreviousItemWhenOneExists() {
        val player = mockk<Player>(relaxed = true)
        every { player.previousMediaItemIndex } returns 3

        val handled = PlaybackRequestCoordinator().dispatchTransport(
            player,
            PlaybackTransportCommand.Previous
        )

        assertThat(handled).isTrue()
        verify(exactly = 1) { player.seekToDefaultPosition(3) }
        verify(exactly = 0) { player.seekTo(0L) }
    }

    @Test
    fun repeatShuffleAndVolumeCommandsCommitOnlyToPlayer() {
        val player = mockk<Player>(relaxed = true)
        val coordinator = PlaybackRequestCoordinator()

        assertThat(
            coordinator.dispatchTransport(
                player,
                PlaybackTransportCommand.SetRepeatMode(Player.REPEAT_MODE_ALL)
            )
        ).isTrue()
        assertThat(
            coordinator.dispatchTransport(player, PlaybackTransportCommand.SetShuffleMode(true))
        ).isTrue()
        assertThat(
            coordinator.dispatchTransport(player, PlaybackTransportCommand.SetVolume(2f))
        ).isTrue()

        verify(exactly = 1) { player.repeatMode = Player.REPEAT_MODE_ALL }
        verify(exactly = 1) { player.shuffleModeEnabled = true }
        verify(exactly = 1) { player.volume = 1f }
    }

    @Test
    fun replaceQueueLoadsCurrentFirstAndAppendsTwoHundredItemBatches() {
        val player = mockk<Player>(relaxed = true)
        val mediaItems = (0 until 1_000).map { index ->
            MediaItem.Builder().setMediaId(index.toString()).build()
        }

        val handled = PlaybackRequestCoordinator().replaceQueue(
            player = player,
            mediaItems = mediaItems,
            startIndex = 450,
            startPositionMs = 12_000L
        )

        assertThat(handled).isTrue()
        verify(exactly = 1) { player.setMediaItem(mediaItems[450], 12_000L) }
        verify(exactly = 3) { player.addMediaItems(any<Int>(), any<List<MediaItem>>()) }
        verify(exactly = 3) { player.addMediaItems(any<List<MediaItem>>()) }
        verify(exactly = 1) { player.prepare() }
        verify(exactly = 1) { player.play() }
    }

    @Test
    fun recoveryDoesNotReplaceItemAfterPlayerMovedToAnotherSong() {
        val player = mockk<Player>(relaxed = true)
        every { player.mediaItemCount } returns 2
        every { player.currentMediaItem } returns MediaItem.Builder().setMediaId("new-song").build()
        val replacement = MediaItem.Builder().setMediaId("old-song").build()

        val recovered = PlaybackRequestCoordinator().recoverResolvedItem(
            player = player,
            expectedMediaId = "old-song",
            index = 0,
            mediaItem = replacement,
            positionMs = 5_000L,
            playWhenReady = true
        )

        assertThat(recovered).isFalse()
        verify(exactly = 0) { player.replaceMediaItem(any(), any()) }
        verify(exactly = 0) { player.prepare() }
    }

    @Test
    fun recoveryDoesNotReplaceItemWhenRequestGenerationChangedForSameMediaId() {
        val player = mockk<Player>(relaxed = true)
        every { player.mediaItemCount } returns 1
        every { player.currentMediaItem } returns tracedMediaItem(
            mediaId = "same-song",
            requestId = "new-request",
            sourceUri = "qqmusic://same-song"
        )

        val recovered = PlaybackRequestCoordinator().recoverResolvedItem(
            player = player,
            expectedMediaId = "same-song",
            expectedRequestId = "old-request",
            expectedSourceUri = "qqmusic://same-song",
            index = 0,
            mediaItem = MediaItem.Builder().setMediaId("same-song").build(),
            positionMs = 5_000L,
            playWhenReady = true
        )

        assertThat(recovered).isFalse()
        verify(exactly = 0) { player.replaceMediaItem(any(), any()) }
    }

    @Test
    fun recoveryDoesNotReplaceItemWhenSourceChangedForSameMediaAndRequestId() {
        val player = mockk<Player>(relaxed = true)
        every { player.mediaItemCount } returns 1
        every { player.currentMediaItem } returns tracedMediaItem(
            mediaId = "same-song",
            requestId = "same-request",
            sourceUri = "netease://123"
        )

        val recovered = PlaybackRequestCoordinator().recoverResolvedItem(
            player = player,
            expectedMediaId = "same-song",
            expectedRequestId = "same-request",
            expectedSourceUri = "qqmusic://same-song",
            index = 0,
            mediaItem = MediaItem.Builder().setMediaId("same-song").build(),
            positionMs = 5_000L,
            playWhenReady = true
        )

        assertThat(recovered).isFalse()
        verify(exactly = 0) { player.replaceMediaItem(any(), any()) }
    }

    @Test
    fun pausedQueueRestoreCanSkipPrepareAndRemainPaused() {
        val player = mockk<Player>(relaxed = true)
        val items = listOf(MediaItem.Builder().setMediaId("1").build())

        val handled = PlaybackRequestCoordinator().replaceQueue(
            player = player,
            mediaItems = items,
            startIndex = 0,
            startPositionMs = 4_000L,
            playWhenReady = false,
            prepare = false
        )

        assertThat(handled).isTrue()
        verify(exactly = 0) { player.prepare() }
        verify(exactly = 0) { player.play() }
        verify(exactly = 1) { player.playWhenReady = false }
    }

    @Test
    fun newerPlaybackRequestCancelsQueueAttachmentChild() = runTest {
        val coordinator = PlaybackRequestCoordinator()
        val childStarted = CompletableDeferred<Unit>()
        val childCancelled = CompletableDeferred<Unit>()

        coordinator.launchLatest(this, PlaybackRequestLane.DIRECT) {
            coordinator.launchChild(this@runTest, this) {
                try {
                    childStarted.complete(Unit)
                    awaitCancellation()
                } finally {
                    childCancelled.complete(Unit)
                }
            }
        }
        childStarted.await()

        coordinator.launchLatest(this, PlaybackRequestLane.DIRECT) { ensureCurrent() }
        advanceUntilIdle()

        assertThat(childCancelled.isCompleted).isTrue()
    }

    @Test
    fun navigationCommandCancelsInFlightQueueMutation() = runTest {
        val coordinator = PlaybackRequestCoordinator()
        val mutationStarted = CompletableDeferred<Unit>()
        val mutationCancelled = CompletableDeferred<Unit>()
        val player = mockk<Player>(relaxed = true)
        every { player.previousMediaItemIndex } returns 0

        coordinator.launchLatest(this, PlaybackRequestLane.QUEUE_MUTATION) {
            try {
                mutationStarted.complete(Unit)
                awaitCancellation()
            } finally {
                mutationCancelled.complete(Unit)
            }
        }
        mutationStarted.await()

        coordinator.dispatchTransport(player, PlaybackTransportCommand.Previous)
        advanceUntilIdle()

        assertThat(mutationCancelled.isCompleted).isTrue()
        verify(exactly = 1) { player.seekToDefaultPosition(0) }
    }

    @Test
    fun deferredPlayerReadyActionIsDiscardedByNewerRequest() = runTest {
        val coordinator = PlaybackRequestCoordinator()
        var staleActionRan = false

        coordinator.launchLatest(this, PlaybackRequestLane.DIRECT) {
            coordinator.deferUntilPlayerReady(this) { staleActionRan = true }
        }
        advanceUntilIdle()
        coordinator.launchLatest(this, PlaybackRequestLane.DIRECT) { ensureCurrent() }
        advanceUntilIdle()

        assertThat(coordinator.flushPlayerReadyAction()).isFalse()
        assertThat(staleActionRan).isFalse()
    }

    @Test
    fun queueMutationMethodsAreOwnedByCoordinator() {
        val player = mockk<Player>(relaxed = true)
        every { player.mediaItemCount } returns 3

        val coordinator = PlaybackRequestCoordinator()
        assertThat(coordinator.moveMediaItem(player, 0, 2)).isTrue()
        assertThat(coordinator.removeMediaItems(player, listOf(0, 2))).isEqualTo(2)

        verify(exactly = 1) { player.moveMediaItem(0, 2) }
        verifyOrder {
            player.removeMediaItem(2)
            player.removeMediaItem(0)
        }
    }

    private fun tracedMediaItem(
        mediaId: String,
        requestId: String,
        sourceUri: String
    ): MediaItem {
        val extras = Bundle().apply {
            putString(PlaybackTrace.EXTRA_REQUEST_ID, requestId)
            putString(MediaItemBuilder.EXTERNAL_EXTRA_CONTENT_URI, sourceUri)
        }
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setExtras(extras)
                    .build()
            )
            .build()
    }
}
