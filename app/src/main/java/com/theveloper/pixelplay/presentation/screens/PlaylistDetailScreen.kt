package com.theveloper.pixelplay.presentation.screens

import com.theveloper.pixelplay.presentation.navigation.navigateSafely
import com.theveloper.pixelplay.presentation.navigation.navigateSafelyReplacing
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.animation.core.tween
import com.theveloper.pixelplay.presentation.components.subcomps.EnhancedSongListItem
import com.theveloper.pixelplay.ui.theme.LocalPixelPlayDarkTheme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.HapticFeedbackConstantsCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import coil.size.Size
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.presentation.components.MiniPlayerHeight
import com.theveloper.pixelplay.presentation.components.PlaylistBottomSheet
import com.theveloper.pixelplay.presentation.components.QueuePlaylistSongItem
import com.theveloper.pixelplay.presentation.components.SongPickerBottomSheet
import com.theveloper.pixelplay.presentation.components.ExpressiveScrollBar
import com.theveloper.pixelplay.ui.theme.LocalShowScrollbar
import com.theveloper.pixelplay.presentation.components.SmartImage
import com.theveloper.pixelplay.presentation.components.SongInfoBottomSheet
import com.theveloper.pixelplay.presentation.components.resolveNavBarOccupiedHeight
import com.theveloper.pixelplay.presentation.components.subcomps.TightWrapText
import com.theveloper.pixelplay.presentation.navigation.Screen
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel
import com.theveloper.pixelplay.presentation.viewmodel.PlaylistViewModel
import com.theveloper.pixelplay.presentation.viewmodel.PlaylistViewModel.Companion.FOLDER_PLAYLIST_PREFIX
import com.theveloper.pixelplay.presentation.utils.LocalAppHapticsConfig
import com.theveloper.pixelplay.presentation.utils.performAppCompatHapticFeedback
import com.theveloper.pixelplay.ui.theme.GoogleSansRounded
import com.theveloper.pixelplay.presentation.viewmodel.PlaylistSongsOrderMode
import com.theveloper.pixelplay.utils.formatSongCount
import com.theveloper.pixelplay.utils.formatTotalDuration
import com.theveloper.pixelplay.utils.MediaSourceClassifier
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import com.theveloper.pixelplay.presentation.components.LibrarySortBottomSheet
import com.theveloper.pixelplay.data.model.SortOption
import com.theveloper.pixelplay.data.model.PlaylistShapeType
import kotlinx.coroutines.launch

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(
    ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class,
    ExperimentalMaterial3Api::class
)
@Composable
fun PlaylistDetailScreen(
    playlistId: String,
    onBackClick: () -> Unit,
    onDeletePlayListClick: () -> Unit,
    playerViewModel: PlayerViewModel,
    playlistViewModel: PlaylistViewModel = hiltViewModel(),
    navController: NavController
) {
    val uiState by playlistViewModel.uiState.collectAsStateWithLifecycle()
    val playerStableState by playerViewModel.stablePlayerState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val fallbackPlaylistName = stringResource(R.string.common_playlist)
    val sortSongsLabel = stringResource(R.string.playlist_sort_songs_title)
    val moreOptionsLabel = stringResource(R.string.playlist_more_options_title)
    val playItLabel = stringResource(R.string.playlist_action_play_it)
    val shuffleLabel = stringResource(R.string.common_shuffle)
    val addSongsCd = stringResource(R.string.playlist_cd_add_songs)
    val addLabel = stringResource(R.string.playlist_action_add_songs)
    val removeLabel = stringResource(R.string.playlist_action_remove_songs)
    val removeSongsCd = stringResource(R.string.playlist_cd_remove_songs)
    val reorderLabel = stringResource(R.string.playlist_action_reorder_songs)
    val reorderSongsCd = stringResource(R.string.playlist_cd_reorder_songs)
    val reorderSongCd = stringResource(R.string.playlist_cd_reorder_songs)
    val playlistEmptyTitle = stringResource(R.string.playlist_empty_title)
    val playlistEmptyFolder = stringResource(R.string.playlist_empty_folder_label)
    val playlistEmptyAddHint = stringResource(R.string.playlist_empty_add_hint)
    val playlistOptionsTitle = stringResource(R.string.playlist_options_title)
    val editPlaylistLabel = stringResource(R.string.playlist_action_edit_playlist)
    val deletePlaylistLabel = stringResource(R.string.playlist_action_delete_playlist)
    val setDefaultTransitionLabel = stringResource(R.string.playlist_action_set_default_transition)
    val deletePlaylistConfirmTitle = stringResource(R.string.playlist_dialog_delete_title)
    val deletePlaylistConfirmBody = stringResource(R.string.playlist_dialog_delete_body)
    val sortSheetTitle = stringResource(R.string.playlist_sort_songs_title)
    val toastAddedToQueue = stringResource(R.string.library_toast_added_to_queue)
    val toastPlayingNext = stringResource(R.string.library_toast_playing_next)
    val currentPlaylist = uiState.currentPlaylistDetails
    val isFolderPlaylist = currentPlaylist?.id?.startsWith(FOLDER_PLAYLIST_PREFIX) == true
    val isNeteasePlaylist = currentPlaylist?.id?.startsWith("netease_playlist:") == true
    val isQqMusicPlaylist = currentPlaylist?.id?.startsWith("qqmusic_playlist:") == true
    val isCloudPlaylist = isNeteasePlaylist || isQqMusicPlaylist
    val songsInPlaylist = uiState.currentPlaylistSongs
    val hasDownloadableSongs = songsInPlaylist.any { song ->
        MediaSourceClassifier.isDownloadable(song.contentUriString)
    }
    val downloadableSongCount = songsInPlaylist.count { song ->
        MediaSourceClassifier.isDownloadable(song.contentUriString)
    }

    var showAddSongsSheet by remember { mutableStateOf(false) }

    var isReorderModeEnabled by remember { mutableStateOf(false) }
    var isRemoveModeEnabled by remember { mutableStateOf(false) }
    var showSongInfoBottomSheet by remember { mutableStateOf(false) }
    var showEditPlaylistDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var showDownloadPlaylistConfirmation by remember { mutableStateOf(false) }
    val selectedPlaylistSongs = remember { mutableStateMapOf<String, Song>() }
    val selectedSongs = selectedPlaylistSongs.values.toList()
    val isSongSelectionMode = selectedPlaylistSongs.isNotEmpty()

    LaunchedEffect(playlistId) {
        playlistViewModel.loadPlaylistDetails(playlistId)
        selectedPlaylistSongs.clear()
    }

    val selectedSongForInfo by playerViewModel.selectedSongForInfo.collectAsStateWithLifecycle()
    val favoriteIds by playerViewModel.favoriteSongIds.collectAsStateWithLifecycle() // Reintroducir favoriteIds aquí
    val stableOnMoreOptionsClick: (Song) -> Unit = remember {
        { song ->
            playerViewModel.selectSongForInfo(song)
            showSongInfoBottomSheet = true
        }
    }
    val systemNavBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val navBarCompactMode by playerViewModel.navBarCompactMode.collectAsStateWithLifecycle()
    val bottomBarHeightDp = resolveNavBarOccupiedHeight(systemNavBarInset, navBarCompactMode)
    var showPlaylistBottomSheet by remember { mutableStateOf(false) }
    var localReorderableSongs by remember(songsInPlaylist) { mutableStateOf(songsInPlaylist) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val appHapticsConfig = LocalAppHapticsConfig.current
    var lastMovedFrom by remember { mutableStateOf<Int?>(null) }
    var lastMovedTo by remember { mutableStateOf<Int?>(null) }

    val reorderableState = rememberReorderableLazyListState(
        lazyListState = listState,
        onMove = { from, to ->
            val fromIndex = localReorderableSongs.indexOfFirst { it.id == from.key }
            val toIndex = localReorderableSongs.indexOfFirst { it.id == to.key }
            if (fromIndex != -1 && toIndex != -1 && fromIndex != toIndex) {
                localReorderableSongs = localReorderableSongs.toMutableList().apply {
                    add(toIndex, removeAt(fromIndex))
                }
                if (lastMovedFrom == null) {
                    lastMovedFrom = fromIndex
                }
                lastMovedTo = toIndex
            }
        }
    )

    LaunchedEffect(reorderableState.isAnyItemDragging, isFolderPlaylist) {
        if (!isFolderPlaylist && !reorderableState.isAnyItemDragging && lastMovedFrom != null && lastMovedTo != null) {
            currentPlaylist?.let {
                playlistViewModel.reorderSongsInPlaylist(it.id, lastMovedFrom!!, lastMovedTo!!)
            }
            lastMovedFrom = null
            lastMovedTo = null
        } else if (isFolderPlaylist && !reorderableState.isAnyItemDragging) {
            lastMovedFrom = null
            lastMovedTo = null
        }
    }

    val isDarkTheme = LocalPixelPlayDarkTheme.current
    val baseColorScheme = MaterialTheme.colorScheme
    val coverArtUri = remember(currentPlaylist, songsInPlaylist) {
        currentPlaylist?.coverImageUri?.takeIf { it.isNotBlank() }
            ?: songsInPlaylist.firstOrNull { it.albumArtUriString?.isNotBlank() == true }?.albumArtUriString
    }
    val albumColorSchemeFlow = remember(coverArtUri) {
        coverArtUri?.let { playerViewModel.themeStateHolder.getAlbumColorSchemeFlow(it, eager = false) }
    }
    val albumColorSchemePair = albumColorSchemeFlow?.collectAsStateWithLifecycle()?.value
    val dynamicColorScheme = remember(albumColorSchemePair, isDarkTheme, baseColorScheme) {
        albumColorSchemePair?.let { pair -> if (isDarkTheme) pair.dark else pair.light } ?: baseColorScheme
    }

    val density = LocalDensity.current
    val titleScrollThresholdPx = remember(density) {
        with(density) { 180.dp.roundToPx() }
    }
    val isScrolled by remember(titleScrollThresholdPx) {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > titleScrollThresholdPx
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
                                text = currentPlaylist?.name ?: fallbackPlaylistName,
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
                            onClick = onBackClick
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.common_back)
                            )
                        }
                    },
                    actions = {
                        if (isReorderModeEnabled) {
                            TextButton(
                                onClick = { isReorderModeEnabled = false },
                                modifier = Modifier.padding(end = 8.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.common_done),
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else {
                            FilledTonalIconButton(
                                modifier = Modifier.padding(end = if (hasDownloadableSongs) 4.dp else 8.dp),
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.88f),
                                    contentColor = MaterialTheme.colorScheme.onSurface
                                ),
                                onClick = {
                                    playerViewModel.showSortingSheet()
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.Sort,
                                    contentDescription = sortSongsLabel
                                )
                            }
                            if (hasDownloadableSongs) {
                                FilledTonalIconButton(
                                    modifier = Modifier.padding(end = 8.dp),
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.88f),
                                        contentColor = MaterialTheme.colorScheme.onSurface
                                    ),
                                    onClick = { showDownloadPlaylistConfirmation = true }
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Download,
                                        contentDescription = stringResource(R.string.download_action)
                                    )
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = topBarBgColor,
                        scrolledContainerColor = topBarBgColor
                    )
                )
            }
        ) { innerPadding ->
            when {
                uiState.isLoading && currentPlaylist == null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
                uiState.playlistNotFound -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(stringResource(id = R.string.playlist_not_found))
                    }
                }
                currentPlaylist == null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
                else -> {
                    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                    val navBarBottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            bottom = MiniPlayerHeight + navBarBottomPadding + 80.dp
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
                                    if (!coverArtUri.isNullOrBlank()) {
                                        SmartImage(
                                            model = coverArtUri,
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
                                                            currentPlaylist.coverColorArgb?.let { Color(it) }
                                                                ?: dynamicColorScheme.primaryContainer.copy(alpha = if (isDarkTheme) 0.60f else 0.40f),
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

                                    // 1. Eyebrow Tag
                                    val eyebrow = when {
                                        isNeteasePlaylist -> "NETEASE · 网易云歌单"
                                        isQqMusicPlaylist -> "QQ MUSIC · QQ音乐歌单"
                                        isFolderPlaylist -> "FOLDER · 文件夹"
                                        else -> "PLAYLIST · 歌单"
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

                                    // 2. Huge Headline Title
                                    Text(
                                        text = currentPlaylist.name.ifBlank { fallbackPlaylistName },
                                        fontFamily = GoogleSansRounded,
                                        fontWeight = FontWeight.Black,
                                        style = MaterialTheme.typography.headlineMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )

                                    // 3. Subtitle / Info
                                    Text(
                                        text = stringResource(
                                            R.string.playlist_song_duration_line,
                                            formatSongCount(songsInPlaylist.size),
                                            formatTotalDuration(songsInPlaylist)
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )

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
                                                if (localReorderableSongs.isNotEmpty()) {
                                                    playerViewModel.playSongs(
                                                        localReorderableSongs,
                                                        localReorderableSongs.first(),
                                                        currentPlaylist.name
                                                    )
                                                    if (playerStableState.isShuffleEnabled) playerViewModel.toggleShuffle()
                                                }
                                            },
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(48.dp),
                                            enabled = localReorderableSongs.isNotEmpty(),
                                            shape = CircleShape,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.primary,
                                                contentColor = MaterialTheme.colorScheme.onPrimary
                                            ),
                                            contentPadding = PaddingValues(horizontal = 16.dp)
                                        ) {
                                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                                            Spacer(Modifier.width(8.dp))
                                            Text(playItLabel, maxLines = 1, fontWeight = FontWeight.Bold)
                                        }

                                        FilledTonalButton(
                                            onClick = {
                                                if (localReorderableSongs.isNotEmpty()) {
                                                    playerViewModel.playSongsShuffled(
                                                        songsToPlay = localReorderableSongs,
                                                        queueName = currentPlaylist.name,
                                                        playlistId = currentPlaylist.id,
                                                        startAtZero = true
                                                    )
                                                }
                                            },
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(48.dp),
                                            enabled = localReorderableSongs.isNotEmpty(),
                                            shape = CircleShape,
                                            colors = ButtonDefaults.filledTonalButtonColors(
                                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                                contentColor = MaterialTheme.colorScheme.onSurface
                                            ),
                                            contentPadding = PaddingValues(horizontal = 16.dp)
                                        ) {
                                            Icon(Icons.Rounded.Shuffle, contentDescription = null, modifier = Modifier.size(20.dp))
                                            Spacer(Modifier.width(8.dp))
                                            Text(shuffleLabel, maxLines = 1, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }

                        // Selection Action Bar
                        if (!isFolderPlaylist && isSongSelectionMode) {
                            item(key = "selection_bar") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Button(
                                        onClick = {
                                            selectedPlaylistSongs.clear()
                                            localReorderableSongs.forEach { selectedPlaylistSongs[it.id] = it }
                                        },
                                        shape = CircleShape,
                                        contentPadding = PaddingValues(horizontal = 14.dp),
                                        modifier = Modifier.height(40.dp)
                                    ) {
                                        Text(stringResource(R.string.common_select_all))
                                    }

                                    Button(
                                        onClick = { selectedPlaylistSongs.clear() },
                                        shape = CircleShape,
                                        contentPadding = PaddingValues(horizontal = 14.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                            contentColor = MaterialTheme.colorScheme.onSurface
                                        ),
                                        modifier = Modifier.height(40.dp)
                                    ) {
                                        Text(stringResource(R.string.playlist_action_cancel_selection))
                                    }

                                    Button(
                                        onClick = {
                                            playerViewModel.downloadSelectedSongs(selectedSongs)
                                            selectedPlaylistSongs.clear()
                                        },
                                        enabled = selectedSongs.any {
                                            MediaSourceClassifier.isDownloadable(it.contentUriString)
                                        },
                                        shape = CircleShape,
                                        contentPadding = PaddingValues(horizontal = 14.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                                        ),
                                        modifier = Modifier.height(40.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Download,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(stringResource(R.string.download_action))
                                    }
                                }
                            }
                        }

                        if (localReorderableSongs.isEmpty()) {
                            item(key = "empty_state") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 48.dp, horizontal = 24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(
                                            imageVector = Icons.Filled.MusicOff,
                                            contentDescription = null,
                                            modifier = Modifier.size(48.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        Text(playlistEmptyTitle, style = MaterialTheme.typography.titleMedium)
                                        val emptyMessage = if (isFolderPlaylist) {
                                            playlistEmptyFolder
                                        } else {
                                            playlistEmptyAddHint
                                        }
                                        Text(
                                            text = emptyMessage,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (!isFolderPlaylist && !isCloudPlaylist) {
                                            Spacer(Modifier.height(16.dp))
                                            Button(onClick = { showAddSongsSheet = true }) {
                                                Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                                                Spacer(Modifier.width(6.dp))
                                                Text(addLabel)
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            itemsIndexed(
                                localReorderableSongs,
                                key = { _, item -> item.id },
                                contentType = { _, _ -> "playlist_song" }
                            ) { _, song ->
                                ReorderableItem(
                                    state = reorderableState,
                                    key = song.id
                                ) { isDragging ->
                                    val scale by animateFloatAsState(
                                        if (isDragging) 1.04f else 1f,
                                        label = "scale"
                                    )
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp)
                                            .graphicsLayer {
                                                scaleX = scale
                                                scaleY = scale
                                            },
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (isReorderModeEnabled) {
                                            IconButton(
                                                onClick = {},
                                                modifier = Modifier
                                                    .draggableHandle(
                                                        onDragStarted = {
                                                            performAppCompatHapticFeedback(
                                                                view,
                                                                appHapticsConfig,
                                                                HapticFeedbackConstantsCompat.GESTURE_START
                                                            )
                                                        },
                                                        onDragStopped = {
                                                            performAppCompatHapticFeedback(
                                                                view,
                                                                appHapticsConfig,
                                                                HapticFeedbackConstantsCompat.GESTURE_END
                                                            )
                                                        }
                                                    )
                                                    .size(40.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.DragIndicator,
                                                    contentDescription = reorderSongCd,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                        EnhancedSongListItem(
                                            modifier = Modifier.weight(1f),
                                            song = song,
                                            isPlaying = playerStableState.isPlaying,
                                            isCurrentSong = playerStableState.currentSong?.id == song.id,
                                            isSelected = selectedPlaylistSongs.containsKey(song.id),
                                            isSelectionMode = isSongSelectionMode,
                                            customShape = RoundedCornerShape(18.dp),
                                            onClick = {
                                                if (isSongSelectionMode) {
                                                    if (selectedPlaylistSongs.containsKey(song.id)) {
                                                        selectedPlaylistSongs.remove(song.id)
                                                    } else {
                                                        selectedPlaylistSongs[song.id] = song
                                                    }
                                                } else {
                                                    playerViewModel.playSongs(
                                                        localReorderableSongs,
                                                        song,
                                                        currentPlaylist.name,
                                                        currentPlaylist.id
                                                    )
                                                }
                                            },
                                            onLongPress = {
                                                if (!isFolderPlaylist && !isReorderModeEnabled) {
                                                    if (selectedPlaylistSongs.containsKey(song.id)) {
                                                        selectedPlaylistSongs.remove(song.id)
                                                    } else {
                                                        selectedPlaylistSongs[song.id] = song
                                                    }
                                                }
                                            },
                                            onMoreOptionsClick = stableOnMoreOptionsClick
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

    if (showAddSongsSheet && currentPlaylist != null && !isFolderPlaylist) {
        SongPickerBottomSheet(
            initiallySelectedSongIds = currentPlaylist.songIds.toSet(),
            onDismiss = { showAddSongsSheet = false },
            onConfirm = { selectedIds ->
                playlistViewModel.addSongsToPlaylist(currentPlaylist.id, selectedIds.toList())
                showAddSongsSheet = false
            }
        )
    }
    
    if (showEditPlaylistDialog && currentPlaylist != null) {
        val initialShapeType = try {
            currentPlaylist.coverShapeType?.let { PlaylistShapeType.valueOf(it) } ?: PlaylistShapeType.Circle
        } catch (e: Exception) {
            PlaylistShapeType.Circle
        }
        
        EditPlaylistDialog(
            visible = showEditPlaylistDialog,
            currentName = currentPlaylist.name,
            currentImageUri = currentPlaylist.coverImageUri,
            currentColor = currentPlaylist.coverColorArgb,
            currentIconName = currentPlaylist.coverIconName,
            currentShapeType = initialShapeType,
            currentShapeDetail1 = currentPlaylist.coverShapeDetail1,
            currentShapeDetail2 = currentPlaylist.coverShapeDetail2,
            currentShapeDetail3 = currentPlaylist.coverShapeDetail3,
            currentShapeDetail4 = currentPlaylist.coverShapeDetail4,
            onDismiss = { showEditPlaylistDialog = false },
            onSave = { name, imageUri, color, icon, scale, panX, panY, shapeType, d1, d2, d3, d4 ->
                playlistViewModel.updatePlaylistParameters(
                    playlistId = currentPlaylist.id,
                    name = name,
                    coverImageUri = imageUri,
                    coverColor = color,
                    coverIcon = icon,
                    cropScale = scale,
                    cropPanX = panX,
                    cropPanY = panY,
                    coverShapeType = shapeType,
                    coverShapeDetail1 = d1,
                    coverShapeDetail2 = d2,
                    coverShapeDetail3 = d3,
                    coverShapeDetail4 = d4
                )
                showEditPlaylistDialog = false
            }
        )
    }
    if (showDeleteConfirmation && currentPlaylist != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text(deletePlaylistConfirmTitle) },
            text = {
                Text(deletePlaylistConfirmBody)
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        playlistViewModel.deletePlaylist(currentPlaylist.id)
                        onDeletePlayListClick()
                        showDeleteConfirmation = false
                    }
                ) {
                    Text(stringResource(R.string.common_delete), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text(stringResource(R.string.common_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        )
    }

    if (showDownloadPlaylistConfirmation && currentPlaylist != null) {
        AlertDialog(
            onDismissRequest = { showDownloadPlaylistConfirmation = false },
            title = { Text(stringResource(R.string.playlist_download_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.playlist_download_confirm_body,
                        downloadableSongCount
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDownloadPlaylistConfirmation = false
                        playerViewModel.downloadSelectedSongs(localReorderableSongs)
                    },
                    enabled = downloadableSongCount > 0
                ) {
                    Text(
                        stringResource(R.string.playlist_download_confirm_action),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDownloadPlaylistConfirmation = false }) {
                    Text(stringResource(R.string.common_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        )
    }

    if (showSongInfoBottomSheet && selectedSongForInfo != null) {
        val currentSong = selectedSongForInfo
        val isFavorite = remember(currentSong?.id, favoriteIds) {
            derivedStateOf {
                currentSong?.let {
                    favoriteIds.contains(
                        it.id
                    )
                }
            }
        }.value ?: false

        if (currentSong != null) {
            SongInfoBottomSheet(
                song = currentSong,
                isFavorite = isFavorite,
                onToggleFavorite = {
                    // Directly use PlayerViewModel's method to toggle, which should handle UserPreferencesRepository
                    playerViewModel.toggleFavoriteSpecificSong(currentSong) // Assumes such a method exists or will be added to PlayerViewModel
                },
                onDismiss = { showSongInfoBottomSheet = false },
                onPlaySong = {
                    playerViewModel.showAndPlaySong(currentSong)
                },
                onAddToQueue = {
                    playerViewModel.addSongToQueue(currentSong) // Assumes such a method exists or will be added
                    playerViewModel.sendToast(toastAddedToQueue)
                },
                onAddNextToQueue = {
                    playerViewModel.addSongNextToQueue(currentSong)
                    playerViewModel.sendToast(toastPlayingNext)
                },
                onAddToPlayList = {
                    showPlaylistBottomSheet = true;
                },
                onDownload = {
                    playerViewModel.downloadSelectedSongs(listOf(currentSong))
                },
                onDeleteFromDevice = playerViewModel::deleteFromDevice,
                onNavigateToAlbum = {
                    navController.navigateSafelyReplacing(
                        route = Screen.AlbumDetail.createRoute(currentSong.albumId),
                        patternToPop = Screen.AlbumDetail.route
                    )
                    showSongInfoBottomSheet = false
                },
                onNavigateToArtist = {
                    navController.navigateSafelyReplacing(
                        route = Screen.ArtistDetail.createRoute(currentSong.artistId),
                        patternToPop = Screen.ArtistDetail.route
                    )
                    showSongInfoBottomSheet = false
                },
                onNavigateToArtistById = { artistId ->
                    navController.navigateSafelyReplacing(
                        route = Screen.ArtistDetail.createRoute(artistId),
                        patternToPop = Screen.ArtistDetail.route
                    )
                    showSongInfoBottomSheet = false
                },
                onNavigateToGenre = {},
                onEditSong = { newTitle, newArtist, newAlbum, newAlbumArtist, newComposer, newGenre, newLyrics, newTrackNumber, newDiscNumber, replayGainTrackGainDb, replayGainAlbumGainDb, coverArtUpdate ->
                    playerViewModel.editSongMetadata(
                        currentSong,
                        newTitle,
                        newArtist,
                        newAlbum,
                        newAlbumArtist,
                        newComposer,
                        newGenre,
                        newLyrics,
                        newTrackNumber,
                        newDiscNumber,
                        replayGainTrackGainDb,
                        replayGainAlbumGainDb,
                        coverArtUpdate
                    )
                },
                generateAiMetadata = { fields ->
                    playerViewModel.generateAiMetadata(currentSong, fields)
                },
                removeFromListTrigger = {
                    playlistViewModel.removeSongFromPlaylist(playlistId, currentSong.id)
                }
            )
            if (showPlaylistBottomSheet) {
                val playlistUiState by playlistViewModel.uiState.collectAsStateWithLifecycle()

                PlaylistBottomSheet(
                    playlistUiState = playlistUiState,
                    songs = listOf(currentSong),
                    onDismiss = {
                        showPlaylistBottomSheet = false
                    },
                    currentPlaylistId = playlistId,
                    bottomBarHeight = bottomBarHeightDp,
                    playerViewModel = playerViewModel,
                )
            }
        }
    }

    val isSortSheetVisible by playerViewModel.isSortingSheetVisible.collectAsStateWithLifecycle()

    if (isSortSheetVisible) {
        // Check if playlist is in Manual mode (which corresponds to Default Order)
        val isManualMode = uiState.playlistSongsOrderMode is PlaylistSongsOrderMode.Manual
        val rawOption = uiState.currentPlaylistSongsSortOption
        // If in Manual mode, show SongDefaultOrder as selected; otherwise use the stored sort option
        val currentSortOption = if (isManualMode) {
            SortOption.SongDefaultOrder
        } else if (currentPlaylist != null) {
            rawOption
        } else {
            SortOption.SongTitleAZ
        }

        // Build options list inline to avoid potential static initialization issues
        val songSortOptions = listOf(
            SortOption.SongDefaultOrder,
            SortOption.SongTitleAZ,
            SortOption.SongTitleZA,
            SortOption.SongArtist,
            SortOption.SongArtistDesc,
            SortOption.SongAlbum,
            SortOption.SongAlbumDesc,
            SortOption.SongDateAdded,
            SortOption.SongDateAddedAsc,
            SortOption.SongDuration,
            SortOption.SongDurationAsc
        )

        LibrarySortBottomSheet(
            title = sortSheetTitle,
            options = songSortOptions,
            selectedOption = currentSortOption,
            onDismiss = { playerViewModel.hideSortingSheet() },
            onOptionSelected = { option ->
                 playlistViewModel.sortPlaylistSongs(option)
                 playerViewModel.hideSortingSheet()
                 // Auto-scroll to first item after sorting (delay to allow list to update)
                 scope.launch {
                     kotlinx.coroutines.delay(100)
                     listState.animateScrollToItem(0)
                 }
            },
            onDirectionToggle = { option ->
                playlistViewModel.sortPlaylistSongs(option)
                scope.launch {
                    kotlinx.coroutines.delay(100)
                    listState.animateScrollToItem(0)
                }
            },
            showViewToggle = false 
        )
    }
    }
}
