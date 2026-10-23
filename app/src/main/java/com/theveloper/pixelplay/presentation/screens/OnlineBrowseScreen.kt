package com.theveloper.pixelplay.presentation.screens

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import com.theveloper.pixelplay.data.model.OnlineBrowsePlatform
import com.theveloper.pixelplay.data.model.RecommendationPage
import com.theveloper.pixelplay.data.model.RecommendationSection
import com.theveloper.pixelplay.data.model.RecommendationSectionKind
import com.theveloper.pixelplay.data.model.RecommendationSongItem
import com.theveloper.pixelplay.data.model.RemoteChartReference
import com.theveloper.pixelplay.data.model.RemotePlaylistReference
import com.theveloper.pixelplay.presentation.components.ExpressiveScrollBar
import com.theveloper.pixelplay.presentation.components.LibraryHeaderHorizontalPadding
import com.theveloper.pixelplay.presentation.components.LibraryHeaderScaffold
import com.theveloper.pixelplay.presentation.components.MiniPlayerHeight
import com.theveloper.pixelplay.presentation.components.ShimmerBox
import com.theveloper.pixelplay.presentation.components.SmartImage
import com.theveloper.pixelplay.presentation.navigation.Screen
import com.theveloper.pixelplay.presentation.navigation.navigateSafely
import com.theveloper.pixelplay.presentation.online.OnlineBrowseViewModel
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel
import com.theveloper.pixelplay.ui.theme.GoogleSansRounded
import com.theveloper.pixelplay.ui.theme.LocalPixelPlayDarkTheme
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineBrowseScreen(
    navController: NavController,
    paddingValues: PaddingValues,
    playerViewModel: PlayerViewModel,
    viewModel: OnlineBrowseViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val dm = LocalPixelPlayDarkTheme.current
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val headerColor = primaryContainer.copy(alpha = 0.4f)
    val gradient = Brush.verticalGradient(
        listOf(
            primaryContainer.copy(alpha = if (dm) 0.5f else 0.25f),
            Color.Transparent
        )
    )

    LibraryHeaderScaffold(
        gradientBrush = gradient,
        headerContainerColor = headerColor,
        searchBarVisible = false,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "在线",
                        modifier = Modifier.padding(start = 8.dp),
                        fontFamily = GoogleSansRounded,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 38.sp,
                        letterSpacing = 1.sp
                    )
                },
                actions = {
                    androidx.compose.material3.IconButton(onClick = { viewModel.refresh(force = true) }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "刷新推荐")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                )
            )
        },
        bottomOverlay = {}
    ) {
        Crossfade(
            targetState = Pair(state.isLoading && state.pages.isEmpty(), state.pages.isEmpty() && state.error != null),
            label = "browse_crossfade"
        ) { (showShimmer, showError) ->
            when {
                showShimmer -> {
                    OnlineBrowseShimmer(paddingValues)
                }
                showError -> {
                    OnlineBrowseError(message = state.error.orEmpty(), onRetry = { viewModel.refresh(true) })
                }
                else -> {
                    RecommendationPageContent(
                        pages = state.pages,
                        loadingPlatforms = state.loadingPlatforms,
                        platformErrors = state.platformErrors,
                        loadingPlayTileKey = state.loadingPlayTileKey,
                        onRetry = { viewModel.refresh(true) },
                        onPlayPlaylist = { playlist, tileKey ->
                            viewModel.playPlaylist(playlist, tileKey, playerViewModel)
                        },
                        onPlayDailyShuffle = {
                            viewModel.playDailyMixShuffled(playerViewModel)
                        },
                        navController = navController,
                        playerViewModel = playerViewModel,
                        paddingValues = paddingValues
                    )
                }
            }
        }
    }
}

@Composable
private fun OnlineBrowseShimmer(paddingValues: PaddingValues) {
    val bottomContentPadding = paddingValues.calculateBottomPadding() + MiniPlayerHeight + 36.dp
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                start = LibraryHeaderHorizontalPadding,
                end = LibraryHeaderHorizontalPadding,
                top = 14.dp,
                bottom = bottomContentPadding
            ),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Action bar shimmer
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ShimmerBox(
                modifier = Modifier
                    .width(130.dp)
                    .height(42.dp)
                    .clip(RoundedCornerShape(26.dp))
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(3) {
                    ShimmerBox(
                        modifier = Modifier
                            .width(50.dp)
                            .height(36.dp)
                            .clip(CircleShape)
                    )
                }
            }
        }

        // Section Title Shimmer
        ShimmerBox(
            modifier = Modifier
                .width(100.dp)
                .height(26.dp)
                .clip(RoundedCornerShape(6.dp))
        )

        // Daily Mix Cards Shimmer (Figure 3 style)
        repeat(2) {
            ShimmerBox(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(78.dp)
                    .clip(AbsoluteSmoothCornerShape(22.dp, 60))
            )
        }

        // Section Title Shimmer
        ShimmerBox(
            modifier = Modifier
                .width(100.dp)
                .height(26.dp)
                .clip(RoundedCornerShape(6.dp))
        )

        // Playlists Grid Shimmer (Figure 2 style)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ShimmerBox(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(0.85f)
                    .clip(RoundedCornerShape(20.dp))
            )
            ShimmerBox(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(0.85f)
                    .clip(RoundedCornerShape(20.dp))
            )
        }
    }
}

private sealed interface RecommendationTile {
    val key: String
}

private data class SectionTile(
    val page: RecommendationPage,
    val section: RecommendationSection
) : RecommendationTile {
    override val key: String = "section_${page.platform.name}_${section.id}"
}

private data class PlaylistTile(
    val page: RecommendationPage,
    val playlist: RemotePlaylistReference
) : RecommendationTile {
    override val key: String = "playlist_${page.platform.name}_${playlist.remoteId}"
}

private data class PendingDailyTile(
    val platform: OnlineBrowsePlatform,
    val failed: Boolean
) : RecommendationTile {
    override val key: String = "daily_pending_${platform.name}"
}

@Composable
private fun RecommendationPageContent(
    pages: List<RecommendationPage>,
    loadingPlatforms: Set<OnlineBrowsePlatform>,
    platformErrors: Map<OnlineBrowsePlatform, String>,
    loadingPlayTileKey: String?,
    onRetry: () -> Unit,
    onPlayPlaylist: (RemotePlaylistReference, String) -> Unit,
    onPlayDailyShuffle: () -> Unit,
    navController: NavController,
    playerViewModel: PlayerViewModel,
    paddingValues: PaddingValues
) {
    var selectedPlatformFilter by rememberSaveable { mutableStateOf<OnlineBrowsePlatform?>(null) }

    val allDailyTiles = buildList<RecommendationTile> {
        pages.forEach { page ->
            page.sections
                .filter { it.kind == RecommendationSectionKind.DAILY_SONGS }
                .filter { it.items.any { item -> item is RecommendationSongItem } }
                .forEach { add(SectionTile(page, it)) }

            // QQ's Daily 30 is returned by Home Feed as a playlist card
            page.sections
                .flatMap { it.items }
                .filterIsInstance<RemotePlaylistReference>()
                .filter(::isQqDaily30)
                .forEach { add(PlaylistTile(page, it)) }
        }
        OnlineBrowsePlatform.entries.forEach { platform ->
            val hasDailyTile = any { tile ->
                when (tile) {
                    is SectionTile -> tile.page.platform == platform
                    is PlaylistTile -> tile.page.platform == platform
                    is PendingDailyTile -> tile.platform == platform
                }
            }
            if (!hasDailyTile && (platform in loadingPlatforms || platform in platformErrors)) {
                add(PendingDailyTile(platform, failed = platform in platformErrors))
            }
        }
    }.distinctBy { it.key }.sortedBy { tile ->
        when (tile) {
            is SectionTile -> tile.page.platform.ordinal
            is PlaylistTile -> tile.page.platform.ordinal
            is PendingDailyTile -> tile.platform.ordinal
        }
    }

    val allPlaylistTiles = buildList<RecommendationTile> {
        OnlineBrowsePlatform.entries.forEach { platform ->
            pages.filter { it.platform == platform }.forEach { page ->
                page.sections
                    .filter { it.kind == RecommendationSectionKind.NEW_SONGS }
                    .filter { it.items.any { item -> item is RecommendationSongItem } }
                    .forEach { add(SectionTile(page, it)) }
            }

            pages.flatMap { page ->
                page.sections.flatMap { section ->
                    section.items
                        .filterIsInstance<RemotePlaylistReference>()
                        .filter { it.platform == platform && !isQqDaily30(it) }
                        .map { PlaylistTile(page, it) }
                }
            }
                .distinctBy { it.playlist.remoteId }
                .take(5)
                .forEach { add(it) }
        }
    }.distinctBy { it.key }.take(10)

    val allCharts = pages.flatMap { page ->
        page.sections.flatMap { section ->
            section.items.filterIsInstance<RemoteChartReference>().map { page to it }
        }
    }.distinctBy { "${it.first.platform.name}_${it.second.remoteId}" }

    // Platform filtering
    val dailyTiles = remember(allDailyTiles, selectedPlatformFilter) {
        if (selectedPlatformFilter == null) allDailyTiles
        else allDailyTiles.filter { tile ->
            when (tile) {
                is SectionTile -> tile.page.platform == selectedPlatformFilter
                is PlaylistTile -> tile.playlist.platform == selectedPlatformFilter
                is PendingDailyTile -> tile.platform == selectedPlatformFilter
            }
        }
    }

    val playlistTiles = remember(allPlaylistTiles, selectedPlatformFilter) {
        if (selectedPlatformFilter == null) allPlaylistTiles
        else allPlaylistTiles.filter { tile ->
            when (tile) {
                is SectionTile -> tile.page.platform == selectedPlatformFilter
                is PlaylistTile -> tile.playlist.platform == selectedPlatformFilter
                is PendingDailyTile -> tile.platform == selectedPlatformFilter
            }
        }
    }

    val charts = remember(allCharts, selectedPlatformFilter) {
        if (selectedPlatformFilter == null) allCharts
        else allCharts.filter { it.first.platform == selectedPlatformFilter }
    }

    val bottomContentPadding = paddingValues.calculateBottomPadding() + MiniPlayerHeight + 36.dp
    val listState = rememberLazyListState()

    Column(modifier = Modifier.fillMaxSize()) {
        // 0. Action & Filter Row (Expressive Action Bar, fixed at top matching LibraryActionRow)
        OnlineActionBar(
            onShuffleClick = onPlayDailyShuffle,
            selectedPlatform = selectedPlatformFilter,
            onPlatformSelect = { selectedPlatformFilter = it },
            modifier = Modifier.padding(
                start = LibraryHeaderHorizontalPadding,
                end = LibraryHeaderHorizontalPadding,
                top = 12.dp,
                bottom = 4.dp
            )
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = LibraryHeaderHorizontalPadding,
                    end = LibraryHeaderHorizontalPadding,
                    top = 8.dp,
                    bottom = bottomContentPadding
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 1. Daily Recommendation (Figure 3 Style Horizontal Cards)
                if (dailyTiles.isNotEmpty()) {
                    item(key = "daily_section_title") {
                        RecommendationSectionTitle("今日推荐")
                    }
                    items(dailyTiles, key = { it.key }) { tile ->
                        DailyMixItemCard(
                            tile = tile,
                            isLoadingPlay = tile.key == loadingPlayTileKey,
                            onClick = {
                                if (tile is PendingDailyTile) onRetry()
                                else navigateToRecommendationTile(navController, tile)
                            },
                            onPlayClick = {
                                when (tile) {
                                    is SectionTile -> {
                                        val songs = tile.section.items.filterIsInstance<RecommendationSongItem>().map { it.transientSong }
                                        if (songs.isNotEmpty()) {
                                            playerViewModel.showAndPlayTransientSong(
                                                song = songs.first(),
                                                contextSongs = songs,
                                                queueName = tile.section.title
                                            )
                                        } else {
                                            navigateToRecommendationTile(navController, tile)
                                        }
                                    }
                                    is PlaylistTile -> {
                                        onPlayPlaylist(tile.playlist, tile.key)
                                    }
                                    is PendingDailyTile -> onRetry()
                                }
                            }
                        )
                    }
                }

                // 2. Playlists 2-Column Grid (Figure 2 Style AlbumGridItemRedesigned)
                if (playlistTiles.isNotEmpty()) {
                    item(key = "playlist_section_title") {
                        RecommendationSectionTitle("推荐歌单")
                    }
                    items(playlistTiles.chunked(2), key = { row -> row.joinToString { it.key } }) { row ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            row.forEach { entry ->
                                RecommendationTileCard(
                                    tile = entry,
                                    playerViewModel = playerViewModel,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    navigateToRecommendationTile(navController, entry)
                                }
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }

                // 3. Ranked Charts (Full-width Horizontal Cards with Top 3)
                if (charts.isNotEmpty()) {
                    item(key = "charts_section_title") {
                        RecommendationSectionTitle("排行榜单")
                    }
                    items(charts, key = { (page, chart) -> "chart_${page.platform.name}_${chart.remoteId}" }) { (page, chart) ->
                        RecommendationChartCard(
                            chart = chart,
                            showPlatformBadge = true,
                            onClick = {
                                navController.navigateSafely(
                                    Screen.OnlineCollection.createRoute(
                                        kind = "chart",
                                        platform = page.platform.name,
                                        id = chart.remoteId,
                                        title = chart.title,
                                        coverUrl = chart.previewArtworkUris.firstOrNull() ?: chart.artworkUri
                                    )
                                )
                            }
                        )
                    }
                }
            }

            ExpressiveScrollBar(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 4.dp, top = 16.dp, bottom = bottomContentPadding),
                listState = listState
            )
        }
    }
}

@Composable
private fun OnlineActionBar(
    onShuffleClick: () -> Unit,
    selectedPlatform: OnlineBrowsePlatform?,
    onPlatformSelect: (OnlineBrowsePlatform?) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilledTonalButton(
            onClick = onShuffleClick,
            shape = RoundedCornerShape(26.dp),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer
            ),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp, pressedElevation = 4.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            modifier = Modifier.height(42.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Shuffle,
                    contentDescription = "今日漫听",
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "今日漫听",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val filterOptions = listOf(
                null to "全部",
                OnlineBrowsePlatform.NETEASE to "网易云",
                OnlineBrowsePlatform.QQ to "QQ"
            )
            filterOptions.forEach { (platform, label) ->
                val isSelected = selectedPlatform == platform
                Surface(
                    shape = CircleShape,
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .height(36.dp)
                        .clip(CircleShape)
                        .clickable { onPlatformSelect(platform) }
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

/**
 * Figure 3 Style Daily Mix Item Card (Modeled after CollapsibleAlbumSectionHeader)
 */
@Composable
private fun DailyMixItemCard(
    tile: RecommendationTile,
    isLoadingPlay: Boolean,
    onClick: () -> Unit,
    onPlayClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val artworkUri = when (tile) {
        is SectionTile -> tile.section.items.filterIsInstance<RecommendationSongItem>().firstOrNull()?.artworkUri
        is PlaylistTile -> tile.playlist.artworkUri
        is PendingDailyTile -> null
    }

    val platform = when (tile) {
        is SectionTile -> tile.page.platform
        is PlaylistTile -> tile.playlist.platform
        is PendingDailyTile -> tile.platform
    }

    val title = when (tile) {
        is SectionTile -> tile.section.title
        is PlaylistTile -> tile.playlist.title
        is PendingDailyTile -> if (tile.platform == OnlineBrowsePlatform.QQ) "每日30首" else "每日推荐"
    }

    val subtitle = when (platform) {
        OnlineBrowsePlatform.QQ -> "QQ 音乐 · 每日为你推荐 30 首精选好歌"
        OnlineBrowsePlatform.NETEASE -> "网易云音乐 · 根据听歌偏好量身定制"
    }

    val cardShape = remember { AbsoluteSmoothCornerShape(22.dp, 60) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(cardShape)
            .clickable(onClick = onClick),
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center
            ) {
                if (!artworkUri.isNullOrBlank()) {
                    SmartImage(
                        model = artworkUri,
                        contentDescription = title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else if (tile is PendingDailyTile && tile.failed) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "重试",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp)
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (tile !is PendingDailyTile) {
                FilledIconButton(
                    onClick = onPlayClick,
                    enabled = !isLoadingPlay,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    modifier = Modifier.size(42.dp)
                ) {
                    if (isLoadingPlay) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = "播放",
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun isQqDaily30(playlist: RemotePlaylistReference): Boolean =
    playlist.platform == OnlineBrowsePlatform.QQ && playlist.title.contains("每日30")

private fun navigateToRecommendationTile(
    navController: NavController,
    tile: RecommendationTile
) {
    when (tile) {
        is PendingDailyTile -> Unit
        is SectionTile -> {
            val cover = tile.section.items.filterIsInstance<RecommendationSongItem>().firstOrNull()?.artworkUri
            navController.navigateSafely(
                Screen.OnlineCollection.createRoute(
                    kind = "section",
                    platform = tile.page.platform.name,
                    id = tile.section.id,
                    title = tile.section.title,
                    coverUrl = cover
                )
            )
        }
        is PlaylistTile -> navController.navigateSafely(
            Screen.OnlineCollection.createRoute(
                kind = "playlist",
                platform = tile.page.platform.name,
                id = tile.playlist.remoteId,
                title = tile.playlist.title,
                coverUrl = tile.playlist.artworkUri
            )
        )
    }
}

/**
 * Figure 2 Style Playlist Grid Card (Modeled after AlbumGridItemRedesigned)
 */
@Composable
private fun RecommendationTileCard(
    tile: RecommendationTile,
    playerViewModel: PlayerViewModel,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val artworkUri = when (tile) {
        is SectionTile -> tile.section.items.filterIsInstance<RecommendationSongItem>().firstOrNull()?.artworkUri
        is PlaylistTile -> tile.playlist.artworkUri
        is PendingDailyTile -> null
    }

    val title = when (tile) {
        is SectionTile -> tile.section.title
        is PlaylistTile -> tile.playlist.title
        is PendingDailyTile -> ""
    }

    val platform = when (tile) {
        is SectionTile -> tile.page.platform
        is PlaylistTile -> tile.playlist.platform
        is PendingDailyTile -> tile.platform
    }

    val songCountText = when (tile) {
        is SectionTile -> "${tile.section.items.size} 首歌曲"
        is PlaylistTile -> if (tile.playlist.songCount > 0) "${tile.playlist.songCount} 首歌曲" else "精选歌单"
        is PendingDailyTile -> ""
    }

    val albumColorSchemePairFlow = remember(artworkUri) {
        playerViewModel.themeStateHolder.getAlbumColorSchemeFlow(artworkUri ?: "")
    }
    val albumColorSchemePair by albumColorSchemePairFlow.collectAsStateWithLifecycle()
    val systemIsDark = LocalPixelPlayDarkTheme.current
    val currentMaterialColorScheme = MaterialTheme.colorScheme

    val itemDesignColorScheme = remember(albumColorSchemePair, systemIsDark, currentMaterialColorScheme) {
        albumColorSchemePair?.let { pair ->
            if (systemIsDark) pair.dark else pair.light
        } ?: currentMaterialColorScheme
    }

    val gradientBaseColor = itemDesignColorScheme.primaryContainer
    val onGradientColor = itemDesignColorScheme.onPrimaryContainer
    val cardShape = RoundedCornerShape(20.dp)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(cardShape)
            .clickable(onClick = onClick),
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = itemDesignColorScheme.surfaceVariant.copy(alpha = 0.3f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .background(color = gradientBaseColor, shape = cardShape)
                .fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 2f)
            ) {
                if (!artworkUri.isNullOrBlank()) {
                    SmartImage(
                        model = artworkUri,
                        contentDescription = title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.MusicNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                // Vertical gradient overlay at bottom of image to blend with gradientBaseColor
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, gradientBaseColor)
                            )
                        )
                )

                // Platform tag at top-start
                PlatformBadge(
                    platform = platform,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(68.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = onGradientColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = songCountText,
                    style = MaterialTheme.typography.bodySmall,
                    color = onGradientColor.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun RecommendationSectionTitle(
    title: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge.copy(
            fontFamily = GoogleSansRounded,
            fontWeight = FontWeight.Bold
        ),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.padding(top = 6.dp, bottom = 2.dp)
    )
}

@Composable
private fun RecommendationChartCard(
    chart: RemoteChartReference,
    modifier: Modifier = Modifier,
    showPlatformBadge: Boolean = true,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Left: Chart Cover Art (90x90 dp)
            Box(
                modifier = Modifier
                    .size(90.dp)
                    .clip(RoundedCornerShape(14.dp))
            ) {
                val coverUrl = chart.previewArtworkUris.firstOrNull() ?: chart.artworkUri
                if (!coverUrl.isNullOrBlank()) {
                    SmartImage(
                        model = coverUrl,
                        contentDescription = chart.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.primaryContainer,
                                        MaterialTheme.colorScheme.secondaryContainer
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.MusicNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // Dark gradient overlay at bottom
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))
                            )
                        )
                )

                Text(
                    text = chart.title,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = GoogleSansRounded,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (showPlatformBadge) {
                    PlatformBadge(
                        platform = chart.platform,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp)
                    )
                }
            }

            // Right: Top 3 Tracks
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (chart.previewTracks.isNotEmpty()) {
                    chart.previewTracks.take(3).forEachIndexed { index, track ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontFamily = GoogleSansRounded,
                                    fontWeight = FontWeight.Black
                                ),
                                color = if (index == 0) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                                modifier = Modifier.width(18.dp)
                            )
                            Text(
                                text = track.title,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.SemiBold
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (track.artist.isNotBlank()) {
                                Text(
                                    text = " - ${track.artist}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = chart.title,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontFamily = GoogleSansRounded,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        Text(
                            text = "点击查看最新榜单热门曲目",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlatformBadge(platform: OnlineBrowsePlatform, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Text(platform.label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun OnlineBrowseError(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(message.ifBlank { "在线内容暂时无法加载" }, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onRetry) { Text("重试") }
    }
}
