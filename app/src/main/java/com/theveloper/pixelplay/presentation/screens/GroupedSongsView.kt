@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.theveloper.pixelplay.presentation.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.model.LibraryTabId
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.model.SongListViewMode
import com.theveloper.pixelplay.data.model.SortOption
import com.theveloper.pixelplay.data.model.StorageFilter
import com.theveloper.pixelplay.presentation.components.ExpressiveScrollBar
import com.theveloper.pixelplay.presentation.components.MiniPlayerHeight
import com.theveloper.pixelplay.presentation.components.SmartImage
import com.theveloper.pixelplay.presentation.components.SmartImageCompactListTargetSize
import com.theveloper.pixelplay.presentation.components.extractFastScrollGlyph
import com.theveloper.pixelplay.presentation.components.subcomps.EnhancedSongListItem
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel
import com.theveloper.pixelplay.ui.theme.LocalShowScrollbar
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape

private sealed interface GroupedSongListItem {
    val key: String

    data class Header(
        override val key: String,
        val title: String,
        val subtitle: String?,
        val artUri: String?,
        val songCount: Int? = null
    ) : GroupedSongListItem

    data class AlbumHeader(
        override val key: String,
        val title: String,
        val artUri: String?,
        val songCount: Int,
        val firstSong: Song?,
        val useArtistStyle: Boolean
    ) : GroupedSongListItem

    data class SongRow(
        override val key: String,
        val song: Song,
        val isFirstInAlbum: Boolean = false,
        val isLastInAlbum: Boolean = false,
        val isLastAlbumInSection: Boolean = false,
        val useArtistStyle: Boolean = false
    ) : GroupedSongListItem

    data class Spacer(
        override val key: String,
        val height: Dp,
        val useSurfaceBackground: Boolean = false
    ) : GroupedSongListItem

    data class Divider(
        override val key: String
    ) : GroupedSongListItem
}

@androidx.annotation.OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun GroupedSongsView(
    songs: List<Song>,
    isLoading: Boolean,
    playerViewModel: PlayerViewModel,
    bottomBarHeight: Dp,
    onMoreOptionsClick: (Song) -> Unit,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    sortOption: SortOption,
    viewMode: SongListViewMode,
    isSelectionMode: Boolean,
    selectedSongIds: Set<String>,
    onSongLongPress: (Song) -> Unit,
    onSongSelectionToggle: (Song) -> Unit,
    getSelectionIndex: (String) -> Int?,
    onLocateCurrentSongVisibilityChanged: (Boolean) -> Unit,
    onRegisterLocateCurrentSongAction: ((() -> Unit)?) -> Unit,
    storageFilter: StorageFilter,
    hasCurrentSong: Boolean,
    artistArtworkByName: Map<String, String?> = emptyMap(),
    queueName: String = "Library"
) {
    val listState = rememberLazyListState()
    val pullToRefreshState = rememberPullToRefreshState()
    val coroutineScope = rememberCoroutineScope()
    val visibilityCallback by rememberUpdatedState(onLocateCurrentSongVisibilityChanged)
    val registerActionCallback by rememberUpdatedState(onRegisterLocateCurrentSongAction)
    val currentSongId by remember(playerViewModel) {
        playerViewModel.stablePlayerState
            .map { it.currentSong?.id }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = null)

    val items = remember(songs, viewMode, sortOption, artistArtworkByName) {
        buildGroupedSongItems(songs, viewMode, sortOption, artistArtworkByName)
    }
    val queue = remember(items) {
        items.filterIsInstance<GroupedSongListItem.SongRow>().map { it.song }
    }
    val currentSongListIndex = remember(items, currentSongId) {
        if (currentSongId == null) {
            -1
        } else {
            items.indexOfFirst { item ->
                item is GroupedSongListItem.SongRow && item.song.id == currentSongId
            }
        }
    }
    val fastScrollLabelProvider = remember(items) {
        { index: Int ->
            when (val item = items.getOrNull(index)) {
                is GroupedSongListItem.Header -> extractFastScrollGlyph(item.title)
                is GroupedSongListItem.AlbumHeader -> extractFastScrollGlyph(item.title)
                is GroupedSongListItem.SongRow -> extractFastScrollGlyph(item.song.title)
                else -> null
            }
        }
    }

    var lastHandledModeKey by remember { mutableStateOf(viewMode.storageKey) }
    LaunchedEffect(viewMode, sortOption) {
        val key = "${viewMode.storageKey}:${sortOption.storageKey}"
        if (key != lastHandledModeKey) {
            lastHandledModeKey = key
            listState.scrollToItem(0)
        }
    }

    val locateCurrentSongAction: (() -> Unit)? = remember(currentSongId, currentSongListIndex) {
        if (currentSongId == null || currentSongListIndex < 0) {
            null
        } else {
            {
                coroutineScope.launch {
                    listState.animateScrollToItem(currentSongListIndex)
                }
            }
        }
    }

    LaunchedEffect(locateCurrentSongAction) {
        registerActionCallback(locateCurrentSongAction)
    }

    LaunchedEffect(currentSongListIndex, items.size, isLoading, currentSongId) {
        if (items.isEmpty() || isLoading || currentSongId == null) {
            visibilityCallback(false)
            return@LaunchedEffect
        }
        if (currentSongListIndex == -1) {
            visibilityCallback(true)
            return@LaunchedEffect
        }
        snapshotFlow {
            val visibleItems = listState.layoutInfo.visibleItemsInfo
            visibleItems.isNotEmpty() &&
                currentSongListIndex in visibleItems.first().index..visibleItems.last().index
        }
            .distinctUntilChanged()
            .collect { isVisible ->
                visibilityCallback(!isVisible)
            }
    }

    DisposableEffect(Unit) {
        onDispose {
            visibilityCallback(false)
            registerActionCallback(null)
        }
    }

    when {
        isLoading && songs.isEmpty() -> {
            LazyColumn(
                modifier = Modifier
                    .padding(start = 12.dp, end = 24.dp, bottom = 6.dp)
                    .clip(
                        RoundedCornerShape(
                            topStart = 26.dp,
                            topEnd = 26.dp,
                            bottomStart = PlayerSheetCollapsedCornerRadius,
                            bottomEnd = PlayerSheetCollapsedCornerRadius
                        )
                    )
                    .fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = bottomBarHeight + MiniPlayerHeight + ListExtraBottomGap)
            ) {
                items(12, key = { "grouped_skeleton_song_$it" }) {
                    EnhancedSongListItem(
                        song = Song.emptySong(),
                        isPlaying = false,
                        isLoading = true,
                        isCurrentSong = false,
                        onMoreOptionsClick = {},
                        onClick = {}
                    )
                }
            }
        }
        songs.isEmpty() -> {
            LibraryExpressiveEmptyState(
                tabId = LibraryTabId.SONGS,
                storageFilter = storageFilter,
                bottomBarHeight = bottomBarHeight
            )
        }
        else -> {
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                state = pullToRefreshState,
                modifier = Modifier.fillMaxSize(),
                indicator = {
                    PullToRefreshDefaults.LoadingIndicator(
                        state = pullToRefreshState,
                        isRefreshing = isRefreshing,
                        modifier = Modifier.align(Alignment.TopCenter)
                    )
                }
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        modifier = Modifier
                            .padding(
                                start = 12.dp,
                                end = if (LocalShowScrollbar.current && (listState.canScrollForward || listState.canScrollBackward)) {
                                    22.dp
                                } else {
                                    12.dp
                                },
                                bottom = 6.dp
                            )
                            .clip(
                                RoundedCornerShape(
                                    topStart = 26.dp,
                                    topEnd = 26.dp,
                                    bottomStart = PlayerSheetCollapsedCornerRadius,
                                    bottomEnd = PlayerSheetCollapsedCornerRadius
                                )
                            ),
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = bottomBarHeight + MiniPlayerHeight + 30.dp)
                    ) {
                        items(
                            items = items,
                            key = { it.key },
                            contentType = {
                                when (it) {
                                    is GroupedSongListItem.Header -> "header"
                                    is GroupedSongListItem.AlbumHeader -> "album_header"
                                    is GroupedSongListItem.SongRow -> "song"
                                    is GroupedSongListItem.Spacer -> "spacer"
                                    is GroupedSongListItem.Divider -> "divider"
                                }
                            }
                        ) { item ->
                            when (item) {
                                is GroupedSongListItem.Header -> {
                                    GroupedArtistHeader(
                                        title = item.title,
                                        artUri = item.artUri
                                    )
                                }
                                is GroupedSongListItem.AlbumHeader -> {
                                    GroupedAlbumSectionHeader(
                                        title = item.title,
                                        artUri = item.artUri,
                                        songCount = item.songCount,
                                        useArtistStyle = item.useArtistStyle,
                                        onPlayClick = {
                                            item.firstSong?.let { song ->
                                                playerViewModel.showAndPlaySong(
                                                    song = song,
                                                    contextSongs = queue,
                                                    queueName = queueName
                                                )
                                            }
                                        }
                                    )
                                }
                                is GroupedSongListItem.SongRow -> {
                                    val song = item.song
                                    GroupedSongRow(
                                        item = item,
                                        playerViewModel = playerViewModel,
                                        isSelected = selectedSongIds.contains(song.id),
                                        isSelectionMode = isSelectionMode,
                                        selectionIndex = if (isSelectionMode) getSelectionIndex(song.id) else null,
                                        onLongPress = { onSongLongPress(song) },
                                        onMoreOptionsClick = onMoreOptionsClick,
                                        onClick = {
                                            if (isSelectionMode) {
                                                onSongSelectionToggle(song)
                                            } else {
                                                playerViewModel.showAndPlaySong(
                                                    song = song,
                                                    contextSongs = queue,
                                                    queueName = queueName
                                                )
                                            }
                                        }
                                    )
                                }
                                is GroupedSongListItem.Spacer -> {
                                    Spacer(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(item.height)
                                            .run {
                                                if (item.useSurfaceBackground) {
                                                    background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f))
                                                } else {
                                                    this
                                                }
                                            }
                                    )
                                }
                                is GroupedSongListItem.Divider -> {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f))
                                            .padding(horizontal = 16.dp, vertical = 8.dp)
                                    ) {
                                        HorizontalDivider(modifier = Modifier)
                                    }
                                }
                            }
                        }
                    }

                    val bottomPadding = if (hasCurrentSong) {
                        bottomBarHeight + MiniPlayerHeight + 16.dp
                    } else {
                        bottomBarHeight + 16.dp
                    }
                    ExpressiveScrollBar(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 4.dp, top = 16.dp, bottom = bottomPadding),
                        listState = listState,
                        dragLabelProvider = fastScrollLabelProvider
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupedArtistHeader(
    title: String,
    artUri: String?
) {
    val headerShape = remember {
        AbsoluteSmoothCornerShape(
            cornerRadiusTR = 24.dp,
            smoothnessAsPercentTR = 60,
            cornerRadiusTL = 24.dp,
            smoothnessAsPercentTL = 60,
            cornerRadiusBR = 0.dp,
            smoothnessAsPercentBR = 0,
            cornerRadiusBL = 0.dp,
            smoothnessAsPercentBL = 0
        )
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f),
        shape = headerShape,
        modifier = Modifier.fillMaxWidth()
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
            ) {
                SmartImage(
                    model = artUri,
                    contentDescription = title,
                    modifier = Modifier.size(48.dp),
                    shape = CircleShape,
                    contentScale = ContentScale.Crop
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun GroupedAlbumSectionHeader(
    title: String,
    artUri: String?,
    songCount: Int,
    useArtistStyle: Boolean,
    onPlayClick: () -> Unit
) {
    val shape = remember(useArtistStyle) {
        if (useArtistStyle) {
            RectangleShape
        } else {
            AbsoluteSmoothCornerShape(
                cornerRadiusTR = 24.dp,
                smoothnessAsPercentTR = 60,
                cornerRadiusTL = 24.dp,
                smoothnessAsPercentTL = 60,
                cornerRadiusBR = 0.dp,
                smoothnessAsPercentBR = 0,
                cornerRadiusBL = 0.dp,
                smoothnessAsPercentBL = 0
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f), shape)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SmartImage(
                model = artUri,
                contentDescription = title,
                targetSize = SmartImageCompactListTargetSize,
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(8.dp),
                contentScale = ContentScale.Crop
            )
            Spacer(Modifier.size(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.library_song_count, songCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onPlayClick,
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = stringResource(R.string.common_play_album)
                )
            }
        }
    }
}

@Composable
private fun GroupedSongRow(
    item: GroupedSongListItem.SongRow,
    playerViewModel: PlayerViewModel,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    selectionIndex: Int?,
    onLongPress: () -> Unit,
    onMoreOptionsClick: (Song) -> Unit,
    onClick: () -> Unit
) {
    val songItemShape = remember(item.isFirstInAlbum, item.isLastInAlbum) {
        when {
            item.isFirstInAlbum && item.isLastInAlbum -> RoundedCornerShape(16.dp)
            item.isFirstInAlbum -> RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = 4.dp,
                bottomEnd = 4.dp
            )
            item.isLastInAlbum -> RoundedCornerShape(
                topStart = 4.dp,
                topEnd = 4.dp,
                bottomStart = 16.dp,
                bottomEnd = 16.dp
            )
            else -> RoundedCornerShape(4.dp)
        }
    }
    val containerShape = remember(item.isLastInAlbum, item.isLastAlbumInSection) {
        if (item.isLastInAlbum && item.isLastAlbumInSection) {
            AbsoluteSmoothCornerShape(
                cornerRadiusTR = 0.dp,
                smoothnessAsPercentTR = 0,
                cornerRadiusTL = 0.dp,
                smoothnessAsPercentTL = 0,
                cornerRadiusBR = 24.dp,
                smoothnessAsPercentBR = 60,
                cornerRadiusBL = 24.dp,
                smoothnessAsPercentBL = 60
            )
        } else {
            RectangleShape
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f), containerShape)
            .padding(horizontal = 8.dp)
            .padding(
                bottom = if (item.isLastInAlbum && !item.isLastAlbumInSection && item.useArtistStyle) {
                    8.dp
                } else {
                    0.dp
                }
            )
    ) {
        Column {
            if (!item.isFirstInAlbum) {
                Spacer(Modifier.height(2.dp))
            }
            LibraryPlaybackAwareSongItem(
                song = item.song,
                playerViewModel = playerViewModel,
                showAlbumArt = false,
                customShape = songItemShape,
                isSelected = isSelected,
                selectionIndex = selectionIndex,
                isSelectionMode = isSelectionMode,
                onLongPress = onLongPress,
                onMoreOptionsClick = onMoreOptionsClick,
                onClick = onClick
            )
            if (item.isLastInAlbum) {
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

private fun buildGroupedSongItems(
    songs: List<Song>,
    viewMode: SongListViewMode,
    sortOption: SortOption,
    artistArtworkByName: Map<String, String?>
): List<GroupedSongListItem> {
    return when (viewMode) {
        SongListViewMode.LIST -> songs.map { song ->
            GroupedSongListItem.SongRow(key = "song_${song.id}", song = song)
        }
        SongListViewMode.GROUP_BY_ARTIST -> buildArtistGroupedItems(songs, sortOption, artistArtworkByName)
        SongListViewMode.GROUP_BY_ALBUM -> buildAlbumGroupedItems(songs, sortOption)
    }
}

private fun buildArtistGroupedItems(
    songs: List<Song>,
    sortOption: SortOption,
    artistArtworkByName: Map<String, String?>
): List<GroupedSongListItem> {
    val groups = songs
        .groupBy { it.displayArtist.ifBlank { "Unknown Artist" } }
        .entries
        .sortedWith(artistGroupComparator(sortOption))

    return buildList {
        groups.forEachIndexed { artistIndex, (artist, artistSongs) ->
            val artistKey = artist.uniqueGroupKey("artist", artistIndex)
            val albums = artistSongs
                .groupBy { it.album.ifBlank { "Unknown Album" } }
                .toSortedMap(compareBy<String> { it.lowercase() })
            val totalSongs = artistSongs.size
            add(
                GroupedSongListItem.Header(
                    key = "${artistKey}_header",
                    title = artist,
                    subtitle = null,
                    artUri = artistArtworkByName[artist.artistLookupKey()],
                    songCount = totalSongs
                )
            )
            albums.entries.forEachIndexed { albumIndex, (album, albumSongs) ->
                val albumKey = album.uniqueGroupKey("${artistKey}_album", albumIndex)
                if (albumIndex > 0) {
                    add(GroupedSongListItem.Divider("${artistKey}_divider_$albumIndex"))
                }
                val sortedAlbumSongs = albumSongs.sortedByTrackOrder()
                add(
                    GroupedSongListItem.AlbumHeader(
                        key = "${albumKey}_header",
                        title = album,
                        artUri = sortedAlbumSongs.firstNotNullOfOrNull { it.albumArtUriString },
                        songCount = sortedAlbumSongs.size,
                        firstSong = sortedAlbumSongs.firstOrNull(),
                        useArtistStyle = true
                    )
                )
                add(
                    GroupedSongListItem.Spacer(
                        key = "${albumKey}_header_spacer",
                        height = 10.dp,
                        useSurfaceBackground = true
                    )
                )
                sortedAlbumSongs.forEachIndexed { songIndex, song ->
                    add(
                        GroupedSongListItem.SongRow(
                            key = "${albumKey}_song_${song.id}",
                            song = song,
                            isFirstInAlbum = songIndex == 0,
                            isLastInAlbum = songIndex == sortedAlbumSongs.lastIndex,
                            isLastAlbumInSection = albumIndex == albums.size - 1,
                            useArtistStyle = true
                        )
                    )
                }
            }
            add(GroupedSongListItem.Spacer(key = "${artistKey}_section_spacer", height = 16.dp))
        }
    }
}

private fun buildAlbumGroupedItems(
    songs: List<Song>,
    sortOption: SortOption
): List<GroupedSongListItem> {
    val groups = songs
        .groupBy { it.album.ifBlank { "Unknown Album" } }
        .entries
        .sortedWith(albumGroupComparator(sortOption))

    return buildList {
        groups.forEachIndexed { albumIndex, (album, albumSongs) ->
            val albumKey = album.uniqueGroupKey("album", albumIndex)
            val sortedAlbumSongs = albumSongs.sortedByTrackOrder()
            add(
                GroupedSongListItem.AlbumHeader(
                    key = "${albumKey}_header",
                    title = album,
                    artUri = sortedAlbumSongs.firstNotNullOfOrNull { it.albumArtUriString },
                    songCount = sortedAlbumSongs.size,
                    firstSong = sortedAlbumSongs.firstOrNull(),
                    useArtistStyle = false
                )
            )
            add(
                GroupedSongListItem.Spacer(
                    key = "${albumKey}_header_spacer",
                    height = 10.dp,
                    useSurfaceBackground = true
                )
            )
            sortedAlbumSongs.forEachIndexed { songIndex, song ->
                add(
                    GroupedSongListItem.SongRow(
                        key = "${albumKey}_song_${song.id}",
                        song = song,
                        isFirstInAlbum = songIndex == 0,
                        isLastInAlbum = songIndex == sortedAlbumSongs.lastIndex,
                        isLastAlbumInSection = true,
                        useArtistStyle = false
                    )
                )
            }
            add(GroupedSongListItem.Spacer(key = "${albumKey}_section_spacer", height = 16.dp))
        }
    }
}

private fun List<Song>.sortedByTrackOrder(): List<Song> =
    sortedWith(
        compareBy<Song> { it.discNumber ?: 1 }
            .thenBy { if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE }
            .thenBy { it.title.lowercase() }
    )

private fun artistGroupComparator(
    sortOption: SortOption
): Comparator<Map.Entry<String, List<Song>>> {
    val nameAsc = compareBy<Map.Entry<String, List<Song>>> { it.key.lowercase() }
    return when (sortOption) {
        SortOption.ArtistNameZA,
        SortOption.SongArtistDesc,
        SortOption.LikedSongArtistDesc -> compareByDescending<Map.Entry<String, List<Song>>> { it.key.lowercase() }

        SortOption.SongDateAddedAsc,
        SortOption.LikedSongDateAddedAsc,
        SortOption.ArtistDateAddedAsc -> compareBy<Map.Entry<String, List<Song>>> { entry ->
            entry.value.maxOfOrNull { it.dateAdded } ?: 0L
        }.then(nameAsc)

        SortOption.SongDateAdded,
        SortOption.LikedSongDateAdded,
        SortOption.ArtistDateAdded -> compareByDescending<Map.Entry<String, List<Song>>> { entry ->
            entry.value.maxOfOrNull { it.dateAdded } ?: 0L
        }.then(nameAsc)

        SortOption.ArtistNumSongsAsc -> compareBy<Map.Entry<String, List<Song>>> { it.value.size }
            .then(nameAsc)

        SortOption.ArtistNumSongsDesc -> compareByDescending<Map.Entry<String, List<Song>>> { it.value.size }
            .then(nameAsc)

        else -> nameAsc
    }
}

private fun albumGroupComparator(
    sortOption: SortOption
): Comparator<Map.Entry<String, List<Song>>> {
    val titleAsc = compareBy<Map.Entry<String, List<Song>>> { it.key.lowercase() }
    return when (sortOption) {
        SortOption.AlbumTitleZA,
        SortOption.SongAlbumDesc,
        SortOption.LikedSongAlbumDesc -> compareByDescending<Map.Entry<String, List<Song>>> { it.key.lowercase() }

        SortOption.AlbumReleaseYearAsc -> compareBy<Map.Entry<String, List<Song>>> { entry ->
            entry.value.maxOfOrNull { it.year } ?: 0
        }.then(titleAsc)

        SortOption.AlbumReleaseYear -> compareByDescending<Map.Entry<String, List<Song>>> { entry ->
            entry.value.maxOfOrNull { it.year } ?: 0
        }.then(titleAsc)

        SortOption.AlbumDateAddedAsc,
        SortOption.SongDateAddedAsc,
        SortOption.LikedSongDateAddedAsc -> compareBy<Map.Entry<String, List<Song>>> { entry ->
            entry.value.maxOfOrNull { it.dateAdded } ?: 0L
        }.then(titleAsc)

        SortOption.AlbumDateAdded,
        SortOption.SongDateAdded,
        SortOption.LikedSongDateAdded -> compareByDescending<Map.Entry<String, List<Song>>> { entry ->
            entry.value.maxOfOrNull { it.dateAdded } ?: 0L
        }.then(titleAsc)

        SortOption.AlbumSizeAsc -> compareBy<Map.Entry<String, List<Song>>> { it.value.size }
            .then(titleAsc)

        SortOption.AlbumSizeDesc -> compareByDescending<Map.Entry<String, List<Song>>> { it.value.size }
            .then(titleAsc)

        else -> titleAsc
    }
}

private fun String.stableGroupKey(): String =
    lowercase()
        .replace(Regex("[^a-z0-9_\\-]+"), "_")
        .trim('_')
        .ifBlank { "unknown" }

private fun String.uniqueGroupKey(prefix: String, index: Int): String =
    "${prefix}_${index}_${stableGroupKey()}"

private fun String.artistLookupKey(): String =
    trim().lowercase()
