package com.theveloper.pixelplay.data.recommendation

import com.theveloper.pixelplay.data.model.OnlineBrowsePlatform
import com.theveloper.pixelplay.data.model.RecommendationPage
import com.theveloper.pixelplay.data.model.RemoteChartDetail
import com.theveloper.pixelplay.data.model.RemoteChartReference
import com.theveloper.pixelplay.data.model.RemotePlaylistDetail
import com.theveloper.pixelplay.data.model.RemotePlaylistReference
import com.theveloper.pixelplay.data.model.TransientOnlineSong
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

@Singleton
class OnlineRecommendationRepositoryImpl @Inject constructor(
    neteaseProvider: NeteaseRecommendationProvider,
    qqMusicProvider: QqMusicRecommendationProvider,
    private val cacheStore: RecommendationCacheStore
) : OnlineRecommendationRepository {
    private companion object {
        const val CACHE_TTL_MS = 15 * 60 * 1000L
        const val STALE_CACHE_TTL_MS = 24 * 60 * 60 * 1000L
    }

    private data class CachedPage(val page: RecommendationPage, val storedAt: Long)

    private val _platform = MutableStateFlow(OnlineBrowsePlatform.NETEASE)
    override val platform: StateFlow<OnlineBrowsePlatform> = _platform.asStateFlow()
    private val providers: List<OnlineRecommendationProvider> = listOf(neteaseProvider, qqMusicProvider)
    private val cache = ConcurrentHashMap<OnlineBrowsePlatform, CachedPage>()
    private val refreshMutexes = OnlineBrowsePlatform.entries.associateWith { Mutex() }

    override suspend fun setPlatform(platform: OnlineBrowsePlatform) {
        _platform.value = platform
    }

    override suspend fun refresh(
        platform: OnlineBrowsePlatform,
        force: Boolean
    ): Result<RecommendationPage> = refreshMutexes.getValue(platform).withLock {
        val now = System.currentTimeMillis()
        val cached = cache[platform] ?: cacheStore.read(platform)?.let {
            CachedPage(it.page, it.storedAt).also { restored -> cache[platform] = restored }
        }
        if (!force && cached != null && now - cached.storedAt < CACHE_TTL_MS) {
            return@withLock Result.success(cached.page)
        }

        val provider = providers.firstOrNull { it.platform == platform }
            ?: return@withLock Result.failure(IllegalStateException("No recommendation provider for $platform"))

        provider.loadHomePage(forceRefresh = force).fold(
            onSuccess = { page ->
                val freshPage = page.copy(isStale = false)
                cache[platform] = CachedPage(freshPage, now)
                cacheStore.write(freshPage)
                Result.success(page)
            },
            onFailure = { error ->
                Timber.w(error, "Recommendation refresh failed platform=%s", platform)
                if (cached != null && now - cached.storedAt < STALE_CACHE_TTL_MS) {
                    Result.success(cached.page.copy(isStale = true))
                } else {
                    Result.failure(error)
                }
            }
        )
    }

    override suspend fun loadPlaylist(reference: RemotePlaylistReference): Result<RemotePlaylistDetail> =
        providerFor(reference.platform).fold(
            onSuccess = { it.loadPlaylist(reference) },
            onFailure = { Result.failure(it) }
        )

    override suspend fun loadChart(reference: RemoteChartReference): Result<RemoteChartDetail> =
        providerFor(reference.platform).fold(
            onSuccess = { it.loadChart(reference) },
            onFailure = { Result.failure(it) }
        )

    override suspend fun loadSection(
        platform: OnlineBrowsePlatform,
        sectionId: String
    ): Result<List<TransientOnlineSong>> = providerFor(platform).fold(
        onSuccess = { it.loadSection(sectionId) },
        onFailure = { Result.failure(it) }
    )

    private fun providerFor(platform: OnlineBrowsePlatform): Result<OnlineRecommendationProvider> =
        providers.firstOrNull { it.platform == platform }
            ?.let { Result.success(it) }
            ?: Result.failure(IllegalStateException("No recommendation provider for $platform"))
}
