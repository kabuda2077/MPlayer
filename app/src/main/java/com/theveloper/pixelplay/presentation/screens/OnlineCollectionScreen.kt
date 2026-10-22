@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.theveloper.pixelplay.presentation.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.model.OnlineBrowsePlatform
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.model.TransientOnlineSong
import com.theveloper.pixelplay.presentation.components.MiniPlayerHeight
import com.theveloper.pixelplay.presentation.components.OnlineSongActionsSheet
import com.theveloper.pixelplay.presentation.components.PlaylistBottomSheet
import com.theveloper.pixelplay.presentation.components.SmartImage
import com.theveloper.pixelplay.presentation.components.subcomps.EnhancedSongListItem
import com.theveloper.pixelplay.presentation.online.OnlineCollectionViewModel
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel
import com.theveloper.pixelplay.presentation.viewmodel.PlaylistViewModel
import com.theveloper.pixelplay.ui.theme.GoogleSansRounded
import com.theveloper.pixelplay.ui.theme.LocalPixelPlayDarkTheme
import com.theveloper.pixelplay.utils.formatSongCount

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
    val isDarkTheme = LocalPixelPlayDarkTheme.current
    val baseColorScheme = MaterialTheme.colorScheme

    val coverArtUri = state.coverUrl?.takeIf { it.isNotBlank() }
    val albumColorSchemeFlow = remember(coverArtUri) {
        coverArtUri?.let { playerViewModel.themeStateHolder.getAlbumColorSchemeFlow(it, eager = false) }
    }
    val albumColorSchemePair = albumColorSchemeFlow?.collectAsStateWithLifecycle()?.value
    val dynamicColorScheme = remember(albumColorSchemePair, isDarkTheme, baseColorScheme) {
        albumColorSchemePair?.let { pair -> if (isDarkTheme) pair.dark else pair.light } ?: baseColorScheme
    }

    var selectedSongForActions by remember { mutableStateOf<TransientOnlineSong?>(null) }
    var materializedSongForPlaylist by remember { mutableStateOf<Song?>(null) }
    var showDownloadConfirmation by remember { mutableStateOf(false) }
    var isDescriptionExpanded by remember { mutableStateOf(false) }

    val collectionSongs = remember(state.songs) { state.songs.map { it.song } }
    val lazyListState = rememberLazyListState()
    val showCollapsedTitle by remember {
        derivedStateOf {
            lazyListState.firstVisibleItemIndex > 0 || lazyListState.firstVisibleItemScrollOffset > 200
        }
    }

    MaterialTheme(colorScheme = dynamicColorScheme) {
        val gradientBrush = remember(dynamicColorScheme, isDarkTheme) {
            Brush.verticalGradient(
                colors = listOf(
                    dynamicColorScheme.primaryContainer.copy(alpha = if (isDarkTheme) 0.50f else 0.30f),
                    dynamicColorScheme.primaryContainer.copy(alpha = if (isDarkTheme) 0.15f else 0.08f),
                    dynamicColorScheme.surface
                )
            )
        }

        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .background(gradientBrush),
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        AnimatedVisibility(
                            visible = showCollapsedTitle,
                            enter = fadeIn(),
                            exit = fadeOut()
                        ) {
                            Text(
                                text = state.title,
                                fontFamily = GoogleSansRounded,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.common_back)
                            )
                        }
                    },
                    actions = {
                        if (state.songs.isNotEmpty() && state.kind == "playlist") {
                            IconButton(
                                onClick = {
                                    viewModel.toggleSaveToLibrary(
                                        onSuccess = { saved ->
                                            val msg = if (saved) "已收藏至本地歌单" else "已从本地歌单移除"
                                            playerViewModel.sendToast(msg)
                                        },
                                        onError = { err -> playerViewModel.sendToast(err) }
                                    )
                                },
                                enabled = !state.isSaving
                            ) {
                                if (state.isSaving) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                } else {
                                    Icon(
                                        imageVector = if (state.isSaved) Icons.Rounded.Check else Icons.AutoMirrored.Rounded.PlaylistAdd,
                                        contentDescription = if (state.isSaved) "已收藏到本地" else "收藏到本地歌单",
                                        tint = if (state.isSaved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                        if (collectionSongs.isNotEmpty()) {
                            IconButton(onClick = { showDownloadConfirmation = true }) {
                                Icon(
                                    imageVector = Icons.Rounded.Download,
                                    contentDescription = "下载全部歌曲"
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
                    )
                )
            }
        ) { padding ->
            when {
                state.isLoading && state.songs.isEmpty() && state.coverUrl.isNullOrBlank() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
                state.error != null && state.songs.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "加载失败",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = state.error.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton(onClick = viewModel::load) {
                                Text("重试")
                            }
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        state = lazyListState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 4.dp,
                            bottom = MiniPlayerHeight + 96.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Header: Cover Art + Metadata
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(130.dp)
                                        .shadow(elevation = 6.dp, shape = RoundedCornerShape(22.dp))
                                        .clip(RoundedCornerShape(22.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (!state.coverUrl.isNullOrBlank()) {
                                        SmartImage(
                                            model = state.coverUrl,
                                            contentDescription = state.title,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Rounded.MusicNote,
                                            contentDescription = null,
                                            modifier = Modifier.size(52.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }

                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = when (state.platform) {
                                            OnlineBrowsePlatform.NETEASE -> if (isDarkTheme) Color(0xFF4A151B) else Color(0xFFFFEBEE)
                                            OnlineBrowsePlatform.QQ -> if (isDarkTheme) Color(0xFF0F3E30) else Color(0xFFE8F8F3)
                                        }
                                    ) {
                                        Text(
                                            text = when (state.platform) {
                                                OnlineBrowsePlatform.NETEASE -> "网易云音乐"
                                                OnlineBrowsePlatform.QQ -> "QQ 音乐"
                                            },
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = when (state.platform) {
                                                OnlineBrowsePlatform.NETEASE -> if (isDarkTheme) Color(0xFFFF5252) else Color(0xFFD32F2F)
                                                OnlineBrowsePlatform.QQ -> if (isDarkTheme) Color(0xFF2DD8A5) else Color(0xFF00A86B)
                                            }
                                        )
                                    }

                                    Text(
                                        text = state.title,
                                        fontFamily = GoogleSansRounded,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleLarge,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis
                                    )

                                    val songCountText = if (state.songs.isNotEmpty()) {
                                        formatSongCount(state.songs.size)
                                    } else if (state.isLoading) {
                                        "正在加载..."
                                    } else {
                                        ""
                                    }
                                    if (songCountText.isNotBlank()) {
                                        Text(
                                            text = songCountText,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        // Description (if present)
                        if (!state.description.isNullOrBlank()) {
                            item {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { isDescriptionExpanded = !isDescriptionExpanded },
                                    shape = RoundedCornerShape(14.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                                    )
                                ) {
                                    Text(
                                        text = state.description!!,
                                        modifier = Modifier.padding(12.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = if (isDescriptionExpanded) 20 else 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        // Action Buttons: Play All & Shuffle
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
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
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp),
                                    enabled = collectionSongs.isNotEmpty(),
                                    shape = RoundedCornerShape(14.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp)
                                ) {
                                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("顺序播放", maxLines = 1, fontWeight = FontWeight.SemiBold)
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
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp),
                                    enabled = collectionSongs.isNotEmpty(),
                                    shape = RoundedCornerShape(14.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp)
                                ) {
                                    Icon(Icons.Rounded.Shuffle, contentDescription = null, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("随机播放", maxLines = 1, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }

                        // Loading Skeleton Placeholders
                        if (state.isLoading && state.songs.isEmpty()) {
                            items(6) {
                                SongItemSkeleton()
                            }
                        } else {
                            // Song Items
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
            }
        }
    }

    // Batch download confirmation dialog
    if (showDownloadConfirmation) {
        AlertDialog(
            onDismissRequest = { showDownloadConfirmation = false },
            title = {
                Text(
                    text = "下载全部歌曲",
                    fontWeight = FontWeight.Bold,
                    fontFamily = GoogleSansRounded
                )
            },
            text = {
                Text(
                    text = "确定要将该歌单中的全部 ${collectionSongs.size} 首歌曲添加到下载队列吗？",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDownloadConfirmation = false
                        playerViewModel.downloadSelectedSongs(collectionSongs)
                    }
                ) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDownloadConfirmation = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    // Single song more actions bottom sheet
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

    // Playlist picker for single song
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

@Composable
private fun SongItemSkeleton() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(68.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.55f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.35f)
                        .height(10.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                )
            }
        }
    }
}
