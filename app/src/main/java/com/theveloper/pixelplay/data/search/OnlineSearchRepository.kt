package com.theveloper.pixelplay.data.search

import com.theveloper.pixelplay.data.model.OnlineSearchPlatform
import com.theveloper.pixelplay.data.model.OnlineSearchResultItem
import com.theveloper.pixelplay.data.netease.NeteaseRepository
import com.theveloper.pixelplay.data.network.qqmusic.QqMusicApiService
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import timber.log.Timber

@Singleton
class OnlineSearchRepository @Inject constructor(
    neteaseRepository: NeteaseRepository,
    qqMusicApiService: QqMusicApiService,
    okHttpClient: OkHttpClient
) {
    private val providers: Map<OnlineSearchPlatform, OnlineSearchProvider> = listOf(
        NeteaseSearchProvider(neteaseRepository, okHttpClient),
        QqMusicSearchProvider(qqMusicApiService),
        KugouSearchProvider(okHttpClient),
        KuwoSearchProvider(okHttpClient)
    ).associateBy { it.platform }

    suspend fun loadPopularSearchTerms(
        platform: OnlineSearchPlatform,
        limit: Int = POPULAR_SEARCH_LIMIT
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        val provider = providers[platform] ?: return@withContext Result.failure(
            IllegalArgumentException("Unsupported search platform: $platform")
        )
        runCatching {
            val terms = provider.loadPopularSearchTerms(limit)
            terms.asSequence()
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
                .take(limit)
                .toList()
                .also { require(it.isNotEmpty()) { "Popular search returned no terms" } }
        }.onFailure { throwable ->
            Timber.w(throwable, "Popular search failed platform=%s", platform.source.lxSource)
        }
    }

    suspend fun searchSongs(
        query: String,
        platform: OnlineSearchPlatform,
        limit: Int = DEFAULT_LIMIT
    ): Result<List<OnlineSearchResultItem>> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext Result.success(emptyList())

        val provider = providers[platform] ?: return@withContext Result.failure(
            IllegalArgumentException("Unsupported search platform: $platform")
        )

        runCatching {
            provider.searchSongs(query, limit).map { song ->
                OnlineSearchResultItem(transientSong = song, platform = platform)
            }
        }.onFailure { throwable ->
            Timber.w(
                throwable,
                "Online song search failed platform=%s query=%s",
                platform.source.lxSource,
                query
            )
        }
    }

    private companion object {
        const val DEFAULT_LIMIT = 30
        const val POPULAR_SEARCH_LIMIT = 8
    }
}
