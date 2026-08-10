package com.theveloper.pixelplay.presentation.viewmodel

import com.theveloper.pixelplay.data.model.OnlineSearchPlatform
import com.theveloper.pixelplay.data.model.OnlineSearchResultItem
import com.theveloper.pixelplay.data.model.SearchFilterType
import com.theveloper.pixelplay.data.model.SearchResultItem
import com.theveloper.pixelplay.data.model.StorageFilter
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
    val onlineError: String? = null
)

@Singleton
class SearchStateHolder @Inject constructor(
    private val onlineSearchRepository: OnlineSearchRepository,
    private val musicRepository: MusicRepository,
) {
    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
    }

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
                        clearResults()
                        return@collectLatest
                    }

                    try {
                        _onlineSearchLoading.value = true
                        _onlineSearchError.value = null
                        val needsLibraryRefresh = normalizedQuery != lastLibraryQuery
                        if (needsLibraryRefresh) {
                            _librarySearchLoading.value = true
                            _librarySearchError.value = null
                        }
                        val (onlineResult, libraryResult) = coroutineScope {
                            val online = async(Dispatchers.IO) {
                                onlineSearchRepository.searchSongs(
                                    query = normalizedQuery,
                                    platform = request.platform
                                )
                            }
                            val library = if (needsLibraryRefresh) {
                                async(Dispatchers.IO) {
                                    runCatching {
                                        musicRepository.searchAll(
                                            normalizedQuery,
                                            SearchFilterType.ALL,
                                            StorageFilter.ALL
                                        ).first()
                                    }
                                }
                            } else null
                            online.await() to library?.await()
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
                        libraryResult?.fold(
                            onSuccess = { items ->
                                _librarySearchResults.value = items.toImmutableList()
                                _librarySearchError.value = null
                                lastLibraryQuery = normalizedQuery
                            },
                            onFailure = { error ->
                                _librarySearchResults.value = persistentListOf()
                                _librarySearchError.value = error.message
                            }
                        )
                    } catch (_: CancellationException) {
                        // Superseded by a newer query; ignore.
                    } catch (e: Exception) {
                        if (request.requestId == latestSearchRequestId.get()) {
                            Timber.e(e, "Error performing online search for query: $normalizedQuery")
                            _onlineSearchResults.value = persistentListOf()
                            _onlineSearchError.value = e.message
                            _librarySearchError.value = e.message
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
        performSearch(query)
    }

    fun performSearch(query: String) {
        val normalizedQuery = query.trim()
        val requestId = latestSearchRequestId.incrementAndGet()

        if (normalizedQuery.isBlank()) {
            clearResults()
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
            onlineError = _onlineSearchError.value
        )
    }

    fun onCleared() {
        searchJob?.cancel()
        scope = null
    }
}
