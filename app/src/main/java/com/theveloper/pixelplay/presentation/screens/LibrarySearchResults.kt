package com.theveloper.pixelplay.presentation.screens

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.model.Album
import com.theveloper.pixelplay.data.model.Artist
import com.theveloper.pixelplay.data.model.Playlist
import com.theveloper.pixelplay.data.model.SearchFilterType
import com.theveloper.pixelplay.data.model.SearchResultItem
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.presentation.components.MiniPlayerHeight
import com.theveloper.pixelplay.presentation.components.PlaylistCover
import com.theveloper.pixelplay.presentation.components.SmartImage
import com.theveloper.pixelplay.presentation.components.SmartImageListTargetSize
import com.theveloper.pixelplay.presentation.components.subcomps.EnhancedSongListItem
import com.theveloper.pixelplay.presentation.navigation.Screen
import com.theveloper.pixelplay.presentation.navigation.navigateSafely
import com.theveloper.pixelplay.presentation.navigation.navigateSafelyReplacing
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel
import com.theveloper.pixelplay.utils.MediaSourceClassifier
import com.theveloper.pixelplay.utils.formatSongCount
import kotlinx.coroutines.launch
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape
import timber.log.Timber

@Composable
fun SearchResultSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp, horizontal = 4.dp)
    )
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun SearchResultsList(
    results: List<SearchResultItem>,
    searchQuery: String,
    playerViewModel: PlayerViewModel,
    onItemSelected: () -> Unit,
    currentPlayingSongId: String?,
    isPlaying: Boolean,
    onSongMoreOptionsClick: (Song) -> Unit,
    navController: NavController,
    isSelectionMode: Boolean = false,
    selectedSongIds: Set<String> = emptySet(),
    getSelectionIndex: (String) -> Int? = { null },
    onSongLongPress: (Song) -> Unit = {},
    selectedAlbums: List<Album> = emptyList(),
    selectedPlaylists: List<Playlist> = emptyList(),
    isAlbumSelectionMode: Boolean = false,
    isPlaylistSelectionMode: Boolean = false,
    onAlbumLongPress: (Album) -> Unit = {},
    onAlbumSelectionToggle: (Album) -> Unit = {},
    getAlbumSelectionIndex: (Long) -> Int? = { null },
    onPlaylistLongPress: (Playlist) -> Unit = {},
    onPlaylistSelectionToggle: (Playlist) -> Unit = {},
    getPlaylistSelectionIndex: (String) -> Int? = { null },
    embedded: Boolean = false
) {
    val localDensity = LocalDensity.current
    val playerStableState by playerViewModel.stablePlayerState.collectAsStateWithLifecycle()

    if (results.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(stringResource(R.string.search_no_results_found), style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    if (embedded) {
        EmbeddedSearchResultsContent(
            results = results,
            searchQuery = searchQuery,
            playerViewModel = playerViewModel,
            onItemSelected = onItemSelected,
            currentPlayingSongId = currentPlayingSongId,
            isPlaying = isPlaying,
            onSongMoreOptionsClick = onSongMoreOptionsClick,
            navController = navController
        )
        return
    }

    val groupedResults = remember(results) {
        results.groupBy { item ->
            when (item) {
                is SearchResultItem.SongItem -> SearchFilterType.SONGS
                is SearchResultItem.AlbumItem -> SearchFilterType.ALBUMS
                is SearchResultItem.ArtistItem -> SearchFilterType.ARTISTS
                is SearchResultItem.PlaylistItem -> SearchFilterType.PLAYLISTS
            }
        }
    }
    val songResultsQueue = remember(groupedResults) {
        buildList {
            groupedResults[SearchFilterType.SONGS]
                ?.forEach { item ->
                    val song = (item as? SearchResultItem.SongItem)?.song ?: return@forEach
                    add(song)
                }
        }
    }
    val searchQueueName = remember(searchQuery) {
        searchQuery.trim()
            .takeIf { it.isNotEmpty() }
            ?.let { "Search: $it" }
            ?: "Search Results"
    }
    val onSongResultClick = remember(playerViewModel, onItemSelected, songResultsQueue, searchQueueName) {
        { song: Song ->
            val playbackQueue = if (songResultsQueue.any { it.id == song.id }) {
                songResultsQueue
            } else {
                listOf(song)
            }
            playerViewModel.showAndPlaySong(song, playbackQueue, searchQueueName)
            onItemSelected()
        }
    }

    val sectionOrder = listOf(
        SearchFilterType.SONGS,
        SearchFilterType.ALBUMS,
        SearchFilterType.ARTISTS,
        SearchFilterType.PLAYLISTS
    )

    val imePadding = WindowInsets.ime.getBottom(localDensity).dp
    val systemBarPaddingBottom = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding() + 94.dp

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .clip(
                RoundedCornerShape(
                    topStart = 28.dp,
                    topEnd = 28.dp
                )
            ),
        contentPadding = PaddingValues(
            top = 8.dp,
            bottom = if (embedded) 8.dp else if (imePadding <= 8.dp) (MiniPlayerHeight + systemBarPaddingBottom) else imePadding
        )
    ) {
        sectionOrder.forEach { filterType ->
            val itemsForSection = groupedResults[filterType] ?: emptyList()

            if (itemsForSection.isNotEmpty()) {
                if (!embedded) {
                item(key = "header_${filterType.name}") {
                    SearchResultSectionHeader(
                        title = when (filterType) {
                            SearchFilterType.SONGS -> "Songs"
                            SearchFilterType.ALBUMS -> "Albums"
                            SearchFilterType.ARTISTS -> "Artists"
                            SearchFilterType.PLAYLISTS -> "Playlists"
                            else -> "Results"
                        }
                    )
                }
                }

                items(
                    count = itemsForSection.size,
                    key = { index ->
                        val item = itemsForSection[index]
                        when (item) {
                            is SearchResultItem.SongItem -> "song_${item.song.id}"
                            is SearchResultItem.AlbumItem -> "album_${item.album.id}"
                            is SearchResultItem.ArtistItem -> "artist_${item.artist.id}"
                            is SearchResultItem.PlaylistItem -> "playlist_${item.playlist.id}_${index}"
                        }
                    },
                    contentType = { index ->
                        when (itemsForSection[index]) {
                            is SearchResultItem.SongItem -> "search_song"
                            is SearchResultItem.AlbumItem -> "search_album"
                            is SearchResultItem.ArtistItem -> "search_artist"
                            is SearchResultItem.PlaylistItem -> "search_playlist"
                        }
                    }
                ) { index ->
                    val item = itemsForSection[index]
                    Box(modifier = Modifier.padding(bottom = 12.dp)) {
                        when (item) {
                            is SearchResultItem.SongItem -> {
                                val isSelected = selectedSongIds.contains(item.song.id)
                                val selectionIndex = getSelectionIndex(item.song.id)
                                EnhancedSongListItem(
                                    song = item.song,
                                    isPlaying = isPlaying,
                                    isCurrentSong = currentPlayingSongId == item.song.id,
                                    onMoreOptionsClick = onSongMoreOptionsClick,
                                    onClick = { onSongResultClick(item.song) },
                                    isSelected = isSelected,
                                    selectionIndex = selectionIndex,
                                    isSelectionMode = isSelectionMode,
                                    sourceLabel = searchSourceLabel(item.song),
                                    onLongPress = { onSongLongPress(item.song) }
                                )
                            }

                            is SearchResultItem.AlbumItem -> {
                                val onPlayClick = remember(item.album, playerViewModel, onItemSelected) {
                                    {
                                        Timber.tag("SearchScreen")
                                            .d("Album clicked: ${item.album.title}")
                                        playerViewModel.playAlbum(item.album)
                                        onItemSelected()
                                    }
                                }
                                val onOpenClick = remember(
                                    item.album,
                                    playerViewModel, onItemSelected
                                ) {
                                    {
                                        navController.navigateSafelyReplacing(
                                            route = Screen.AlbumDetail.createRoute(item.album.id),
                                            patternToPop = Screen.AlbumDetail.route
                                        )
                                        onItemSelected()
                                    }
                                }
                                val isSelected = selectedAlbums.any { it.id == item.album.id }
                                val selectionIndex = getAlbumSelectionIndex(item.album.id)
                                SearchResultAlbumItem(
                                    album = item.album,
                                    onPlayClick = onPlayClick,
                                    onOpenClick = onOpenClick,
                                    isSelected = isSelected,
                                    selectionIndex = selectionIndex,
                                    isSelectionMode = isAlbumSelectionMode,
                                    onLongPress = { onAlbumLongPress(item.album) },
                                    onSelectionToggle = { onAlbumSelectionToggle(item.album) }
                                )
                            }

                            is SearchResultItem.ArtistItem -> {
                                val onPlayClick = remember(item.artist, playerViewModel, onItemSelected) {
                                    {
                                        Timber.tag("SearchScreen")
                                            .d("Artist clicked: ${item.artist.name}")
                                        playerViewModel.playArtist(item.artist)
                                        onItemSelected()
                                    }
                                }
                                val onOpenClick = remember(
                                    item.artist,
                                    playerViewModel, onItemSelected
                                ) {
                                    {
                                        navController.navigateSafelyReplacing(
                                            route = Screen.ArtistDetail.createRoute(item.artist.id),
                                            patternToPop = Screen.ArtistDetail.route
                                        )
                                        onItemSelected()
                                    }
                                }
                                SearchResultArtistItem(
                                    artist = item.artist,
                                    onPlayClick = onPlayClick,
                                    onOpenClick = onOpenClick
                                )
                            }

                            is SearchResultItem.PlaylistItem -> {
                                val playlistSongs by remember(item.playlist.songIds, playerViewModel) {
                                    playerViewModel.observeSongs(item.playlist.songIds)
                                }.collectAsStateWithLifecycle(initialValue = emptyList())
                                val coroutineScope = rememberCoroutineScope()
                                val onPlayClick: () -> Unit = {
                                    coroutineScope.launch {
                                        val songs = playerViewModel.getSongs(item.playlist.songIds)
                                        if (songs.isNotEmpty()) {
                                            playerViewModel.playSongs(
                                                songs,
                                                songs.first(),
                                                item.playlist.name
                                            )
                                            if (playerStableState.isShuffleEnabled) playerViewModel.toggleShuffle()
                                        } else {
                                            playerViewModel.sendToast("Empty playlist")
                                        }
                                        onItemSelected()
                                    }
                                }
                                val onOpenClick = remember(
                                    item.playlist,
                                    playerViewModel, onItemSelected
                                ) {
                                    {
                                        navController.navigateSafely(Screen.PlaylistDetail.createRoute(item.playlist.id))
                                        onItemSelected()
                                    }
                                }
                                val isSelected = selectedPlaylists.any { it.id == item.playlist.id }
                                val selectionIndex = getPlaylistSelectionIndex(item.playlist.id)
                                SearchResultPlaylistItem(
                                    playlist = item.playlist,
                                    playlistSongs = playlistSongs,
                                    onPlayClick = onPlayClick,
                                    onOpenClick = onOpenClick,
                                    isSelected = isSelected,
                                    selectionIndex = selectionIndex,
                                    isSelectionMode = isPlaylistSelectionMode,
                                    onLongPress = { onPlaylistLongPress(item.playlist) },
                                    onSelectionToggle = { onPlaylistSelectionToggle(item.playlist) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Non-scrollable version used when library results are embedded in the unified
 * search list. Keeping the rows as regular Compose content avoids a nested
 * LazyColumn with an independently constrained viewport.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun EmbeddedSearchResultsContent(
    results: List<SearchResultItem>,
    searchQuery: String,
    playerViewModel: PlayerViewModel,
    onItemSelected: () -> Unit,
    currentPlayingSongId: String?,
    isPlaying: Boolean,
    onSongMoreOptionsClick: (Song) -> Unit,
    navController: NavController
) {
    val playerStableState by playerViewModel.stablePlayerState.collectAsStateWithLifecycle()
    val groupedResults = remember(results) {
        results.groupBy { item ->
            when (item) {
                is SearchResultItem.SongItem -> SearchFilterType.SONGS
                is SearchResultItem.AlbumItem -> SearchFilterType.ALBUMS
                is SearchResultItem.ArtistItem -> SearchFilterType.ARTISTS
                is SearchResultItem.PlaylistItem -> SearchFilterType.PLAYLISTS
            }
        }
    }
    val songResultsQueue = remember(groupedResults) {
        groupedResults[SearchFilterType.SONGS].orEmpty().mapNotNull { item ->
            (item as? SearchResultItem.SongItem)?.song
        }
    }
    val searchQueueName = remember(searchQuery) {
        searchQuery.trim()
            .takeIf { it.isNotEmpty() }
            ?.let { "Search: $it" }
            ?: "Search Results"
    }
    val sectionOrder = listOf(
        SearchFilterType.SONGS,
        SearchFilterType.ALBUMS,
        SearchFilterType.ARTISTS,
        SearchFilterType.PLAYLISTS
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
    ) {
        sectionOrder.forEach { filterType ->
            val itemsForSection = groupedResults[filterType].orEmpty()
            itemsForSection.forEach { item ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 4.dp, bottom = 12.dp)
                ) {
                    when (item) {
                        is SearchResultItem.SongItem -> {
                            EnhancedSongListItem(
                                song = item.song,
                                isPlaying = isPlaying,
                                isCurrentSong = currentPlayingSongId == item.song.id,
                                onMoreOptionsClick = onSongMoreOptionsClick,
                                onClick = {
                                    val playbackQueue = if (songResultsQueue.any { it.id == item.song.id }) {
                                        songResultsQueue
                                    } else {
                                        listOf(item.song)
                                    }
                                    playerViewModel.showAndPlaySong(item.song, playbackQueue, searchQueueName)
                                    onItemSelected()
                                },
                                sourceLabel = searchSourceLabel(item.song)
                            )
                        }

                        is SearchResultItem.AlbumItem -> {
                            SearchResultAlbumItem(
                                album = item.album,
                                onPlayClick = {
                                    playerViewModel.playAlbum(item.album)
                                    onItemSelected()
                                },
                                onOpenClick = {
                                    navController.navigateSafelyReplacing(
                                        route = Screen.AlbumDetail.createRoute(item.album.id),
                                        patternToPop = Screen.AlbumDetail.route
                                    )
                                    onItemSelected()
                                }
                            )
                        }

                        is SearchResultItem.ArtistItem -> {
                            SearchResultArtistItem(
                                artist = item.artist,
                                onPlayClick = {
                                    playerViewModel.playArtist(item.artist)
                                    onItemSelected()
                                },
                                onOpenClick = {
                                    navController.navigateSafelyReplacing(
                                        route = Screen.ArtistDetail.createRoute(item.artist.id),
                                        patternToPop = Screen.ArtistDetail.route
                                    )
                                    onItemSelected()
                                }
                            )
                        }

                        is SearchResultItem.PlaylistItem -> {
                            val playlistSongs by remember(item.playlist.songIds, playerViewModel) {
                                playerViewModel.observeSongs(item.playlist.songIds)
                            }.collectAsStateWithLifecycle(initialValue = emptyList())
                            val coroutineScope = rememberCoroutineScope()
                            SearchResultPlaylistItem(
                                playlist = item.playlist,
                                playlistSongs = playlistSongs,
                                onOpenClick = {
                                    navController.navigateSafely(Screen.PlaylistDetail.createRoute(item.playlist.id))
                                    onItemSelected()
                                },
                                onPlayClick = {
                                    coroutineScope.launch {
                                        val songs = playerViewModel.getSongs(item.playlist.songIds)
                                        if (songs.isNotEmpty()) {
                                            playerViewModel.playSongs(songs, songs.first(), item.playlist.name)
                                            if (playerStableState.isShuffleEnabled) playerViewModel.toggleShuffle()
                                        } else {
                                            playerViewModel.sendToast("Empty playlist")
                                        }
                                        onItemSelected()
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchResultAlbumItem(
    album: Album,
    onOpenClick: () -> Unit,
    onPlayClick: () -> Unit,
    isSelected: Boolean = false,
    selectionIndex: Int? = null,
    isSelectionMode: Boolean = false,
    onLongPress: () -> Unit = {},
    onSelectionToggle: () -> Unit = {}
) {
    val itemShape = remember {
        AbsoluteSmoothCornerShape(
            cornerRadiusTL = 26.dp,
            smoothnessAsPercentTR = 60,
            cornerRadiusTR = 26.dp,
            smoothnessAsPercentBR = 60,
            cornerRadiusBR = 26.dp,
            smoothnessAsPercentBL = 60,
            cornerRadiusBL = 26.dp,
            smoothnessAsPercentTL = 60
        )
    }

    val selectionScale by animateFloatAsState(
        targetValue = if (isSelected) 0.98f else 1f,
        animationSpec = tween(durationMillis = 200),
        label = "albumSelectionScale"
    )
    val selectionBorderWidth by animateDpAsState(
        targetValue = if (isSelected) 2.dp else 0.dp,
        animationSpec = tween(durationMillis = 200),
        label = "albumSelectionBorder"
    )

    Card(
        shape = itemShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier
            .fillMaxWidth()
            .scale(selectionScale)
            .then(
                if (isSelected) {
                    Modifier.border(
                        width = selectionBorderWidth,
                        color = MaterialTheme.colorScheme.primary,
                        shape = itemShape
                    )
                } else {
                    Modifier
                }
            )
            .clip(itemShape)
            .combinedClickable(
                onClick = {
                    if (isSelectionMode) {
                        onSelectionToggle()
                    } else {
                        onOpenClick()
                    }
                },
                onLongClick = onLongPress
            )
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SmartImage(
                    model = album.albumArtUriString,
                    contentDescription = "Album Art: ${album.title}",
                    targetSize = SmartImageListTargetSize,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(itemShape)
                )
                Spacer(Modifier.width(12.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = album.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = album.artist,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FilledIconButton(
                    onClick = onPlayClick,
                    modifier = Modifier.size(40.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.8f),
                        contentColor = MaterialTheme.colorScheme.onSecondary
                    )
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = stringResource(R.string.common_play_album), modifier = Modifier.size(24.dp))
                }
            }
            if (isSelectionMode && isSelected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(24.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = selectionIndex?.toString() ?: "✓",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchResultArtistItem(
    artist: Artist,
    onOpenClick: () -> Unit,
    onPlayClick: () -> Unit
) {
    val itemShape = remember {
        AbsoluteSmoothCornerShape(
            cornerRadiusTL = 26.dp,
            smoothnessAsPercentTR = 60,
            cornerRadiusTR = 26.dp,
            smoothnessAsPercentBR = 60,
            cornerRadiusBR = 26.dp,
            smoothnessAsPercentBL = 60,
            cornerRadiusBL = 26.dp,
            smoothnessAsPercentTL = 60
        )
    }

    Card(
        onClick = onOpenClick,
        shape = itemShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!artist.effectiveImageUrl.isNullOrBlank()) {
                SmartImage(
                    model = artist.effectiveImageUrl,
                    contentDescription = "Artist: ${artist.name}",
                    targetSize = SmartImageListTargetSize,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                )
            } else {
                Icon(
                    painter = painterResource(id = R.drawable.rounded_artist_24),
                    contentDescription = "Artist",
                    modifier = Modifier
                        .size(56.dp)
                        .background(MaterialTheme.colorScheme.tertiaryContainer, CircleShape)
                        .padding(12.dp),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = artist.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatSongCount(artist.songCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FilledIconButton(
                onClick = onPlayClick,
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.8f),
                    contentColor = MaterialTheme.colorScheme.onTertiary
                )
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = "Play Artist", modifier = Modifier.size(24.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchResultPlaylistItem(
    playlist: Playlist,
    playlistSongs: List<Song>,
    onOpenClick: () -> Unit,
    onPlayClick: () -> Unit,
    isSelected: Boolean = false,
    selectionIndex: Int? = null,
    isSelectionMode: Boolean = false,
    onLongPress: () -> Unit = {},
    onSelectionToggle: () -> Unit = {}
) {
    val itemShape = remember {
        AbsoluteSmoothCornerShape(
            cornerRadiusTL = 26.dp,
            smoothnessAsPercentTR = 60,
            cornerRadiusTR = 26.dp,
            smoothnessAsPercentBR = 60,
            cornerRadiusBR = 26.dp,
            smoothnessAsPercentBL = 60,
            cornerRadiusBL = 26.dp,
            smoothnessAsPercentTL = 60
        )
    }

    val selectionScale by animateFloatAsState(
        targetValue = if (isSelected) 0.98f else 1f,
        animationSpec = tween(durationMillis = 200),
        label = "playlistSelectionScale"
    )
    val selectionBorderWidth by animateDpAsState(
        targetValue = if (isSelected) 2.dp else 0.dp,
        animationSpec = tween(durationMillis = 200),
        label = "playlistSelectionBorder"
    )

    Card(
        shape = itemShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier
            .fillMaxWidth()
            .scale(selectionScale)
            .then(
                if (isSelected) {
                    Modifier.border(
                        width = selectionBorderWidth,
                        color = MaterialTheme.colorScheme.primary,
                        shape = itemShape
                    )
                } else {
                    Modifier
                }
            )
            .clip(itemShape)
            .combinedClickable(
                onClick = {
                    if (isSelectionMode) {
                        onSelectionToggle()
                    } else {
                        onOpenClick()
                    }
                },
                onLongClick = onLongPress
            )
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PlaylistCover(
                    playlist = playlist,
                    playlistSongs = playlistSongs,
                    size = 56.dp
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = playlist.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = formatSongCount(playlist.songIds.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FilledIconButton(
                    onClick = onPlayClick,
                    modifier = Modifier.size(40.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = "Play Playlist", modifier = Modifier.size(24.dp))
                }
            }
            if (isSelectionMode && isSelected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(24.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = selectionIndex?.toString() ?: "✓",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun searchSourceLabel(song: Song): String {
    return if (MediaSourceClassifier.isLocal(song.contentUriString)) {
        stringResource(R.string.library_storage_filter_offline)
    } else {
        MediaSourceClassifier.displayName(song.contentUriString)
    }
}
