package com.theveloper.pixelplay.presentation.online

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.model.OnlineBrowsePlatform
import com.theveloper.pixelplay.data.model.RemoteChartReference
import com.theveloper.pixelplay.data.model.RemotePlaylistDetail
import com.theveloper.pixelplay.data.model.RemotePlaylistReference
import com.theveloper.pixelplay.data.model.TransientOnlineSong
import com.theveloper.pixelplay.data.preferences.PlaylistPreferencesRepository
import com.theveloper.pixelplay.data.recommendation.OnlineRecommendationRepository
import com.theveloper.pixelplay.data.repository.OnlineLibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OnlineCollectionUiState(
    val title: String = "在线内容",
    val platform: OnlineBrowsePlatform = OnlineBrowsePlatform.NETEASE,
    val kind: String = "playlist",
    val coverUrl: String? = null,
    val description: String? = null,
    val songs: List<TransientOnlineSong> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val isSaved: Boolean = false,
    val isSaving: Boolean = false
)

@HiltViewModel
class OnlineCollectionViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: OnlineRecommendationRepository,
    private val onlineLibraryRepository: OnlineLibraryRepository,
    private val playlistPreferencesRepository: PlaylistPreferencesRepository
) : ViewModel() {
    val kind: String = savedStateHandle.get<String>("kind").orEmpty()
    val platform: OnlineBrowsePlatform = runCatching {
        OnlineBrowsePlatform.valueOf(savedStateHandle.get<String>("platform").orEmpty())
    }.getOrDefault(OnlineBrowsePlatform.NETEASE)
    val remoteId: String = savedStateHandle.get<String>("id").orEmpty()
    val initialTitle: String = savedStateHandle.get<String>("title").orEmpty().ifBlank { "在线内容" }
    val initialCoverUrl: String? = savedStateHandle.get<String>("coverUrl")

    val effectiveRemoteId: String = if (kind.isNotBlank() && kind != "playlist") "$kind:$remoteId" else remoteId

    val stablePlaylistId: String = buildString {
        append("saved-online:")
        append(platform.name.lowercase())
        append(':')
        append(effectiveRemoteId)
    }

    private val _uiState = MutableStateFlow(
        OnlineCollectionUiState(
            title = initialTitle,
            platform = platform,
            kind = kind,
            coverUrl = initialCoverUrl?.takeIf { it.isNotBlank() },
            isLoading = true
        )
    )
    val uiState: StateFlow<OnlineCollectionUiState> = _uiState.asStateFlow()

    init {
        observeSavedStatus()
        load()
    }

    private fun observeSavedStatus() {
        viewModelScope.launch {
            playlistPreferencesRepository.userPlaylistsFlow.collectLatest { playlists ->
                val saved = playlists.any { it.id == stablePlaylistId }
                _uiState.update { it.copy(isSaved = saved) }
            }
        }
    }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            when (kind) {
                "section" -> {
                    repository.loadSection(platform, remoteId).fold(
                        onSuccess = { songs ->
                            val resolvedCover = _uiState.value.coverUrl
                                ?: songs.firstOrNull()?.song?.albumArtUriString
                            _uiState.update {
                                it.copy(
                                    songs = songs,
                                    coverUrl = resolvedCover,
                                    isLoading = false
                                )
                            }
                        },
                        onFailure = { error ->
                            _uiState.update { it.copy(isLoading = false, error = error.message) }
                        }
                    )
                }
                "chart" -> {
                    repository.loadChart(RemoteChartReference(platform, remoteId, initialTitle)).fold(
                        onSuccess = { chartDetail ->
                            val resolvedCover = chartDetail.reference.artworkUri
                                ?: chartDetail.reference.previewArtworkUris.firstOrNull()
                                ?: _uiState.value.coverUrl
                                ?: chartDetail.songs.firstOrNull()?.song?.albumArtUriString
                            _uiState.update {
                                it.copy(
                                    songs = chartDetail.songs,
                                    coverUrl = resolvedCover,
                                    isLoading = false
                                )
                            }
                        },
                        onFailure = { error ->
                            _uiState.update { it.copy(isLoading = false, error = error.message) }
                        }
                    )
                }
                else -> {
                    // "playlist"
                    repository.loadPlaylist(RemotePlaylistReference(platform, remoteId, initialTitle)).fold(
                        onSuccess = { playlistDetail ->
                            val resolvedCover = playlistDetail.reference.artworkUri
                                ?: _uiState.value.coverUrl
                                ?: playlistDetail.songs.firstOrNull()?.song?.albumArtUriString
                            _uiState.update {
                                it.copy(
                                    songs = playlistDetail.songs,
                                    coverUrl = resolvedCover,
                                    description = playlistDetail.description,
                                    isLoading = false
                                )
                            }
                        },
                        onFailure = { error ->
                            _uiState.update { it.copy(isLoading = false, error = error.message) }
                        }
                    )
                }
            }
        }
    }

    fun toggleSaveToLibrary(onSuccess: (Boolean) -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            val currentState = _uiState.value
            if (currentState.isSaving) return@launch
            _uiState.update { it.copy(isSaving = true) }

            if (currentState.isSaved) {
                runCatching {
                    playlistPreferencesRepository.deletePlaylist(stablePlaylistId)
                }.fold(
                    onSuccess = {
                        _uiState.update { it.copy(isSaving = false, isSaved = false) }
                        onSuccess(false)
                    },
                    onFailure = { err ->
                        _uiState.update { it.copy(isSaving = false) }
                        onError(err.message ?: "取消收藏失败")
                    }
                )
            } else {
                val detail = RemotePlaylistDetail(
                    reference = RemotePlaylistReference(
                        platform = platform,
                        remoteId = effectiveRemoteId,
                        title = currentState.title,
                        artworkUri = currentState.coverUrl,
                        songCount = currentState.songs.size
                    ),
                    songs = currentState.songs,
                    description = currentState.description
                )
                onlineLibraryRepository.savePlaylist(detail).fold(
                    onSuccess = {
                        _uiState.update { it.copy(isSaving = false, isSaved = true) }
                        onSuccess(true)
                    },
                    onFailure = { err ->
                        _uiState.update { it.copy(isSaving = false) }
                        onError(err.message ?: "收藏歌单失败")
                    }
                )
            }
        }
    }
}
