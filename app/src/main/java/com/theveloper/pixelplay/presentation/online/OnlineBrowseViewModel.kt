package com.theveloper.pixelplay.presentation.online

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.model.OnlineBrowsePlatform
import com.theveloper.pixelplay.data.model.RecommendationPage
import com.theveloper.pixelplay.data.recommendation.OnlineRecommendationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
            val results = coroutineScope {
                platforms.map { platform ->
                    async { platform to repository.refresh(platform, force) }
                }.awaitAll()
            }
            val pages = results.mapNotNull { (_, result) -> result.getOrNull() }
            val errors = results.mapNotNull { (_, result) -> result.exceptionOrNull()?.message }
            _uiState.value = _uiState.value.copy(
                pages = pages,
                isLoading = false,
                error = if (pages.isEmpty()) errors.firstOrNull() else null
            )
        }
    }
}
