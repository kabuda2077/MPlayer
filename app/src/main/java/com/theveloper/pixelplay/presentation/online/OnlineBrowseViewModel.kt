package com.theveloper.pixelplay.presentation.online

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.model.OnlineBrowsePlatform
import com.theveloper.pixelplay.data.model.RecommendationPage
import com.theveloper.pixelplay.data.recommendation.OnlineRecommendationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

data class OnlineBrowseUiState(
    val pages: List<RecommendationPage> = emptyList(),
    val isLoading: Boolean = false,
    val loadingPlatforms: Set<OnlineBrowsePlatform> = emptySet(),
    val platformErrors: Map<OnlineBrowsePlatform, String> = emptyMap(),
    val error: String? = null
)

@HiltViewModel
class OnlineBrowseViewModel @Inject constructor(
    private val repository: OnlineRecommendationRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(OnlineBrowseUiState())
    val uiState: StateFlow<OnlineBrowseUiState> = _uiState.asStateFlow()
    private var loadJob: Job? = null
    private val platforms = OnlineBrowsePlatform.entries.toList()

    init {
        refresh()
    }

    fun refresh(force: Boolean = false) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update {
                it.copy(isLoading = true, loadingPlatforms = platforms.toSet(), platformErrors = emptyMap(), error = null)
            }
            val errors = mutableListOf<String>()
            var successCount = 0
            coroutineScope {
                platforms.map { platform ->
                    launch {
                        val result = repository.refresh(platform, force) { page ->
                            withContext(Dispatchers.Main.immediate) {
                                currentCoroutineContext().ensureActive()
                                publishPage(page)
                            }
                        }
                        currentCoroutineContext().ensureActive()
                        result.fold(
                            onSuccess = { page ->
                                successCount++
                                publishPage(page)
                            },
                            onFailure = {
                                val message = it.message ?: "加载失败"
                                errors += message
                                _uiState.update { state ->
                                    state.copy(platformErrors = state.platformErrors + (platform to message))
                                }
                            }
                        )
                        _uiState.update { it.copy(loadingPlatforms = it.loadingPlatforms - platform) }
                    }
                }.joinAll()
            }
            _uiState.update {
                it.copy(
                    isLoading = false,
                    error = errors.firstOrNull().takeIf { successCount == 0 }
                )
            }
        }
    }

    private fun publishPage(page: RecommendationPage) {
        _uiState.update { state ->
            state.copy(
                pages = (state.pages.filterNot { it.platform == page.platform } + page)
                    .sortedBy { platforms.indexOf(it.platform) }
            )
        }
    }
}
