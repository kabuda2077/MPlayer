package com.theveloper.pixelplay.presentation.online

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.model.OnlineBrowsePlatform
import com.theveloper.pixelplay.data.model.RemoteChartReference
import com.theveloper.pixelplay.data.model.RemotePlaylistReference
import com.theveloper.pixelplay.data.model.RecommendationSongItem
import com.theveloper.pixelplay.data.model.TransientOnlineSong
import com.theveloper.pixelplay.data.recommendation.OnlineRecommendationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class OnlineCollectionUiState(
    val title: String = "在线内容",
    val songs: List<TransientOnlineSong> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null
)

@HiltViewModel
class OnlineCollectionViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: OnlineRecommendationRepository
) : ViewModel() {
    private val kind = savedStateHandle.get<String>("kind").orEmpty()
    private val platform = runCatching {
        OnlineBrowsePlatform.valueOf(savedStateHandle.get<String>("platform").orEmpty())
    }.getOrDefault(OnlineBrowsePlatform.NETEASE)
    private val remoteId = savedStateHandle.get<String>("id").orEmpty()
    private val title = savedStateHandle.get<String>("title").orEmpty().ifBlank { "在线内容" }

    private val _uiState = MutableStateFlow(OnlineCollectionUiState(title = title))
    val uiState: StateFlow<OnlineCollectionUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            val result = if (kind == "section") {
                repository.loadSection(platform, remoteId)
            } else if (kind == "chart") {
                repository.loadChart(RemoteChartReference(platform, remoteId, title)).map { it.songs }
            } else {
                repository.loadPlaylist(RemotePlaylistReference(platform, remoteId, title)).map { it.songs }
            }
            result.fold(
                onSuccess = { songs -> _uiState.value = _uiState.value.copy(songs = songs, isLoading = false) },
                onFailure = { error -> _uiState.value = _uiState.value.copy(isLoading = false, error = error.message) }
            )
        }
    }
}
