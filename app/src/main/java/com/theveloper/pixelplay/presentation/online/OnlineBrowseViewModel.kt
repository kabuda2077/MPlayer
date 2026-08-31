package com.theveloper.pixelplay.presentation.online

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.model.OnlineBrowsePlatform
import com.theveloper.pixelplay.data.model.RecommendationPage
import com.theveloper.pixelplay.data.recommendation.OnlineRecommendationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

data class OnlineBrowseUiState(
    val pages: List<RecommendationPage> = emptyList(),
    val isLoading: Boolean = false,
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
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            val errors = mutableListOf<String>()
            var successCount = 0
            coroutineScope {
                platforms.map { platform ->
                    launch {
                        repository.refresh(platform, force).fold(
                            onSuccess = { page ->
                                successCount++
                                _uiState.update { state ->
                                    state.copy(
                                        pages = (state.pages.filterNot { it.platform == platform } + page)
                                            .sortedBy { platforms.indexOf(it.platform) }
                                    )
                                }
                            },
                            onFailure = { errors += it.message ?: "加载失败" }
                        )
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
}
