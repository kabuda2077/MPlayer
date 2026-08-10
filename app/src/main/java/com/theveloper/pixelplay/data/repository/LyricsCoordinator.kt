package com.theveloper.pixelplay.data.repository

import android.util.LruCache
import com.theveloper.pixelplay.data.model.Lyrics
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

internal data class LyricsLoadAttempt(
    val sourceName: String,
    val fetch: suspend () -> Lyrics?
)

internal data class PrioritizedLyricsAttempt<T>(
    val sourceName: String,
    val priority: Int,
    val fetch: suspend () -> T?
)

/**
 * Owns the lyrics request lifecycle independently from provider and persistence details.
 *
 * The repository supplies Room/local/provider operations; this coordinator decides when cache,
 * stored data, local sources and remote sources may run, and performs deterministic concurrent
 * candidate selection. This keeps force-refresh and cancellation semantics in one place.
 */
@Singleton
class LyricsCoordinator @Inject constructor() {
    private val memoryCache = LruCache<String, Lyrics>(MAX_MEMORY_ENTRIES)

    internal suspend fun resolve(
        cacheKey: String,
        forceRefresh: Boolean,
        loadStored: suspend () -> Lyrics?,
        attempts: List<LyricsLoadAttempt>,
        onSourceHit: (String) -> Unit = {},
        onSourceFailure: (String, Throwable) -> Unit = { _, _ -> }
    ): Lyrics? {
        if (!forceRefresh) {
            memoryCache.get(cacheKey)?.let { return it }
            loadStored()?.takeIf { it.hasContent() }?.let { stored ->
                memoryCache.put(cacheKey, stored)
                return stored
            }
        }

        for (attempt in attempts) {
            try {
                val lyrics = attempt.fetch()?.takeIf { it.hasContent() } ?: continue
                memoryCache.put(cacheKey, lyrics)
                onSourceHit(attempt.sourceName)
                return lyrics
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                onSourceFailure(attempt.sourceName, error)
            }
        }
        return null
    }

    internal suspend fun <T> selectFirstByPriority(
        attempts: List<PrioritizedLyricsAttempt<T>>,
        isUsable: (T) -> Boolean,
        onTry: (PrioritizedLyricsAttempt<T>) -> Unit = {},
        onHit: (PrioritizedLyricsAttempt<T>, T) -> Unit = { _, _ -> },
        onMiss: (PrioritizedLyricsAttempt<T>, Throwable?) -> Unit = { _, _ -> }
    ): T? {
        val ordered = attempts.sortedBy(PrioritizedLyricsAttempt<T>::priority)
        return selectFirstAvailableByPriority(
            ordered.map { attempt ->
                suspend {
                    onTry(attempt)
                    try {
                        attempt.fetch()
                            ?.takeIf(isUsable)
                            ?.also { onHit(attempt, it) }
                            ?: run {
                                onMiss(attempt, null)
                                null
                            }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Throwable) {
                        onMiss(attempt, error)
                        null
                    }
                }
            }
        )
    }

    fun getMemory(cacheKey: String): Lyrics? = memoryCache.get(cacheKey)

    fun putMemory(cacheKey: String, lyrics: Lyrics) {
        if (lyrics.hasContent()) memoryCache.put(cacheKey, lyrics)
    }

    fun removeMemory(cacheKey: String) {
        memoryCache.remove(cacheKey)
    }

    fun clearMemory() {
        memoryCache.evictAll()
    }

    private fun Lyrics.hasContent(): Boolean = !synced.isNullOrEmpty() || !plain.isNullOrEmpty()

    private companion object {
        const val MAX_MEMORY_ENTRIES = 150
    }
}
