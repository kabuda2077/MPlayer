package com.theveloper.pixelplay.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.netease.NeteaseRepository
import com.theveloper.pixelplay.data.qqmusic.QqMusicRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ExternalServiceAccount {
    NETEASE,
    QQ_MUSIC
}

data class ExternalAccountUiModel(
    val service: ExternalServiceAccount,
    val title: String,
    val accountLabel: String,
    val syncedContentLabel: String,
    val isLoggingOut: Boolean
)

data class AccountsUiState(
    val connectedAccounts: List<ExternalAccountUiModel> = emptyList(),
    val disconnectedServices: List<ExternalServiceAccount> = emptyList()
)

@HiltViewModel
class AccountsViewModel @Inject constructor(
    private val neteaseRepository: NeteaseRepository,
    private val qqMusicRepository: QqMusicRepository
) : ViewModel() {

    private val loggingOutServices = MutableStateFlow<Set<ExternalServiceAccount>>(emptySet())

    private val neteaseStateFlow = combine(
        neteaseRepository.isLoggedInFlow,
        neteaseRepository.getPlaylists().map { it.size }
    ) { connected, playlistCount ->
        connected to playlistCount
    }

    private val qqMusicStateFlow = combine(
        qqMusicRepository.isLoggedInFlow,
        qqMusicRepository.getPlaylists().map { it.size }
    ) { connected, playlistCount ->
        connected to playlistCount
    }

    val uiState: StateFlow<AccountsUiState> = combine(
        neteaseStateFlow,
        qqMusicStateFlow,
        loggingOutServices
    ) { neteaseState, qqState, activeLogouts ->
        val (neteaseConnected, neteasePlaylistCount) = neteaseState
        val (qqConnected, qqPlaylistCount) = qqState

        val connectedAccounts = buildList {
            if (neteaseConnected) {
                add(
                    ExternalAccountUiModel(
                        service = ExternalServiceAccount.NETEASE,
                        title = "Netease Music",
                        accountLabel = neteaseRepository.userNickname
                            ?.takeIf { it.isNotBlank() }
                            ?: "Netease account connected",
                        syncedContentLabel = formatCount(
                            count = neteasePlaylistCount,
                            singular = "synced playlist",
                            plural = "synced playlists"
                        ),
                        isLoggingOut = ExternalServiceAccount.NETEASE in activeLogouts
                    )
                )
            }
            if (qqConnected) {
                add(
                    ExternalAccountUiModel(
                        service = ExternalServiceAccount.QQ_MUSIC,
                        title = "QQ Music",
                        accountLabel = qqMusicRepository.userNickname
                            ?.takeIf { it.isNotBlank() }
                            ?: "QQ Music account connected",
                        syncedContentLabel = formatCount(
                            count = qqPlaylistCount,
                            singular = "synced playlist",
                            plural = "synced playlists"
                        ),
                        isLoggingOut = ExternalServiceAccount.QQ_MUSIC in activeLogouts
                    )
                )
            }
        }

        val disconnectedServices = buildList {
            if (!neteaseConnected) add(ExternalServiceAccount.NETEASE)
            if (!qqConnected) add(ExternalServiceAccount.QQ_MUSIC)
        }

        AccountsUiState(
            connectedAccounts = connectedAccounts,
            disconnectedServices = disconnectedServices
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountsUiState())

    fun logout(service: ExternalServiceAccount) {
        if (service in loggingOutServices.value) return

        viewModelScope.launch {
            loggingOutServices.update { it + service }
            try {
                runCatching {
                    when (service) {
                        ExternalServiceAccount.NETEASE -> neteaseRepository.logout()
                        ExternalServiceAccount.QQ_MUSIC -> qqMusicRepository.logout()
                    }
                }
            } finally {
                loggingOutServices.update { it - service }
            }
        }
    }

    private fun formatCount(count: Int, singular: String, plural: String): String {
        return if (count == 1) {
            "1 $singular"
        } else {
            "$count $plural"
        }
    }
}
