package com.theveloper.pixelplay.presentation.viewmodel

import com.theveloper.pixelplay.data.model.OnlineSearchPlatform
import com.theveloper.pixelplay.data.model.OnlineSearchResultItem
import com.theveloper.pixelplay.data.model.SearchFilterType
import com.theveloper.pixelplay.data.model.SearchResultItem
import com.theveloper.pixelplay.data.model.StorageFilter
import com.theveloper.pixelplay.data.database.SearchHistoryDao
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.data.search.OnlineSearchRepository
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

data class UnifiedSearchState(
    val query: String = "",
    val onlinePlatform: OnlineSearchPlatform = OnlineSearchPlatform.NETEASE,
    val libraryResults: ImmutableList<SearchResultItem> = persistentListOf(),
    val onlineResults: ImmutableList<OnlineSearchResultItem> = persistentListOf(),
    val libraryLoading: Boolean = false,
    val onlineLoading: Boolean = false,
    val libraryError: String? = null,
    val onlineError: String? = null,
    val popularSearchTerms: ImmutableList<String> = persistentListOf(),
    val searchHistory: ImmutableList<String> = persistentListOf()
)

@Singleton
class SearchStateHolder @Inject constructor(
    private val onlineSearchRepository: OnlineSearchRepository,
    private val musicRepository: MusicRepository,
    private val searchHistoryDao: SearchHistoryDao
) {
    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
        const val POPULAR_SEARCH_CACHE_MS = 15 * 60 * 1000L

        val POPULAR_SEARCH_FALLBACKS = mapOf(
            OnlineSearchPlatform.NETEASE to listOf("晴天", "后来", "周杰伦", "如愿", "陈奕迅", "起风了"),
            OnlineSearchPlatform.QQ to listOf("晴天", "小幸运", "林俊杰", "演员", "邓紫棋", "一路生花"),
            OnlineSearchPlatform.KUGOU to listOf("奢香夫人", "起风了", "凤凰传奇", "后来", "周深", "红日"),
            OnlineSearchPlatform.KUWO to listOf("海阔天空", "后来", "陈奕迅", "晴天", "孙燕姿", "红豆")
        )
    }

    private data class PopularSearchCacheEntry(
        val terms: ImmutableList<String>,
        val fetchedAt: Long
    )

    private data class SearchRequest(
        val query: String,
        val requestId: Long,
        val platform: OnlineSearchPlatform,
    )

    private val _onlineSearchResults = MutableStateFlow<ImmutableList<OnlineSearchResultItem>>(persistentListOf())
    val onlineSearchResults = _onlineSearchResults.asStateFlow()

    private val _onlineSearchPlatform = MutableStateFlow(OnlineSearchPlatform.NETEASE)
    val onlineSearchPlatform = _onlineSearchPlatform.asStateFlow()

    private val _onlineSearchLoading = MutableStateFlow(false)
    val onlineSearchLoading = _onlineSearchLoading.asStateFlow()

    private val _onlineSearchError = MutableStateFlow<String?>(null)
    val onlineSearchError = _onlineSearchError.asStateFlow()

    private val _librarySearchResults = MutableStateFlow<ImmutableList<SearchResultItem>>(persistentListOf())
    val librarySearchResults = _librarySearchResults.asStateFlow()

    private val _librarySearchLoading = MutableStateFlow(false)
    val librarySearchLoading = _librarySearchLoading.asStateFlow()

    private val _librarySearchError = MutableStateFlow<String?>(null)
    val librarySearchError = _librarySearchError.asStateFlow()

    private val _searchHistory = MutableStateFlow<ImmutableList<String>>(persistentListOf())
    val searchHistory = _searchHistory.asStateFlow()

    private val _unifiedState = MutableStateFlow(UnifiedSearchState())
    val unifiedState = _unifiedState.asStateFlow()
    private var lastLibraryQuery: String = ""

    private val searchRequests = MutableSharedFlow<SearchRequest>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val latestSearchRequestId = AtomicLong(0L)

    private var scope: CoroutineScope? = null
    private var searchJob: Job? = null
    private var popularSearchJob: Job? = null
    private val popularSearchCache = mutableMapOf<OnlineSearchPlatform, PopularSearchCacheEntry>()
    private var popularSearchTerms: ImmutableList<String> = persistentListOf()

    fun initialize(scope: CoroutineScope) {
        this.scope = scope
        observeSearchRequests()
        loadPopularSearchTerms(_onlineSearchPlatform.value)
        observeSearchHistory()
    }

    private fun observeSearchHistory() {
        scope?.launch {
            searchHistoryDao.getRecentSearches(limit = 20).collect { list ->
                _searchHistory.value = list.map { it.query }.toImmutableList()
                publishUnifiedState(_unifiedState.value.query)
            }
        }
    }

    fun recordSearchHistory(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return
        scope?.launch(Dispatchers.IO) {
            searchHistoryDao.recordSearch(trimmed)
        }
    }

    fun deleteSearchHistoryItem(query: String) {
        scope?.launch(Dispatchers.IO) {
            searchHistoryDao.deleteByQuery(query.trim())
        }
    }

    fun clearSearchHistory() {
        scope?.launch(Dispatchers.IO) {
            searchHistoryDao.clearAll()
        }
    }

    @OptIn(FlowPreview::class)
    private fun observeSearchRequests() {
        searchJob?.cancel()
        searchJob = scope?.launch {
            searchRequests
                .debounce(SEARCH_DEBOUNCE_MS)
                .collectLatest { request ->
                    val normalizedQuery = request.query
                    if (normalizedQuery.isBlank()) {
                        clearResults()
                        return@collectLatest
                    }

                    try {
                        _onlineSearchLoading.value = true
                        _onlineSearchError.value = null
                        _librarySearchLoading.value = false
                        _librarySearchError.value = null
                        val onlineResult = withContext(Dispatchers.IO) {
                            onlineSearchRepository.searchSongs(
                                query = normalizedQuery,
                                platform = request.platform
                            )
                        }
                        if (request.requestId != latestSearchRequestId.get()) {
                            return@collectLatest
                        }
                        onlineResult.fold(
                            onSuccess = { items ->
                                _onlineSearchResults.value = items.toImmutableList()
                                _onlineSearchError.value = null
                            },
                            onFailure = { error ->
                                Timber.e(error, "Error performing online search for query: $normalizedQuery")
                                _onlineSearchResults.value = persistentListOf()
                                _onlineSearchError.value = error.message
                            }
                        )
                    } catch (_: CancellationException) {
                        // Superseded by a newer query; ignore.
                    } catch (e: Exception) {
                        if (request.requestId == latestSearchRequestId.get()) {
                            Timber.e(e, "Error performing online search for query: $normalizedQuery")
                            _onlineSearchResults.value = persistentListOf()
                            _onlineSearchError.value = e.message
                        }
                    } finally {
                        if (request.requestId == latestSearchRequestId.get()) {
                            _onlineSearchLoading.value = false
                            _librarySearchLoading.value = false
                            publishUnifiedState(normalizedQuery)
                        }
                    }
                }
        }
    }

    fun updateOnlineSearchPlatform(platform: OnlineSearchPlatform, query: String) {
        if (_onlineSearchPlatform.value == platform) return
        _onlineSearchPlatform.value = platform
        loadPopularSearchTerms(platform)
        performSearch(query)
    }

    private fun loadPopularSearchTerms(platform: OnlineSearchPlatform) {
        popularSearchJob?.cancel()
        val fallback = POPULAR_SEARCH_FALLBACKS.getValue(platform).toImmutableList()
        val now = System.currentTimeMillis()
        val cached = popularSearchCache[platform]
        popularSearchTerms = cached
            ?.takeIf { now - it.fetchedAt < POPULAR_SEARCH_CACHE_MS }
            ?.terms
            ?: fallback
        publishUnifiedState(_unifiedState.value.query)

        if (cached != null && now - cached.fetchedAt < POPULAR_SEARCH_CACHE_MS) return
        popularSearchJob = scope?.launch {
            onlineSearchRepository.loadPopularSearchTerms(platform).onSuccess { terms ->
                if (_onlineSearchPlatform.value != platform || terms.isEmpty()) return@onSuccess
                val immutableTerms = terms.toImmutableList()
                popularSearchCache[platform] = PopularSearchCacheEntry(immutableTerms, System.currentTimeMillis())
                popularSearchTerms = immutableTerms
                publishUnifiedState(_unifiedState.value.query)
            }
        }
    }

    fun performSearch(query: String) {
        val normalizedQuery = query.trim()
        val requestId = latestSearchRequestId.incrementAndGet()

        if (normalizedQuery.isBlank()) {
            clearResults()
        } else {
            // Mark the new request as pending before the debounce window starts.
            // Otherwise Compose briefly interprets the empty result list as a
            // completed search and flashes the "no results" state.
            _onlineSearchResults.value = persistentListOf()
            _onlineSearchLoading.value = true
            _onlineSearchError.value = null
            if (normalizedQuery != lastLibraryQuery) {
                _librarySearchResults.value = persistentListOf()
                _librarySearchError.value = null
            }
            publishUnifiedState(normalizedQuery)
        }

        searchRequests.tryEmit(
            SearchRequest(
                query = normalizedQuery,
                requestId = requestId,
                platform = _onlineSearchPlatform.value
            )
        )
    }

    private fun clearOnlineResults() {
        if (_onlineSearchResults.value.isNotEmpty()) {
            _onlineSearchResults.value = persistentListOf()
        }
        _onlineSearchLoading.value = false
        _onlineSearchError.value = null
    }

    private fun clearResults() {
        clearOnlineResults()
        _librarySearchResults.value = persistentListOf()
        _librarySearchLoading.value = false
        _librarySearchError.value = null
        lastLibraryQuery = ""
        publishUnifiedState("")
    }

    private fun publishUnifiedState(query: String) {
        _unifiedState.value = UnifiedSearchState(
            query = query,
            onlinePlatform = _onlineSearchPlatform.value,
            libraryResults = _librarySearchResults.value,
            onlineResults = _onlineSearchResults.value,
            libraryLoading = _librarySearchLoading.value,
            onlineLoading = _onlineSearchLoading.value,
            libraryError = _librarySearchError.value,
            onlineError = _onlineSearchError.value,
            popularSearchTerms = popularSearchTerms,
            searchHistory = _searchHistory.value
        )
    }

    fun onCleared() {
        searchJob?.cancel()
        popularSearchJob?.cancel()
        scope = null
    }
}
