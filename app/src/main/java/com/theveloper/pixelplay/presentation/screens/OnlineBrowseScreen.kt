package com.theveloper.pixelplay.presentation.screens

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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
                        fontSize = 40.sp,
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
        when {
            state.isLoading && state.pages.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            state.pages.isEmpty() && state.error != null -> {
                OnlineBrowseError(message = state.error.orEmpty(), onRetry = { viewModel.refresh(true) })
            }
            state.pages.isNotEmpty() -> {
                RecommendationPageContent(
                    pages = state.pages,
                    navController = navController,
                    paddingValues = paddingValues
                )
            }
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

@Composable
private fun RecommendationPageContent(
    pages: List<RecommendationPage>,
    navController: NavController,
    paddingValues: PaddingValues
) {
    val dailyTiles = buildList<RecommendationTile> {
        pages.forEach { page ->
            page.sections
                .filter { it.kind == RecommendationSectionKind.DAILY_SONGS }
                .filter { it.items.any { item -> item is RecommendationSongItem } }
                .forEach { add(SectionTile(page, it)) }

            // QQ's Daily 30 is returned by Home Feed as a playlist card rather
            // than as a song section. Put that card beside the other daily
            // recommendations so the page follows the user's intent.
            page.sections
                .flatMap { it.items }
                .filterIsInstance<RemotePlaylistReference>()
                .filter(::isQqDaily30)
                .forEach { add(PlaylistTile(page, it)) }
        }
    }.distinctBy { it.key }

    val playlistTiles = buildList<RecommendationTile> {
        // Keep each platform's new-song feed beside that platform's playlists.
        // Daily 30 is already displayed above, so exclude it here.
        OnlineBrowsePlatform.entries.forEach { platform ->
            pages.filter { it.platform == platform }.forEach { page ->
                page.sections
                    .filter { it.kind == RecommendationSectionKind.NEW_SONGS }
                    .filter { it.items.any { item -> item is RecommendationSongItem } }
                    .forEach { add(SectionTile(page, it)) }
            }

            // Keep the combined feed balanced. A stale cache or a provider
            // returning more than the preview limit must not let one platform
            // fill the section.
            pages.flatMap { page ->
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
    val charts = pages.flatMap { page ->
        page.sections.flatMap { section ->
            section.items.filterIsInstance<RemoteChartReference>().map { page to it }
        }
    }
    val bottomContentPadding = paddingValues.calculateBottomPadding() + MiniPlayerHeight + 36.dp

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = LibraryHeaderHorizontalPadding,
            end = LibraryHeaderHorizontalPadding,
            top = 18.dp,
            bottom = bottomContentPadding
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (dailyTiles.isNotEmpty()) {
            item { RecommendationSectionTitle("每日推荐") }
            items(dailyTiles.chunked(2), key = { row ->
                row.joinToString { it.key }
            }) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { entry ->
                        RecommendationTileCard(entry, Modifier.weight(1f)) {
                            navigateToRecommendationTile(navController, entry)
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        if (playlistTiles.isNotEmpty()) {
            item { RecommendationSectionTitle("推荐歌单") }
            items(playlistTiles.chunked(2), key = { row -> row.joinToString { it.key } }) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { entry ->
                        RecommendationTileCard(entry, Modifier.weight(1f)) {
                            navigateToRecommendationTile(navController, entry)
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        if (charts.isNotEmpty()) {
            item { RecommendationSectionTitle("榜单") }
            items(charts.chunked(2), key = { row -> row.joinToString { "chart_${it.first.platform.name}_${it.second.remoteId}" } }) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { (page, chart) ->
                        RecommendationChartCard(
                            chart = chart,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                navController.navigateSafely(
                                    Screen.OnlineCollection.createRoute("chart", page.platform.name, chart.remoteId, chart.title)
                                )
                            }
                        )
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
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
        is SectionTile -> navController.navigateSafely(
            Screen.OnlineCollection.createRoute(
                "section",
                tile.page.platform.name,
                tile.section.id,
                tile.section.title
            )
        )
        is PlaylistTile -> navController.navigateSafely(
            Screen.OnlineCollection.createRoute(
                "playlist",
                tile.page.platform.name,
                tile.playlist.remoteId,
                tile.playlist.title
            )
        )
    }
}

@Composable
private fun RecommendationTileCard(
    tile: RecommendationTile,
    modifier: Modifier,
    onClick: () -> Unit
) {
    when (tile) {
        is SectionTile -> RecommendationSectionCard(tile, modifier, onClick)
        is PlaylistTile -> RecommendationPlaylistCard(tile.playlist, modifier, onClick)
    }
}

@Composable
private fun RecommendationSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
    )
}

@Composable
private fun RecommendationSectionCard(
    entry: SectionTile,
    modifier: Modifier,
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
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                    accent = MaterialTheme.colorScheme.primaryContainer
                )
                PlatformBadge(entry.page.platform, Modifier.align(Alignment.TopEnd).padding(7.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(entry.section.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            if (entry.section.kind == RecommendationSectionKind.NEW_SONGS) {
                Text("30 首", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun RecommendationPlaylistCard(
    playlist: RemotePlaylistReference,
    modifier: Modifier,
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
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                    shape = RoundedCornerShape(15.dp),
                    contentScale = ContentScale.Crop
                )
                PlatformBadge(playlist.platform, Modifier.align(Alignment.TopEnd).padding(7.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(playlist.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            if (!isQqDaily30(playlist)) {
                Text("${playlist.songCount.coerceAtLeast(0)} 首", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun RecommendationChartCard(
    chart: RemoteChartReference,
    modifier: Modifier,
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
                ChartArtworkMosaic(
                    artworkUris = chart.previewArtworkUris,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                )
                PlatformBadge(chart.platform, Modifier.align(Alignment.TopEnd).padding(7.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(chart.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ChartArtworkMosaic(
    artworkUris: List<String>,
    modifier: Modifier
) {
    val fallbackBrush = Brush.linearGradient(
        listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.secondaryContainer)
    )
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(15.dp))
            .background(fallbackBrush),
        contentAlignment = Alignment.Center
    ) {
        if (artworkUris.isEmpty()) {
            Icon(
                Icons.Rounded.MusicNote,
                contentDescription = "榜单封面",
                modifier = Modifier.size(30.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
            )
        } else {
            Column(Modifier.fillMaxSize()) {
                artworkUris.take(4).chunked(2).forEach { rowUris ->
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        rowUris.forEach { uri ->
                            SmartImage(
                                model = uri,
                                contentDescription = null,
                                modifier = Modifier.weight(1f).fillMaxHeight().padding(1.dp),
                                shape = RoundedCornerShape(3.dp),
                                contentScale = ContentScale.Crop
                            )
                        }
                        if (rowUris.size == 1) Spacer(Modifier.weight(1f))
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
            .padding(horizontal = 8.dp, vertical = 4.dp)
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
