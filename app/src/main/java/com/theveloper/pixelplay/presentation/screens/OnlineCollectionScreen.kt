package com.theveloper.pixelplay.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.theveloper.pixelplay.presentation.components.MiniPlayerHeight
import com.theveloper.pixelplay.presentation.components.OnlineSongActionsSheet
import com.theveloper.pixelplay.presentation.components.PlaylistBottomSheet
import com.theveloper.pixelplay.presentation.components.subcomps.EnhancedSongListItem
import com.theveloper.pixelplay.presentation.online.OnlineCollectionViewModel
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel
import com.theveloper.pixelplay.presentation.viewmodel.PlaylistViewModel
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.model.TransientOnlineSong
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineCollectionScreen(
    navController: NavController,
    playerViewModel: PlayerViewModel,
    viewModel: OnlineCollectionViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val playerState by playerViewModel.stablePlayerState.collectAsStateWithLifecycle()
    val playlistViewModel: PlaylistViewModel = hiltViewModel()
    val playlistUiState by playlistViewModel.uiState.collectAsStateWithLifecycle()
    var selectedSongForActions by remember { mutableStateOf<TransientOnlineSong?>(null) }
    var materializedSongForPlaylist by remember { mutableStateOf<Song?>(null) }
    val collectionSongs = state.songs.map { it.song }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.title, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        when {
            state.isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.error != null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                TextButton(onClick = viewModel::load) { Text("加载失败，点击重试") }
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = MiniPlayerHeight + 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                if (collectionSongs.isNotEmpty()) {
                                    playerViewModel.playSongs(
                                        songsToPlay = collectionSongs,
                                        startSong = collectionSongs.first(),
                                        queueName = state.title
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = collectionSongs.isNotEmpty(),
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = "顺序播放", modifier = Modifier.size(20.dp))
                            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                            Text("顺序播放", maxLines = 1)
                        }
                        FilledTonalButton(
                            onClick = {
                                if (collectionSongs.isNotEmpty()) {
                                    playerViewModel.playSongsShuffled(
                                        songsToPlay = collectionSongs,
                                        queueName = state.title,
                                        startAtZero = true
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = collectionSongs.isNotEmpty(),
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            colors = ButtonDefaults.filledTonalButtonColors()
                        ) {
                            Icon(Icons.Rounded.Shuffle, contentDescription = "随机播放", modifier = Modifier.size(20.dp))
                            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                            Text("随机播放", maxLines = 1)
                        }
                    }
                }
                items(state.songs, key = { it.song.id }) { item ->
                    EnhancedSongListItem(
                        song = item.song,
                        isPlaying = playerState.isPlaying,
                        isCurrentSong = playerState.currentSong?.id == item.song.id,
                        customShape = RoundedCornerShape(18.dp),
                        onClick = {
                            playerViewModel.showAndPlayTransientSong(item, state.songs, state.title)
                        },
                        onMoreOptionsClick = { selectedSongForActions = item }
                    )
                }
            }
        }
    }
    selectedSongForActions?.let { onlineSong ->
        OnlineSongActionsSheet(
            song = onlineSong,
            onDismiss = { selectedSongForActions = null },
            onPlayNext = {
                playerViewModel.addTransientSongNextToQueue(onlineSong)
                selectedSongForActions = null
            },
            onAddToQueue = {
                playerViewModel.addTransientSongToQueue(onlineSong)
                selectedSongForActions = null
            },
            onDownload = {
                playerViewModel.downloadTransientSong(onlineSong)
                selectedSongForActions = null
            },
            onFavorite = {
                playerViewModel.favoriteTransientSong(onlineSong)
                selectedSongForActions = null
            },
            onAddToPlaylist = {
                selectedSongForActions = null
                playerViewModel.materializeTransientSong(onlineSong) { materializedSongForPlaylist = it }
            }
        )
    }
    materializedSongForPlaylist?.let { savedSong ->
        PlaylistBottomSheet(
            playlistUiState = playlistUiState,
            songs = listOf(savedSong),
            onDismiss = { materializedSongForPlaylist = null },
            bottomBarHeight = 0.dp,
            playerViewModel = playerViewModel,
            playlistViewModel = playlistViewModel
        )
    }
}
