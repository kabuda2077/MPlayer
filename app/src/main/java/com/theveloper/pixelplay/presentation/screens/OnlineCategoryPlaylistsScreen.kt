package com.theveloper.pixelplay.presentation.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Whatshot
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.theveloper.pixelplay.data.model.OnlineBrowsePlatform
import com.theveloper.pixelplay.data.model.RemotePlaylistReference
import com.theveloper.pixelplay.presentation.components.MiniPlayerHeight
import com.theveloper.pixelplay.presentation.components.SmartImage
import com.theveloper.pixelplay.presentation.navigation.Screen
import com.theveloper.pixelplay.presentation.navigation.navigateSafely
import com.theveloper.pixelplay.presentation.online.OnlineCategoryViewModel
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel
import com.theveloper.pixelplay.ui.theme.GoogleSansRounded

private data class CategoryVisualSpec(
    val gradientColors: List<Color>,
    val icon: ImageVector
)

private fun getCategoryVisualSpec(categoryKey: String): CategoryVisualSpec {
    return when (categoryKey) {
        "华语流行" -> CategoryVisualSpec(
            listOf(Color(0xFFE53935), Color(0xFFFF7043)),
            Icons.Rounded.MusicNote
        )
        "欧美潮流" -> CategoryVisualSpec(
            listOf(Color(0xFF7B1FA2), Color(0xFFBA68C8)),
            Icons.Rounded.Headphones
        )
        "摇滚力量" -> CategoryVisualSpec(
            listOf(Color(0xFF263238), Color(0xFF455A64)),
            Icons.Rounded.GraphicEq
        )
        "说唱嘻哈" -> CategoryVisualSpec(
            listOf(Color(0xFFE65100), Color(0xFFFFB74D)),
            Icons.Rounded.Whatshot
        )
        "轻松治愈" -> CategoryVisualSpec(
            listOf(Color(0xFF00695C), Color(0xFF4DB6AC)),
            Icons.Rounded.NightsStay
        )
        "经典怀旧" -> CategoryVisualSpec(
            listOf(Color(0xFF5D4037), Color(0xFF8D6E63)),
            Icons.Rounded.Radio
        )
        "动漫游戏" -> CategoryVisualSpec(
            listOf(Color(0xFFD81B60), Color(0xFFFF4081)),
            Icons.Rounded.AutoAwesome
        )
        "电音派对" -> CategoryVisualSpec(
            listOf(Color(0xFF00838F), Color(0xFF00ACC1)),
            Icons.Rounded.PlayArrow
        )
        else -> CategoryVisualSpec(
            listOf(Color(0xFF1E88E5), Color(0xFF42A5F5)),
            Icons.Rounded.MusicNote
        )
    }
}

@Composable
fun OnlineCategoryPlaylistsScreen(
    navController: NavController,
    playerViewModel: PlayerViewModel,
    modifier: Modifier = Modifier,
    viewModel: OnlineCategoryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val visualSpec = remember(uiState.categoryKey) { getCategoryVisualSpec(uiState.categoryKey) }
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomContentPadding = MiniPlayerHeight + 36.dp
    val gridState = rememberLazyGridState()

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = statusBarPadding)
        ) {
            // --- 顶部导航栏：返回键旁边显示“探索”，消除与下方卡片标题重复 ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "返回",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                Text(
                    text = "探索",
                    style = MaterialTheme.typography.titleLarge,
                    fontFamily = GoogleSansRounded,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // --- 歌单网格主体 ---
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                state = gridState,
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = bottomContentPadding
                ),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 1. 分类 Banner 与 平台切换器（跨两列）
                item(span = { GridItemSpan(2) }) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // 流光质感 Banner（包含分类名称与副标题）
                        val gradientBrush = remember(visualSpec.gradientColors) {
                            Brush.linearGradient(visualSpec.gradientColors)
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(96.dp)
                                .clip(RoundedCornerShape(22.dp))
                                .background(gradientBrush)
                                .padding(18.dp)
                        ) {
                            Icon(
                                imageVector = visualSpec.icon,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.22f),
                                modifier = Modifier
                                    .size(68.dp)
                                    .align(Alignment.BottomEnd)
                                    .graphicsLayer {
                                        rotationZ = -14f
                                        translationX = 14.dp.toPx()
                                        translationY = 16.dp.toPx()
                                    }
                            )

                            Column(
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .fillMaxWidth(0.85f)
                            ) {
                                Text(
                                    text = uiState.title,
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontFamily = GoogleSansRounded,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = if (uiState.subtitle.isNotBlank()) uiState.subtitle else "官方精选整理好歌单",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White.copy(alpha = 0.9f)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // 平台切换 Segmented Switcher（优化动画与防闪烁）
                        CategoryPlatformSegmentedControl(
                            currentPlatform = uiState.platform,
                            onPlatformChange = { viewModel.switchPlatform(it) }
                        )

                        // 若正在后台切换平台加载，显示一条极细的平滑进度线，而不是把整个页面清空跳动
                        if (uiState.isLoading && uiState.playlists.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(2.dp)
                                    .clip(RoundedCornerShape(1.dp)),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = Color.Transparent
                            )
                        } else {
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }

                // 2. 初始全屏加载状态（仅在没有任何歌单时展示）
                if (uiState.isLoading && uiState.playlists.isEmpty()) {
                    item(span = { GridItemSpan(2) }) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(260.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(36.dp),
                                    strokeWidth = 3.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                                Text(
                                    text = "正在获取精选歌单...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else if (uiState.error != null && uiState.playlists.isEmpty()) {
                    // 3. 错误状态
                    item(span = { GridItemSpan(2) }) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(260.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ErrorOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(44.dp)
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = uiState.error ?: "加载失败",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                                Button(
                                    onClick = { viewModel.retry() },
                                    shape = CircleShape,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("重试", fontFamily = GoogleSansRounded, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                } else if (uiState.playlists.isEmpty()) {
                    // 4. 空状态
                    item(span = { GridItemSpan(2) }) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "暂无相关分类歌单",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    // 5. 歌单卡片列表（加载新平台时保持平滑淡入，不产生高度塌陷）
                    items(
                        items = uiState.playlists,
                        key = { it.key }
                    ) { playlist ->
                        CategoryPlaylistCard(
                            playlist = playlist,
                            modifier = Modifier.graphicsLayer {
                                alpha = if (uiState.isLoading) 0.65f else 1f
                            },
                            onClick = {
                                navController.navigateSafely(
                                    Screen.OnlineCollection.createRoute(
                                        kind = "playlist",
                                        platform = uiState.platform.name,
                                        id = playlist.remoteId,
                                        title = playlist.title,
                                        coverUrl = playlist.artworkUri
                                    )
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryPlatformSegmentedControl(
    currentPlatform: OnlineBrowsePlatform,
    onPlatformChange: (OnlineBrowsePlatform) -> Unit,
    modifier: Modifier = Modifier
) {
    val platforms = remember { listOf(OnlineBrowsePlatform.NETEASE, OnlineBrowsePlatform.QQ) }
    val haptic = LocalHapticFeedback.current

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(42.dp),
        shape = RoundedCornerShape(21.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(3.dp)
        ) {
            val segmentWidth = maxWidth / platforms.size
            val selectedIndex = platforms.indexOf(currentPlatform).coerceAtLeast(0)
            val indicatorOffset by animateDpAsState(
                targetValue = segmentWidth * selectedIndex,
                animationSpec = spring(
                    dampingRatio = 0.76f,
                    stiffness = Spring.StiffnessMediumLow
                ),
                label = "categoryPlatformIndicatorOffset"
            )

            // 1. Sliding indicator pill (平滑滑动的白色药丸滑块)
            Box(
                modifier = Modifier
                    .offset(x = indicatorOffset)
                    .width(segmentWidth)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surface)
            )

            // 2. Interactive text labels row
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                platforms.forEach { platform ->
                    val isSelected = platform == currentPlatform
                    val textColor by animateColorAsState(
                        targetValue = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        animationSpec = tween(durationMillis = 180),
                        label = "categoryPlatformText_${platform.name}"
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(18.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                if (!isSelected) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onPlatformChange(platform)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (platform == OnlineBrowsePlatform.NETEASE) "网易云音乐" else "QQ 音乐",
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = GoogleSansRounded,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = textColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryPlaylistCard(
    playlist: RemotePlaylistReference,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            ) {
                if (!playlist.artworkUri.isNullOrBlank()) {
                    SmartImage(
                        model = playlist.artworkUri,
                        contentDescription = playlist.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MusicNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Text(
                    text = playlist.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = GoogleSansRounded,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (!playlist.subtitle.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = playlist.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
