package com.theveloper.pixelplay.data.service.player

import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class PlaybackWorkSlot {
    ADJACENT_SOURCE_RESOLUTION,
    AUXILIARY_PLAYER,
    REPLAY_GAIN,
    CACHE_FILL
}

@Singleton
class PlaybackWorkBudget @Inject constructor() {
    private data class Owner(val token: Long, val key: String, val generation: Long)

    private val nextToken = AtomicLong(0L)
    private val playbackGeneration = AtomicLong(0L)
    private val owners = mutableMapOf<PlaybackWorkSlot, Owner>()
    private val mutexes = PlaybackWorkSlot.entries.associateWith { Mutex() }

    fun tryAcquire(slot: PlaybackWorkSlot, key: String): Lease? = synchronized(owners) {
        if (owners.containsKey(slot)) return null
        val owner = Owner(nextToken.incrementAndGet(), key, playbackGeneration.get())
        owners[slot] = owner
        Lease(
            slot = slot,
            token = owner.token,
            key = key,
            generation = owner.generation,
            isCurrent = { leaseSlot, leaseToken, leaseGeneration ->
                playbackGeneration.get() == leaseGeneration && synchronized(owners) {
                    owners[leaseSlot]?.token == leaseToken
                }
            },
            release = { releaseSlot, releaseToken ->
                synchronized(owners) {
                    if (owners[releaseSlot]?.token == releaseToken) owners.remove(releaseSlot)
                }
            }
        )
    }

    /** Invalidates work that belongs to the previously playing item. */
    fun advancePlaybackGeneration(): Long = playbackGeneration.incrementAndGet()

    suspend fun <T> withExclusive(
        slot: PlaybackWorkSlot,
        block: suspend () -> T
    ): T = requireNotNull(mutexes[slot]).withLock { block() }

    class Lease internal constructor(
        val slot: PlaybackWorkSlot,
        private val token: Long,
        val key: String,
        private val generation: Long,
        private val isCurrent: (PlaybackWorkSlot, Long, Long) -> Boolean,
        private val release: (PlaybackWorkSlot, Long) -> Unit
    ) : Closeable {
        private var closed = false

        val isValid: Boolean
            get() = !closed && isCurrent(slot, token, generation)

        override fun close() {
            if (closed) return
            closed = true
            release(slot, token)
        }
    }
}

@Singleton
class PlaybackPrefetchController @Inject constructor(
    private val budget: PlaybackWorkBudget
) {
    private val jobs = mutableMapOf<PlaybackWorkSlot, Job>()

    fun launchLatest(
        scope: CoroutineScope,
        slot: PlaybackWorkSlot,
        key: String,
        block: suspend () -> Unit
    ): Job {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            budget.withExclusive(slot) { block() }
        }
        synchronized(jobs) {
            jobs.remove(slot)?.cancel()
            jobs[slot] = job
            job.invokeOnCompletion {
                synchronized(jobs) {
                    if (jobs[slot] === job) jobs.remove(slot)
                }
            }
        }
        job.start()
        return job
    }

    /**
     * Registers a suspend prefetch operation in the same cancellation table as [launchLatest].
     * This is used by auxiliary-player preparation, whose caller owns the coroutine.
     */
    suspend fun <T> runLatest(
        slot: PlaybackWorkSlot,
        key: String,
        block: suspend () -> T
    ): T {
        val job = currentCoroutineContext()[Job]
            ?: error("Playback prefetch requires a coroutine Job")
        synchronized(jobs) {
            jobs.remove(slot)?.takeIf { it !== job }?.cancel()
            jobs[slot] = job
        }
        return try {
            budget.withExclusive(slot) {
                currentCoroutineContext().ensureActive()
                block()
            }
        } finally {
            synchronized(jobs) {
                if (jobs[slot] === job) jobs.remove(slot)
            }
        }
    }

    fun advancePlaybackGeneration() {
        budget.advancePlaybackGeneration()
        val staleJobs = synchronized(jobs) {
            jobs.values.toList().also { jobs.clear() }
        }
        staleJobs.forEach(Job::cancel)
    }

    fun cancel(slot: PlaybackWorkSlot) {
        synchronized(jobs) { jobs.remove(slot) }?.cancel()
    }

    fun cancelAll() {
        val active = synchronized(jobs) {
            jobs.values.toList().also { jobs.clear() }
        }
        active.forEach(Job::cancel)
    }

    suspend fun <T> withExclusive(
        slot: PlaybackWorkSlot,
        block: suspend () -> T
    ): T = budget.withExclusive(slot, block)
}
