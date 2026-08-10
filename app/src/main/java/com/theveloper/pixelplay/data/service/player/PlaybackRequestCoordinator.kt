package com.theveloper.pixelplay.data.service.player

import androidx.annotation.MainThread
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.theveloper.pixelplay.utils.seekToPreviousItemOrStart
import com.theveloper.pixelplay.utils.PlaybackTrace
import com.theveloper.pixelplay.utils.PlaybackTraceContext
import com.theveloper.pixelplay.utils.PlaybackTraceOrigin
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

enum class PlaybackRequestLane {
    FULL_QUEUE,
    DIRECT,
    QUEUE_MUTATION
}

class PlaybackRequestToken internal constructor(
    val generation: Long,
    val lane: PlaybackRequestLane,
    val traceContext: PlaybackTraceContext,
    private val isCurrentRequest: (PlaybackRequestToken) -> Boolean
) {
    val requestId: String
        get() = traceContext.requestId
    val isCurrent: Boolean
        get() = isCurrentRequest(this)

    fun ensureCurrent() {
        if (!isCurrent) throw CancellationException("Stale $lane playback request")
    }
}

/**
 * Small synchronous actor used for every mutation of the active Media3 player.
 *
 * Callers keep the immediate return values required by MediaSession callbacks, while concurrent
 * callers are queued in FIFO order and only one command is ever allowed to touch the player at a
 * time. Re-entrant commands execute inside the current actor turn to avoid self-deadlock.
 */
internal class SerialPlaybackCommandActor {
    private val mailbox = ArrayDeque<FutureTask<*>>()
    private var drainingThread: Thread? = null

    fun <T> execute(command: () -> T): T {
        val currentThread = Thread.currentThread()
        if (synchronized(mailbox) { drainingThread === currentThread }) {
            return command()
        }

        val task = FutureTask(command)
        val shouldDrain = synchronized(mailbox) {
            mailbox.addLast(task)
            if (drainingThread == null) {
                drainingThread = currentThread
                true
            } else {
                false
            }
        }
        if (shouldDrain) drainMailbox(currentThread)

        return try {
            task.get()
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
    }

    private fun drainMailbox(owner: Thread) {
        while (true) {
            val task = synchronized(mailbox) {
                mailbox.removeFirstOrNull() ?: run {
                    if (drainingThread === owner) drainingThread = null
                    return
                }
            }
            task.run()
        }
    }
}

sealed interface PlaybackTransportCommand {
    data object Play : PlaybackTransportCommand
    data object Pause : PlaybackTransportCommand
    data object TogglePlayPause : PlaybackTransportCommand
    data object Next : PlaybackTransportCommand
    data object Previous : PlaybackTransportCommand
    data class Seek(val positionMs: Long) : PlaybackTransportCommand
    data class PlayQueueIndex(val index: Int) : PlaybackTransportCommand
    data class SetRepeatMode(@Player.RepeatMode val mode: Int) : PlaybackTransportCommand
    data class SetShuffleMode(val enabled: Boolean) : PlaybackTransportCommand
    data class SetVolume(val volume: Float) : PlaybackTransportCommand
}

/** Single ownership point for request generations and local transport commands. */
@Singleton
class PlaybackRequestCoordinator @Inject constructor() {
    private val nextGeneration = AtomicLong(0L)
    private val lock = Any()
    private var currentToken: PlaybackRequestToken? = null
    private var activeJob: Job? = null
    private val childJobs = linkedSetOf<Job>()
    private var pendingPlayerReadyAction: PendingPlayerReadyAction? = null
    private val commandActor = SerialPlaybackCommandActor()

    private data class PendingPlayerReadyAction(
        val token: PlaybackRequestToken?,
        val action: () -> Unit
    )

    fun launchLatest(
        scope: CoroutineScope,
        lane: PlaybackRequestLane,
        block: suspend PlaybackRequestToken.() -> Unit
    ): PlaybackRequestToken {
        val token = begin(lane)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                token.block()
            } finally {
                complete(token)
            }
        }
        synchronized(lock) {
            if (currentToken === token) activeJob = job else job.cancel()
        }
        job.start()
        return token
    }

    fun launchChild(
        scope: CoroutineScope,
        token: PlaybackRequestToken,
        block: suspend PlaybackRequestToken.() -> Unit
    ): Job {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            token.ensureCurrent()
            token.block()
        }
        synchronized(lock) {
            if (currentToken === token) {
                childJobs += job
                job.invokeOnCompletion { synchronized(lock) { childJobs.remove(job) } }
            } else {
                job.cancel()
            }
        }
        job.start()
        return job
    }

    fun cancel(lane: PlaybackRequestLane? = null) {
        synchronized(lock) {
            val current = currentToken ?: return
            if (lane != null && current.lane != lane) return
            nextGeneration.incrementAndGet()
            currentToken = null
            activeJob?.cancel()
            activeJob = null
            childJobs.forEach(Job::cancel)
            childJobs.clear()
            pendingPlayerReadyAction = null
        }
    }

    fun deferUntilPlayerReady(token: PlaybackRequestToken? = null, action: () -> Unit) {
        synchronized(lock) {
            if (token == null || currentToken === token) {
                pendingPlayerReadyAction = PendingPlayerReadyAction(token, action)
            }
        }
    }

    fun flushPlayerReadyAction(): Boolean {
        val pending = synchronized(lock) {
            pendingPlayerReadyAction.also { pendingPlayerReadyAction = null }
        } ?: return false
        if (pending.token != null && !pending.token.isCurrent) return false
        pending.action()
        return true
    }

    fun isCurrent(token: PlaybackRequestToken): Boolean = synchronized(lock) {
        currentToken === token && token.generation == nextGeneration.get()
    }

    @MainThread
    fun dispatchTransport(player: Player, command: PlaybackTransportCommand): Boolean {
        when (command) {
            PlaybackTransportCommand.Next,
            PlaybackTransportCommand.Previous,
            is PlaybackTransportCommand.Seek,
            is PlaybackTransportCommand.PlayQueueIndex -> cancel(PlaybackRequestLane.QUEUE_MUTATION)
            else -> Unit
        }
        return commandActor.execute { when (command) {
            PlaybackTransportCommand.Play -> {
                prepareIfNeeded(player)
                player.play()
                true
            }
            PlaybackTransportCommand.Pause -> {
                player.pause()
                true
            }
            PlaybackTransportCommand.TogglePlayPause -> {
                if (player.playWhenReady) player.pause() else {
                    prepareIfNeeded(player)
                    player.play()
                }
                true
            }
            PlaybackTransportCommand.Next -> {
                if (!player.hasNextMediaItem()) return@execute false
                player.seekToNext()
                prepareIfNeeded(player)
                player.play()
                true
            }
            PlaybackTransportCommand.Previous -> {
                player.seekToPreviousItemOrStart()
                true
            }
            is PlaybackTransportCommand.Seek -> {
                player.seekTo(command.positionMs.coerceAtLeast(0L))
                true
            }
            is PlaybackTransportCommand.PlayQueueIndex -> {
                if (command.index !in 0 until player.mediaItemCount) return@execute false
                player.seekToDefaultPosition(command.index)
                prepareIfNeeded(player)
                player.play()
                true
            }
            is PlaybackTransportCommand.SetRepeatMode -> {
                player.repeatMode = command.mode
                true
            }
            is PlaybackTransportCommand.SetShuffleMode -> {
                player.shuffleModeEnabled = command.enabled
                true
            }
            is PlaybackTransportCommand.SetVolume -> {
                player.volume = command.volume.coerceIn(0f, 1f)
                true
            }
        } }
    }

    @MainThread
    fun playLoadedItem(player: Player, targetIndex: Int): Boolean = commandActor.execute {
        if (targetIndex !in 0 until player.mediaItemCount) return@execute false
        val shouldRestart = player.currentMediaItemIndex != targetIndex ||
            player.playbackState == Player.STATE_ENDED
        if (shouldRestart) player.seekTo(targetIndex, 0L)
        prepareIfNeeded(player)
        player.play()
        true
    }

    @MainThread
    fun replaceCurrentAndPlay(
        player: Player,
        mediaItem: MediaItem,
        startPositionMs: Long = 0L,
        playWhenReady: Boolean = true
    ) = commandActor.execute {
        player.setMediaItem(mediaItem, startPositionMs.coerceAtLeast(0L))
        player.prepare()
        if (playWhenReady) player.play() else player.playWhenReady = false
    }

    @MainThread
    fun appendMediaItem(player: Player, mediaItem: MediaItem) = commandActor.execute {
        player.addMediaItem(mediaItem)
    }

    @MainThread
    fun insertMediaItem(player: Player, index: Int, mediaItem: MediaItem) = commandActor.execute {
        player.addMediaItem(index.coerceIn(0, player.mediaItemCount), mediaItem)
    }

    @MainThread
    fun insertMediaItems(player: Player, index: Int, mediaItems: List<MediaItem>) = commandActor.execute {
        if (mediaItems.isNotEmpty()) {
            player.addMediaItems(index.coerceIn(0, player.mediaItemCount), mediaItems)
        }
    }

    @MainThread
    fun removeMediaItem(player: Player, index: Int): Boolean = commandActor.execute {
        if (index !in 0 until player.mediaItemCount) return@execute false
        player.removeMediaItem(index)
        true
    }

    @MainThread
    fun removeMediaItems(player: Player, indices: Collection<Int>): Int = commandActor.execute {
        var removed = 0
        indices.distinct().sortedDescending().forEach { index ->
            if (index in 0 until player.mediaItemCount) {
                player.removeMediaItem(index)
                removed++
            }
        }
        removed
    }

    @MainThread
    fun moveMediaItem(player: Player, fromIndex: Int, toIndex: Int): Boolean = commandActor.execute {
        if (fromIndex !in 0 until player.mediaItemCount || toIndex !in 0 until player.mediaItemCount) {
            return@execute false
        }
        player.moveMediaItem(fromIndex, toIndex)
        true
    }

    @MainThread
    fun replaceMediaItemPreservingPosition(
        player: Player,
        index: Int,
        mediaItem: MediaItem
    ): Boolean = commandActor.execute {
        if (index !in 0 until player.mediaItemCount) return@execute false
        val positionMs = player.currentPosition.coerceAtLeast(0L)
        val wasCurrent = player.currentMediaItemIndex == index
        player.replaceMediaItem(index, mediaItem)
        if (wasCurrent) player.seekTo(index, positionMs)
        true
    }

    @MainThread
    fun stopAndClear(player: Player) = commandActor.execute {
        player.playWhenReady = false
        player.stop()
        player.clearMediaItems()
    }

    @MainThread
    fun replaceMediaItems(
        player: Player,
        fromIndex: Int,
        toIndex: Int,
        mediaItems: List<MediaItem>
    ) = commandActor.execute {
        player.replaceMediaItems(fromIndex, toIndex, mediaItems)
    }

    @MainThread
    fun recoverResolvedItem(
        player: Player,
        expectedMediaId: String,
        expectedRequestId: String? = null,
        expectedSourceUri: String? = null,
        index: Int,
        mediaItem: MediaItem,
        positionMs: Long,
        playWhenReady: Boolean
    ): Boolean = commandActor.execute {
        val currentItem = player.currentMediaItem
        val currentSourceUri = currentItem?.mediaMetadata?.extras
            ?.getString(com.theveloper.pixelplay.utils.MediaItemBuilder.EXTERNAL_EXTRA_CONTENT_URI)
        if (index !in 0 until player.mediaItemCount ||
            currentItem?.mediaId != expectedMediaId ||
            (expectedRequestId != null && PlaybackTrace.requestId(currentItem) != expectedRequestId) ||
            (expectedSourceUri != null && currentSourceUri != expectedSourceUri)
        ) {
            return@execute false
        }
        player.replaceMediaItem(index, mediaItem)
        player.seekTo(index, positionMs.coerceAtLeast(0L))
        player.prepare()
        if (playWhenReady) player.play() else player.playWhenReady = false
        true
    }

    @MainThread
    fun replaceQueue(
        player: Player,
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
        playWhenReady: Boolean = true,
        prepare: Boolean = true
    ): Boolean = commandActor.execute {
        val plan = QueueLoadPlan.create(mediaItems, startIndex) ?: return@execute false
        player.setMediaItem(plan.current, startPositionMs.coerceAtLeast(0L))
        if (prepare) player.prepare()
        var insertionIndex = 0
        plan.beforeBatches().forEach { batch ->
            player.addMediaItems(insertionIndex, batch)
            insertionIndex += batch.size
        }
        plan.afterBatches().forEach(player::addMediaItems)
        if (playWhenReady) player.play() else player.playWhenReady = false
        true
    }

    private fun prepareIfNeeded(player: Player) {
        if (player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0) player.prepare()
    }

    private fun begin(lane: PlaybackRequestLane): PlaybackRequestToken = synchronized(lock) {
        activeJob?.cancel()
        childJobs.forEach(Job::cancel)
        childJobs.clear()
        pendingPlayerReadyAction = null
        val generation = nextGeneration.incrementAndGet()
        val traceContext = PlaybackTrace.newContext(origin = PlaybackTraceOrigin.UI)
        PlaybackRequestToken(generation, lane, traceContext, ::isCurrent).also { currentToken = it }
    }

    private fun complete(token: PlaybackRequestToken) {
        synchronized(lock) {
            if (currentToken === token) activeJob = null
        }
    }
}
