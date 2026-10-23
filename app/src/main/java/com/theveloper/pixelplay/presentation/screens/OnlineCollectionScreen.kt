@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.theveloper.pixelplay.presentation.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.ui.draw.alpha
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
    val density = LocalDensity.current
    val titleScrollThresholdPx = remember(density) {
        with(density) { 180.dp.roundToPx() }
    }
    val isScrolled by remember(titleScrollThresholdPx) {
        derivedStateOf {
            lazyListState.firstVisibleItemIndex > 0 || lazyListState.firstVisibleItemScrollOffset > titleScrollThresholdPx
        }
    }
    val surfaceColor = dynamicColorScheme.surface
    val topBarBgColor by animateColorAsState(
        targetValue = if (isScrolled) surfaceColor.copy(alpha = 0.98f) else surfaceColor.copy(alpha = 0f),
        animationSpec = tween(durationMillis = 200),
        label = "topBarBg"
    )

    MaterialTheme(colorScheme = dynamicColorScheme) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                TopAppBar(
                    title = {
                        AnimatedVisibility(
                            visible = isScrolled,
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
                        FilledTonalIconButton(
                            modifier = Modifier.padding(start = 8.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.88f),
                                contentColor = MaterialTheme.colorScheme.onSurface
                            ),
                            onClick = { navController.popBackStack() }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.common_back)
                            )
                        }
                    },
                    actions = {
                        if (state.songs.isNotEmpty()) {
                            FilledTonalIconButton(
                                modifier = Modifier.padding(end = 4.dp),
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.88f),
                                    contentColor = if (state.isSaved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                ),
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
                                        contentDescription = if (state.isSaved) "已收藏到本地" else "收藏到本地歌单"
                                    )
                                }
                            }
                        }
                        if (collectionSongs.isNotEmpty()) {
                            FilledTonalIconButton(
                                modifier = Modifier.padding(end = 8.dp),
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.88f),
                                    contentColor = MaterialTheme.colorScheme.onSurface
                                ),
                                onClick = { showDownloadConfirmation = true }
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Download,
                                    contentDescription = "下载全部歌曲"
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = topBarBgColor,
                        scrolledContainerColor = topBarBgColor
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
                    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                    LazyColumn(
                        state = lazyListState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            bottom = MiniPlayerHeight + 96.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Hero Header: Full-width cover with downward gradient and scheme 3 layout
                        item(key = "hero_header") {
                            Box(
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                // Full Width Cover Backdrop
                                Box(
                                    modifier = Modifier.matchParentSize()
                                ) {
                                    if (!state.coverUrl.isNullOrBlank()) {
                                        SmartImage(
                                            model = state.coverUrl,
                                            contentDescription = null,
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .alpha(if (isDarkTheme) 0.82f else 0.95f),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(
                                                    Brush.verticalGradient(
                                                        listOf(
                                                            dynamicColorScheme.primaryContainer.copy(alpha = if (isDarkTheme) 0.60f else 0.40f),
                                                            dynamicColorScheme.surface
                                                        )
                                                    )
                                                )
                                        )
                                    }

                                    // Downward Gradient Fade to Surface Color
                                    val surfaceColor = MaterialTheme.colorScheme.surface
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(
                                                Brush.verticalGradient(
                                                    colorStops = arrayOf(
                                                        0.0f to Color.Transparent,
                                                        0.35f to surfaceColor.copy(alpha = 0.20f),
                                                        0.68f to surfaceColor.copy(alpha = 0.80f),
                                                        1.0f to surfaceColor
                                                    )
                                                )
                                            )
                                    )
                                }

                                // Header Content Column
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = statusBarHeight + 56.dp)
                                        .padding(horizontal = 20.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Spacer(Modifier.height(28.dp))

                                    // 1. Eyebrow Tag (Scheme 3 style)
                                    val eyebrow = when {
                                        state.kind == "toplist" -> "TOP CHARTS · 官方榜单"
                                        state.title.contains("每日") -> "DAILY MIX · 每日推荐"
                                        else -> "RECOMMENDED · 精选歌单"
                                    }
                                    Text(
                                        text = eyebrow,
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            letterSpacing = 1.2.sp,
                                            fontFamily = GoogleSansRounded
                                        ),
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )

                                    // 2. Huge Headline Title (Scheme 3 style)
                                    Text(
                                        text = state.title,
                                        fontFamily = GoogleSansRounded,
                                        fontWeight = FontWeight.Black,
                                        style = MaterialTheme.typography.headlineMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )

                                    // 3. Description / Subtitle
                                    if (!state.description.isNullOrBlank()) {
                                        Text(
                                            text = state.description!!,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = if (isDescriptionExpanded) 15 else 2,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.clickable { isDescriptionExpanded = !isDescriptionExpanded }
                                        )
                                    }

                                    Spacer(Modifier.height(4.dp))

                                    // 4. Action Buttons (Play Order & Shuffle capsules)
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 2.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
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
                                            shape = CircleShape,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.primary,
                                                contentColor = MaterialTheme.colorScheme.onPrimary
                                            ),
                                            contentPadding = PaddingValues(horizontal = 16.dp)
                                        ) {
                                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                                            Spacer(Modifier.width(8.dp))
                                            Text("顺序播放", maxLines = 1, fontWeight = FontWeight.Bold)
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
                                            shape = CircleShape,
                                            colors = ButtonDefaults.filledTonalButtonColors(
                                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                                contentColor = MaterialTheme.colorScheme.onSurface
                                            ),
                                            contentPadding = PaddingValues(horizontal = 16.dp)
                                        ) {
                                            Icon(Icons.Rounded.Shuffle, contentDescription = null, modifier = Modifier.size(20.dp))
                                            Spacer(Modifier.width(8.dp))
                                            Text("随机播放", maxLines = 1, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }

                        // Loading Skeleton Placeholders
                        if (state.isLoading && state.songs.isEmpty()) {
                            items(6) {
                                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                                    SongItemSkeleton()
                                }
                            }
                        } else {
                            // Song Items
                            items(state.songs, key = { it.song.id }) { item ->
                                EnhancedSongListItem(
                                    modifier = Modifier.padding(horizontal = 16.dp),
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
