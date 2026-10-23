package com.theveloper.pixelplay.presentation.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
        afterSearchBar = {
            val tabs = listOf("全部", "网易云", "QQ 音乐")
            val selectedIndex = when (state.selectedPlatform) {
                null -> 0
                OnlineBrowsePlatform.NETEASE -> 1
                OnlineBrowsePlatform.QQ -> 2
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = LibraryHeaderHorizontalPadding,
                        top = 0.dp,
                        end = LibraryHeaderHorizontalPadding,
                        bottom = 8.dp
                    ),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                tabs.forEachIndexed { index, title ->
                    TabAnimation(
                        modifier = Modifier.weight(1f),
                        index = index,
                        title = title,
                        selectedIndex = selectedIndex,
                        outerPadding = PaddingValues(vertical = 4.dp),
                        onClick = {
                            val newPlatform = when (index) {
                                1 -> OnlineBrowsePlatform.NETEASE
                                2 -> OnlineBrowsePlatform.QQ
                                else -> null
                            }
                            viewModel.selectPlatform(newPlatform)
                        }
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (index == selectedIndex) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }
        },
        bottomOverlay = {}
    ) {
        Crossfade(
            targetState = Triple(state.isLoading && state.pages.isEmpty(), state.pages.isEmpty() && state.error != null, state.selectedPlatform),
            label = "browse_crossfade"
        ) { (showShimmer, showError, _) ->
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
                        selectedPlatform = state.selectedPlatform,
                        loadingPlatforms = state.loadingPlatforms,
                        platformErrors = state.platformErrors,
                        onRetry = { viewModel.refresh(true) },
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
                top = 18.dp,
                bottom = bottomContentPadding
            ),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Daily Recommendation Hero Shimmer
        ShimmerBox(
            modifier = Modifier
                .fillMaxWidth()
                .height(156.dp)
                .clip(RoundedCornerShape(24.dp))
        )

        // Section Title Shimmer
        ShimmerBox(
            modifier = Modifier
                .width(100.dp)
                .height(24.dp)
                .clip(RoundedCornerShape(6.dp))
        )

        // Playlists Grid Shimmer (2 columns)
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

        // Charts Title Shimmer
        ShimmerBox(
            modifier = Modifier
                .width(80.dp)
                .height(24.dp)
                .clip(RoundedCornerShape(6.dp))
        )

        // Chart Card Shimmer (horizontal)
        ShimmerBox(
            modifier = Modifier
                .fillMaxWidth()
                .height(108.dp)
                .clip(RoundedCornerShape(20.dp))
        )
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
    selectedPlatform: OnlineBrowsePlatform?,
    loadingPlatforms: Set<OnlineBrowsePlatform>,
    platformErrors: Map<OnlineBrowsePlatform, String>,
    onRetry: () -> Unit,
    navController: NavController,
    playerViewModel: PlayerViewModel,
    paddingValues: PaddingValues
) {
    val filteredPages = if (selectedPlatform != null) {
        pages.filter { it.platform == selectedPlatform }
    } else {
        pages
    }

    val dailyTiles = buildList<RecommendationTile> {
        filteredPages.forEach { page ->
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
        val targetPlatforms = if (selectedPlatform != null) listOf(selectedPlatform) else OnlineBrowsePlatform.entries.toList()
        targetPlatforms.forEach { platform ->
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

    val playlistTiles = buildList<RecommendationTile> {
        val targetPlatforms = if (selectedPlatform != null) listOf(selectedPlatform) else OnlineBrowsePlatform.entries.toList()
        targetPlatforms.forEach { platform ->
            filteredPages.filter { it.platform == platform }.forEach { page ->
                page.sections
                    .filter { it.kind == RecommendationSectionKind.NEW_SONGS }
                    .filter { it.items.any { item -> item is RecommendationSongItem } }
                    .forEach { add(SectionTile(page, it)) }
            }

            filteredPages.flatMap { page ->
                page.sections.flatMap { section ->
                    section.items
                        .filterIsInstance<RemotePlaylistReference>()
                        .filter { it.platform == platform && !isQqDaily30(it) }
                        .map { PlaylistTile(page, it) }
                }
            }
                .distinctBy { it.playlist.remoteId }
                .take(if (platform == OnlineBrowsePlatform.NETEASE) 6 else 4)
                .forEach { add(it) }
        }
    }.distinctBy { it.key }

    val charts = filteredPages.flatMap { page ->
        page.sections.flatMap { section ->
            section.items.filterIsInstance<RemoteChartReference>().map { page to it }
        }
    }.distinctBy { "${it.first.platform.name}_${it.second.remoteId}" }

    val bottomContentPadding = paddingValues.calculateBottomPadding() + MiniPlayerHeight + 36.dp
    val showPlatformBadge = selectedPlatform == null

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = LibraryHeaderHorizontalPadding,
            end = LibraryHeaderHorizontalPadding,
            top = 18.dp,
            bottom = bottomContentPadding
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Daily Recommendation Banner Carousel (Option A)
        if (dailyTiles.isNotEmpty()) {
            item(key = "daily_section_title") {
                RecommendationSectionTitle("每日推荐")
            }
            item(key = "daily_section_pager") {
                DailyRecommendationPager(
                    tiles = dailyTiles,
                    navController = navController,
                    playerViewModel = playerViewModel,
                    onRetry = onRetry
                )
            }
        }

        // 2. Playlists 2-Column Grid
        if (playlistTiles.isNotEmpty()) {
            item(key = "playlist_section_title") {
                RecommendationSectionTitle("推荐歌单")
            }
            items(playlistTiles.chunked(2), key = { row -> row.joinToString { it.key } }) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { entry ->
                        RecommendationTileCard(
                            tile = entry,
                            modifier = Modifier.weight(1f),
                            showPlatformBadge = showPlatformBadge
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
                    showPlatformBadge = showPlatformBadge,
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
}

@Composable
private fun DailyRecommendationPager(
    tiles: List<RecommendationTile>,
    navController: NavController,
    playerViewModel: PlayerViewModel,
    onRetry: () -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { tiles.size })

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth(),
            pageSpacing = 12.dp
        ) { pageIndex ->
            val tile = tiles[pageIndex]
            DailyHeroCard(
                tile = tile,
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
                            navigateToRecommendationTile(navController, tile)
                        }
                        is PendingDailyTile -> onRetry()
                    }
                }
            )
        }

        if (tiles.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(tiles.size) { index ->
                    val isSelected = pagerState.currentPage == index
                    val width by animateDpAsState(
                        targetValue = if (isSelected) 18.dp else 6.dp,
                        animationSpec = tween(300),
                        label = "indicator_width"
                    )
                    val color = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)

                    Box(
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .height(6.dp)
                            .width(width)
                            .clip(CircleShape)
                            .background(color)
                    )
                }
            }
        }
    }
}

@Composable
private fun DailyHeroCard(
    tile: RecommendationTile,
    onClick: () -> Unit,
    onPlayClick: () -> Unit
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

    val eyebrow = when (platform) {
        OnlineBrowsePlatform.QQ -> "DAILY 30 · QQ 音乐"
        OnlineBrowsePlatform.NETEASE -> "DAILY MIX · 网易云音乐"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(156.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (!artworkUri.isNullOrBlank()) {
                SmartImage(
                    model = artworkUri,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }

            val surfaceContainer = MaterialTheme.colorScheme.surfaceContainerHigh
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            colorStops = arrayOf(
                                0.0f to surfaceContainer.copy(alpha = 0.95f),
                                0.52f to surfaceContainer.copy(alpha = 0.88f),
                                0.82f to surfaceContainer.copy(alpha = 0.45f),
                                1.0f to Color.Transparent
                            )
                        )
                    )
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = eyebrow,
                        style = MaterialTheme.typography.labelSmall.copy(
                            letterSpacing = 1.sp,
                            fontFamily = GoogleSansRounded,
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontFamily = GoogleSansRounded,
                            fontWeight = FontWeight.Black
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "专属个性化推荐 · 每日随心听",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }

                Spacer(Modifier.height(2.dp))
            }

            if (tile !is PendingDailyTile) {
                FilledIconButton(
                    onClick = onPlayClick,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                        .size(46.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    shape = CircleShape
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = "立即播放",
                        modifier = Modifier.size(26.dp)
                    )
                }
            } else if (tile.failed) {
                Text(
                    text = "加载失败，点击重试",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                )
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

@Composable
private fun RecommendationTileCard(
    tile: RecommendationTile,
    modifier: Modifier,
    showPlatformBadge: Boolean,
    onClick: () -> Unit
) {
    when (tile) {
        is SectionTile -> RecommendationSectionCard(tile, modifier, showPlatformBadge, onClick)
        is PlaylistTile -> RecommendationPlaylistCard(tile.playlist, modifier, showPlatformBadge, onClick)
        is PendingDailyTile -> Unit
    }
}

@Composable
private fun RecommendationSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge.copy(
            fontFamily = GoogleSansRounded,
            fontWeight = FontWeight.Bold
        ),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
    )
}

@Composable
private fun RecommendationSectionCard(
    entry: SectionTile,
    modifier: Modifier,
    showPlatformBadge: Boolean,
    onClick: () -> Unit
) {
    val songs = entry.section.items.filterIsInstance<RecommendationSongItem>()
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(10.dp)) {
            Box {
                RecommendationArtwork(
                    artworkUri = songs.firstOrNull()?.artworkUri,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                    accent = MaterialTheme.colorScheme.primaryContainer
                )
                if (showPlatformBadge) {
                    PlatformBadge(entry.page.platform, Modifier.align(Alignment.TopStart).padding(6.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                entry.section.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = GoogleSansRounded,
                    fontWeight = FontWeight.SemiBold
                )
            )
            if (entry.section.kind == RecommendationSectionKind.NEW_SONGS) {
                Text(
                    "新歌速递",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun RecommendationPlaylistCard(
    playlist: RemotePlaylistReference,
    modifier: Modifier,
    showPlatformBadge: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(10.dp)) {
            Box {
                SmartImage(
                    model = playlist.artworkUri,
                    contentDescription = playlist.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                    shape = RoundedCornerShape(15.dp),
                    contentScale = ContentScale.Crop
                )
                if (showPlatformBadge) {
                    PlatformBadge(playlist.platform, Modifier.align(Alignment.TopStart).padding(6.dp))
                }
                if (playlist.songCount > 0 && !isQqDaily30(playlist)) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "${playlist.songCount} 首",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                playlist.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = GoogleSansRounded,
                    fontWeight = FontWeight.SemiBold
                )
            )
        }
    }
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
private fun RecommendationArtwork(
    artworkUri: String?,
    modifier: Modifier,
    accent: Color
) {
    if (artworkUri.isNullOrBlank()) {
        Box(modifier.clip(RoundedCornerShape(16.dp)).background(accent), contentAlignment = Alignment.Center) {
            Text("30", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
    } else {
        SmartImage(
            model = artworkUri,
            contentDescription = null,
            modifier = modifier,
            shape = RoundedCornerShape(16.dp),
            contentScale = ContentScale.Crop
        )
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
