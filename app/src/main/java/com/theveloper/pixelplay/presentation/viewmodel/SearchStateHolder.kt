package com.theveloper.pixelplay.presentation.viewmodel

import com.theveloper.pixelplay.data.model.SearchFilterType
import com.theveloper.pixelplay.data.model.SearchHistoryItem
import com.theveloper.pixelplay.data.model.SearchMode
import com.theveloper.pixelplay.data.model.OnlineSearchPlatform
import com.theveloper.pixelplay.data.model.OnlineSearchResultItem
import com.theveloper.pixelplay.data.model.SearchResultItem
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.data.search.OnlineSearchRepository
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.FlowPreview

/**
 * Manages search state and operations.
 * Extracted from PlayerViewModel to improve modularity.
 *
 * Responsibilities:
 * - Search query execution
 * - Search filter management
 * - Search history CRUD operations
 */
@Singleton
class SearchStateHolder @Inject constructor(
    private val musicRepository: MusicRepository,
    private val onlineSearchRepository: OnlineSearchRepository,
) {
    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
    }

    private data class SearchRequest(
        val query: String,
        val requestId: Long,
        val mode: SearchMode,
        val platform: OnlineSearchPlatform,
        val filter: SearchFilterType,
    )

    // Search State
    private val _searchResults = MutableStateFlow<ImmutableList<SearchResultItem>>(persistentListOf())
    val searchResults = _searchResults.asStateFlow()

    private val _onlineSearchResults = MutableStateFlow<ImmutableList<OnlineSearchResultItem>>(persistentListOf())
    val onlineSearchResults = _onlineSearchResults.asStateFlow()

    private val _searchMode = MutableStateFlow(SearchMode.ONLINE)
    val searchMode = _searchMode.asStateFlow()

    private val _onlineSearchPlatform = MutableStateFlow(OnlineSearchPlatform.NETEASE)
    val onlineSearchPlatform = _onlineSearchPlatform.asStateFlow()

    private val _onlineSearchLoading = MutableStateFlow(false)
    val onlineSearchLoading = _onlineSearchLoading.asStateFlow()

    private val _onlineSearchError = MutableStateFlow<String?>(null)
    val onlineSearchError = _onlineSearchError.asStateFlow()

    private val _selectedSearchFilter = MutableStateFlow(SearchFilterType.ALL)
    val selectedSearchFilter = _selectedSearchFilter.asStateFlow()

    private val _searchHistory = MutableStateFlow<ImmutableList<SearchHistoryItem>>(persistentListOf())
    val searchHistory = _searchHistory.asStateFlow()

    private val searchRequests = MutableSharedFlow<SearchRequest>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val latestSearchRequestId = AtomicLong(0L)

    private var scope: CoroutineScope? = null
    private var searchJob: Job? = null

    /**
     * Initialize with ViewModel scope.
     */
    fun initialize(scope: CoroutineScope) {
        this.scope = scope
        observeSearchRequests()
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
                        clearResultsForMode(request.mode)
                        return@collectLatest
                    }

                    try {
                        when (request.mode) {
                            SearchMode.LIBRARY -> {
                                _onlineSearchLoading.value = false
                                musicRepository.searchAll(normalizedQuery, request.filter).collect { resultsList ->
                                    val sortedResults = resultsList.sortedWith(
                                        compareBy { result ->
                                            when (result) {
                                                is SearchResultItem.SongItem -> 0
                                                is SearchResultItem.AlbumItem -> 1
                                                is SearchResultItem.ArtistItem -> 2
                                                is SearchResultItem.PlaylistItem -> 3
                                            }
                                        }
                                    )

                                    if (request.requestId != latestSearchRequestId.get()) {
                                        return@collect
                                    }

                                    val immutableResults = sortedResults.toImmutableList()
                                    if (_searchResults.value != immutableResults) {
                                        _searchResults.value = immutableResults
                                    }
                                }
                            }
                            SearchMode.ONLINE -> {
                                _onlineSearchLoading.value = true
                                _onlineSearchError.value = null
                                val result = withContext(Dispatchers.IO) {
                                    onlineSearchRepository.searchSongs(
                                        query = normalizedQuery,
                                        platform = request.platform
                                    )
                                }
                                if (request.requestId != latestSearchRequestId.get()) {
                                    return@collectLatest
                                }
                                result.fold(
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
                                _onlineSearchLoading.value = false
                            }
                        }
                    } catch (_: CancellationException) {
                        // Superseded by a newer query; ignore.
                        if (request.mode == SearchMode.ONLINE) {
                            _onlineSearchLoading.value = false
                        }
                    } catch (e: Exception) {
                        if (request.requestId == latestSearchRequestId.get()) {
                            Timber.e(e, "Error performing search for query: $normalizedQuery")
                            when (request.mode) {
                                SearchMode.LIBRARY -> _searchResults.value = persistentListOf()
                                SearchMode.ONLINE -> {
                                    _onlineSearchResults.value = persistentListOf()
                                    _onlineSearchError.value = e.message
                                    _onlineSearchLoading.value = false
                                }
                            }
                        }
                    }
                }
        }
    }

    fun updateSearchFilter(filterType: SearchFilterType) {
        _selectedSearchFilter.value = filterType
    }

    fun updateSearchMode(mode: SearchMode, query: String) {
        if (_searchMode.value == mode) return
        _searchMode.value = mode
        performSearch(query)
    }

    fun updateOnlineSearchPlatform(platform: OnlineSearchPlatform, query: String) {
        if (_onlineSearchPlatform.value == platform) return
        _onlineSearchPlatform.value = platform
        performSearch(query)
    }

    fun loadSearchHistory(limit: Int = 15) {
        scope?.launch {
            try {
                val history = withContext(Dispatchers.IO) {
                    musicRepository.getRecentSearchHistory(limit)
                }
                _searchHistory.value = history.toImmutableList()
            } catch (e: Exception) {
                Timber.e(e, "Error loading search history")
            }
        }
    }

    fun onSearchQuerySubmitted(query: String) {
        scope?.launch {
            if (query.isNotBlank()) {
                try {
                    withContext(Dispatchers.IO) {
                        musicRepository.addSearchHistoryItem(query)
                    }
                    loadSearchHistory()
                } catch (e: Exception) {
                    Timber.e(e, "Error adding search history item")
                }
            }
        }
    }

    fun performSearch(query: String) {
        val normalizedQuery = query.trim()

        val requestId = latestSearchRequestId.incrementAndGet()

        if (normalizedQuery.isBlank()) {
            clearResultsForMode(_searchMode.value)
        }

        searchRequests.tryEmit(
            SearchRequest(
                query = normalizedQuery,
                requestId = requestId,
                mode = _searchMode.value,
                platform = _onlineSearchPlatform.value,
                filter = _selectedSearchFilter.value
            )
        )
    }

    private fun clearResultsForMode(mode: SearchMode) {
        when (mode) {
            SearchMode.LIBRARY -> {
                if (_searchResults.value.isNotEmpty()) {
                    _searchResults.value = persistentListOf()
                }
            }
            SearchMode.ONLINE -> {
                if (_onlineSearchResults.value.isNotEmpty()) {
                    _onlineSearchResults.value = persistentListOf()
                }
                _onlineSearchLoading.value = false
                _onlineSearchError.value = null
            }
        }
    }

    fun deleteSearchHistoryItem(query: String) {
        scope?.launch {
            try {
                withContext(Dispatchers.IO) {
                    musicRepository.deleteSearchHistoryItemByQuery(query)
                }
                loadSearchHistory()
            } catch (e: Exception) {
                Timber.e(e, "Error deleting search history item")
            }
        }
    }

    fun clearSearchHistory() {
        scope?.launch {
            try {
                withContext(Dispatchers.IO) {
                    musicRepository.clearSearchHistory()
                }
                _searchHistory.value = persistentListOf()
            } catch (e: Exception) {
                Timber.e(e, "Error clearing search history")
            }
        }
    }

    fun onCleared() {
        searchJob?.cancel()
        scope = null
    }
}
