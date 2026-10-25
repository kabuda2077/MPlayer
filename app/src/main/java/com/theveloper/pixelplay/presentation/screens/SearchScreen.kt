@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.theveloper.pixelplay.presentation.screens

import com.theveloper.pixelplay.presentation.navigation.navigateSafely

import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Whatshot
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DockedSearchBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.model.OnlineBrowsePlatform
import com.theveloper.pixelplay.data.model.OnlineSearchPlatform
import com.theveloper.pixelplay.data.model.OnlineSearchResultItem
import com.theveloper.pixelplay.data.model.SearchResultItem
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.model.TransientOnlineSong
import com.theveloper.pixelplay.presentation.components.LibraryHeaderScaffold
import com.theveloper.pixelplay.presentation.components.MiniPlayerHeight
import com.theveloper.pixelplay.presentation.components.OnlineSongActionsSheet
import com.theveloper.pixelplay.presentation.components.PlaylistBottomSheet
import com.theveloper.pixelplay.presentation.components.resolveMainScreenBottomGradientHeight
import com.theveloper.pixelplay.presentation.components.subcomps.EnhancedSongListItem
import com.theveloper.pixelplay.presentation.navigation.Screen
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel
import com.theveloper.pixelplay.presentation.viewmodel.PlaylistViewModel
import com.theveloper.pixelplay.ui.theme.GoogleSansRounded
import com.theveloper.pixelplay.ui.theme.LocalPixelPlayDarkTheme
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private data class SearchCategoryItem(
    val title: String,
    val subtitle: String,
    val queryKeyword: String,
    val gradientColors: List<Color>,
    val icon: ImageVector
)

private val SEARCH_CATEGORIES = listOf(
    SearchCategoryItem(
        title = "华语流行",
        subtitle = "热歌与流行金曲",
        queryKeyword = "华语流行",
        gradientColors = listOf(Color(0xFFF43F5E), Color(0xFFFF8E6E)),
        icon = Icons.Rounded.MusicNote
    ),
    SearchCategoryItem(
        title = "欧美潮流",
        subtitle = "全球榜单先锋",
        queryKeyword = "欧美流行",
        gradientColors = listOf(Color(0xFF7C3AED), Color(0xFFA78BFA)),
        icon = Icons.Rounded.Headphones
    ),
    SearchCategoryItem(
        title = "摇滚力量",
        subtitle = "吉他与现场狂想",
        queryKeyword = "摇滚",
        gradientColors = listOf(Color(0xFF334155), Color(0xFF64748B)),
        icon = Icons.Rounded.GraphicEq
    ),
    SearchCategoryItem(
        title = "说唱嘻哈",
        subtitle = "节拍与街头律动",
        queryKeyword = "说唱",
        gradientColors = listOf(Color(0xFFEA580C), Color(0xFFF59E0B)),
        icon = Icons.Rounded.Whatshot
    ),
    SearchCategoryItem(
        title = "轻松治愈",
        subtitle = "专注与静心睡眠",
        queryKeyword = "轻音乐",
        gradientColors = listOf(Color(0xFF0F766E), Color(0xFF2DD4BF)),
        icon = Icons.Rounded.NightsStay
    ),
    SearchCategoryItem(
        title = "经典怀旧",
        subtitle = "岁月的回响金曲",
        queryKeyword = "经典老歌",
        gradientColors = listOf(Color(0xFFC2410C), Color(0xFFF59E0B)),
        icon = Icons.Rounded.Radio
    ),
    SearchCategoryItem(
        title = "动漫游戏",
        subtitle = "二次元与原声大碟",
        queryKeyword = "ACG",
        gradientColors = listOf(Color(0xFFDB2777), Color(0xFFF472B6)),
        icon = Icons.Rounded.AutoAwesome
    ),
    SearchCategoryItem(
        title = "电音派对",
        subtitle = "低音冲击与舞池",
        queryKeyword = "电音",
        gradientColors = listOf(Color(0xFF1D4ED8), Color(0xFF60A5FA)),
        icon = Icons.Rounded.PlayArrow
    )
)

private data class SearchUiSlice(
    val onlineSearchResults: ImmutableList<OnlineSearchResultItem> = persistentListOf(),
    val onlineSearchPlatform: OnlineSearchPlatform = OnlineSearchPlatform.NETEASE,
    val onlineSearchLoading: Boolean = false,
    val onlineSearchError: String? = null,
    val popularSearchTerms: ImmutableList<String> = persistentListOf(),
    val searchHistory: ImmutableList<String> = persistentListOf()
)

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalLayoutApi::class
)
@Composable
fun SearchScreen(
    paddingValues: PaddingValues,
    playerViewModel: PlayerViewModel = hiltViewModel(),
    playlistViewModel: PlaylistViewModel = hiltViewModel(),
    navController: NavController,
    onSearchBarActiveChange: (Boolean) -> Unit = {}
) {
    var searchQuery by rememberSaveable { mutableStateOf(playerViewModel.searchQuery) }
    val navBarCompactMode by playerViewModel.navBarCompactMode.collectAsStateWithLifecycle()
    val bottomGradientHeight = resolveMainScreenBottomGradientHeight(navBarCompactMode)
    val searchUiState by remember(playerViewModel) {
        playerViewModel.playerUiState
            .map { uiState ->
                SearchUiSlice(
                    onlineSearchResults = uiState.onlineSearchResults,
                    onlineSearchPlatform = uiState.onlineSearchPlatform,
                    onlineSearchLoading = uiState.onlineSearchLoading,
                    onlineSearchError = uiState.onlineSearchError,
                    popularSearchTerms = uiState.popularSearchTerms,
                    searchHistory = uiState.searchHistory
                )
            }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = SearchUiSlice())
    val currentOnlinePlatform = searchUiState.onlineSearchPlatform
    val stablePlayerState by playerViewModel.stablePlayerState.collectAsStateWithLifecycle()
    val playlistUiState by playlistViewModel.uiState.collectAsStateWithLifecycle()
    val keyboardController = LocalSoftwareKeyboardController.current
    val searchInputFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        onSearchBarActiveChange(false)
        delay(180L)
        searchInputFocusRequester.requestFocus()
        keyboardController?.show()
    }

    LaunchedEffect(playerViewModel, keyboardController) {
        playerViewModel.searchNavDoubleTapEvents.collect {
            delay(40L)
            searchInputFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    // Search debouncing is centralized in SearchStateHolder.
    LaunchedEffect(searchQuery, currentOnlinePlatform) {
        playerViewModel.performSearch(searchQuery)
    }
    val onlineSearchResults = searchUiState.onlineSearchResults
    var selectedOnlineSongForActions by remember { mutableStateOf<TransientOnlineSong?>(null) }
    var materializedSongForPlaylist by remember { mutableStateOf<Song?>(null) }

    val dm = LocalPixelPlayDarkTheme.current

    val gradientColorsDark = listOf(
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
        Color.Transparent
    ).toImmutableList()

    val gradientColorsLight = listOf(
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f),
        Color.Transparent
    ).toImmutableList()

    val gradientColors = if (dm) gradientColorsDark else gradientColorsLight

    val gradientBrush = remember(gradientColors) {
        Brush.verticalGradient(colors = gradientColors)
    }
    val colorScheme = MaterialTheme.colorScheme
    val bottomGradientBrush = remember(colorScheme.surfaceContainerLowest) {
        Brush.verticalGradient(
            colorStops = arrayOf(
                0.0f to Color.Transparent,
                0.2f to Color.Transparent,
                0.8f to colorScheme.surfaceContainerLowest,
                1.0f to colorScheme.surfaceContainerLowest
            )
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            onSearchBarActiveChange(false)
        }
    }

    val headerContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)

    LibraryHeaderScaffold(
        gradientBrush = gradientBrush,
        headerContainerColor = headerContainerColor,
        searchBarVisible = false,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        modifier = Modifier.padding(start = 8.dp),
                        text = stringResource(R.string.nav_bar_search),
                        fontFamily = GoogleSansRounded,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 40.sp,
                        letterSpacing = 1.sp
                    )
                },
                actions = {
                    FilledIconButton(
                        modifier = Modifier.padding(end = 14.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        onClick = { navController.navigateSafely(Screen.Settings.route) }
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.rounded_settings_24),
                            contentDescription = stringResource(R.string.library_cd_open_settings)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                )
            )
        },
        bottomOverlay = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .height(bottomGradientHeight)
                    .background(brush = bottomGradientBrush)
            )
        }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 1. 位于圆弧顶部的搜索框与独立的平台切换按钮
            Spacer(modifier = Modifier.height(14.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SearchScreenSearchBar(
                    query = searchQuery,
                    onQueryChange = {
                        searchQuery = it
                        playerViewModel.updateSearchQuery(it)
                    },
                    onSearch = { query ->
                        keyboardController?.hide()
                        playerViewModel.recordSearchHistory(query)
                    },
                    onClear = {
                        searchQuery = ""
                        playerViewModel.updateSearchQuery("")
                    },
                    inputModifier = Modifier.focusRequester(searchInputFocusRequester),
                    modifier = Modifier.weight(1f)
                )

                SearchPlatformSwitcher(
                    currentPlatform = currentOnlinePlatform,
                    onPlatformSelected = { platform ->
                        playerViewModel.updateOnlineSearchPlatform(platform)
                    },
                    modifier = Modifier.size(56.dp),
                    buttonColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(
                    top = 0.dp,
                    bottom = MiniPlayerHeight + paddingValues.calculateBottomPadding() + 96.dp
                ),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                // --- 状态 1：未输入搜索词（探索模式） ---
                if (searchQuery.isBlank()) {
                    item(key = "search_start_content") {
                        SearchStartContent(
                            history = searchUiState.searchHistory,
                            suggestions = searchUiState.popularSearchTerms,
                            onSuggestionClick = { suggestion ->
                                searchQuery = suggestion
                                playerViewModel.updateSearchQuery(suggestion)
                                playerViewModel.recordSearchHistory(suggestion)
                                searchInputFocusRequester.requestFocus()
                                keyboardController?.show()
                            },
                            onDeleteHistoryItem = { query ->
                                playerViewModel.deleteSearchHistory(query)
                            },
                            onClearHistory = {
                                playerViewModel.clearSearchHistory()
                            },
                            onCategoryClick = { category ->
                                val targetPlatform = if (currentOnlinePlatform == OnlineSearchPlatform.QQ) {
                                    OnlineBrowsePlatform.QQ
                                } else {
                                    OnlineBrowsePlatform.NETEASE
                                }
                                navController.navigateSafely(
                                    Screen.OnlineCategoryPlaylists.createRoute(
                                        platform = targetPlatform.name,
                                        categoryKey = category.title,
                                        title = category.title,
                                        subtitle = category.subtitle
                                    )
                                )
                            }
                        )
                    }
                }

                // --- 状态 2：输入了搜索词（仅搜索在线音乐） ---
                if (searchQuery.isNotBlank()) {
                    item(key = "search_online_results") {
                        OnlineSearchResultsPane(
                            results = onlineSearchResults,
                            searchQuery = searchQuery,
                            isLoading = searchUiState.onlineSearchLoading,
                            errorMessage = searchUiState.onlineSearchError,
                            playerViewModel = playerViewModel,
                            currentPlayingSongId = stablePlayerState.currentSong?.id,
                            isPlaying = stablePlayerState.isPlaying,
                            onSongMoreOptionsClick = { song -> selectedOnlineSongForActions = song },
                            embedded = true
                        )
                    }
                }
            }
        }
    }

    selectedOnlineSongForActions?.let { onlineSong ->
        OnlineSongActionsSheet(
            song = onlineSong,
            onDismiss = { selectedOnlineSongForActions = null },
            onPlay = {
                playerViewModel.recordSearchHistory(searchQuery)
                playerViewModel.showAndPlayTransientSong(
                    onlineSong,
                    listOf(onlineSong),
                    "搜索单曲"
                )
                selectedOnlineSongForActions = null
            },
            onPlayNext = {
                playerViewModel.recordSearchHistory(searchQuery)
                playerViewModel.addTransientSongNextToQueue(onlineSong)
                selectedOnlineSongForActions = null
            },
            onAddToQueue = {
                playerViewModel.recordSearchHistory(searchQuery)
                playerViewModel.addTransientSongToQueue(onlineSong)
                selectedOnlineSongForActions = null
            },
            onDownload = {
                playerViewModel.downloadTransientSong(onlineSong)
                selectedOnlineSongForActions = null
            },
            onFavorite = {
                playerViewModel.favoriteTransientSong(onlineSong)
                selectedOnlineSongForActions = null
            },
            onAddToPlaylist = {
                selectedOnlineSongForActions = null
                playerViewModel.materializeTransientSong(onlineSong) { savedSong ->
                    materializedSongForPlaylist = savedSong
                }
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

// Material 3 间距规范体系：基于 8.dp 栅格体系
private val SearchSectionSpacing = 24.dp        // M3 Large Spacing: 板块与板块之间的垂直间距（拉大呼吸感）
private val SearchTitleToContentSpacing = 10.dp // M3 Medium-Small Spacing: 标题到内容之间的间距
private val SearchChipSpacing = 8.dp           // M3 Compact Spacing: 胶囊芯片行距与列距
private val SearchCardGridSpacing = 10.dp      // M3 Small Spacing: 分类彩色大卡片网格缝隙

@Composable
fun SearchPlatformSwitcher(
    currentPlatform: OnlineSearchPlatform,
    onPlatformSelected: (OnlineSearchPlatform) -> Unit,
    modifier: Modifier = Modifier,
    buttonColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh
) {
    var expanded by remember { mutableStateOf(false) }
    val platforms = remember {
        listOf(
            OnlineSearchPlatform.NETEASE,
            OnlineSearchPlatform.QQ,
            OnlineSearchPlatform.KUGOU
        )
    }

    Box(modifier = modifier) {
        Surface(
            onClick = { expanded = true },
            shape = CircleShape,
            color = buttonColor,
            contentColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                SearchPlatformIcon(
                    platform = currentPlatform,
                    contentDescription = "切换平台",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            platforms.forEach { platform ->
                val isSelected = platform == currentPlatform
                DropdownMenuItem(
                    modifier = Modifier
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                            else Color.Transparent
                        ),
                    leadingIcon = {
                        SearchPlatformIcon(
                            platform = platform,
                            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    text = {
                        Text(
                            text = platform.label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    },
                    onClick = {
                        onPlatformSelected(platform)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun SearchPlatformIcon(
    platform: OnlineSearchPlatform,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    tint: Color = LocalContentColor.current
) {
    val painter = when (platform) {
        OnlineSearchPlatform.NETEASE -> painterResource(R.drawable.netease_cloud_music_logo_icon_206716__1_)
        OnlineSearchPlatform.QQ -> painterResource(R.drawable.qq_music)
        OnlineSearchPlatform.KUGOU -> painterResource(R.drawable.ic_kugou_music)
        OnlineSearchPlatform.KUWO -> painterResource(R.drawable.ic_kuwo_music)
    }
    Icon(
        painter = painter,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier
    )
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchScreenSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    inputModifier: Modifier = Modifier
) {
    val inputFieldColors = SearchBarDefaults.inputFieldColors(
        focusedTextColor = MaterialTheme.colorScheme.onSurface,
        unfocusedTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        cursorColor = MaterialTheme.colorScheme.primary
    )

    DockedSearchBar(
        inputField = {
            SearchBarDefaults.InputField(
                modifier = inputModifier,
                query = query,
                onQueryChange = onQueryChange,
                onSearch = onSearch,
                expanded = false,
                onExpandedChange = {},
                placeholder = {
                    Text(
                        text = stringResource(R.string.search_placeholder),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = stringResource(R.string.search_cd_search_icon),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                },
                trailingIcon = {
                    if (query.isNotBlank()) {
                        IconButton(
                            onClick = onClear,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f))
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.search_cd_clear_search_query),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                },
                colors = inputFieldColors
            )
        },
        expanded = false,
        onExpandedChange = {},
        modifier = modifier
            .clip(RoundedCornerShape(28.dp)),
        colors = SearchBarDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            dividerColor = Color.Transparent,
            inputFieldColors = inputFieldColors
        ),
        content = {}
    )
}

private data class TrendingColorTheme(
    val containerColor: Color,
    val contentColor: Color,
    val rankColor: Color
)

private val LightTrendingThemes = listOf(
    TrendingColorTheme(
        containerColor = Color(0xFFEBF2FC),
        contentColor = Color(0xFF2C3E55),
        rankColor = Color(0xFF2563EB)
    ),
    TrendingColorTheme(
        containerColor = Color(0xFFE6F8F2),
        contentColor = Color(0xFF1E4B3E),
        rankColor = Color(0xFF059669)
    ),
    TrendingColorTheme(
        containerColor = Color(0xFFFFF0F5),
        contentColor = Color(0xFF5A2538),
        rankColor = Color(0xFFE11D48)
    ),
    TrendingColorTheme(
        containerColor = Color(0xFFF3EEFC),
        contentColor = Color(0xFF3E2D5E),
        rankColor = Color(0xFF7C3AED)
    ),
    TrendingColorTheme(
        containerColor = Color(0xFFFFF5EB),
        contentColor = Color(0xFF5A3E1E),
        rankColor = Color(0xFFEA580C)
    ),
    TrendingColorTheme(
        containerColor = Color(0xFFE6F7FA),
        contentColor = Color(0xFF164E63),
        rankColor = Color(0xFF0891B2)
    )
)

private val DarkTrendingThemes = listOf(
    TrendingColorTheme(
        containerColor = Color(0xFF1B2838),
        contentColor = Color(0xFFE2E8F0),
        rankColor = Color(0xFF60A5FA)
    ),
    TrendingColorTheme(
        containerColor = Color(0xFF142E26),
        contentColor = Color(0xFFE6F4EA),
        rankColor = Color(0xFF34D399)
    ),
    TrendingColorTheme(
        containerColor = Color(0xFF331B26),
        contentColor = Color(0xFFFCE7F3),
        rankColor = Color(0xFFF472B6)
    ),
    TrendingColorTheme(
        containerColor = Color(0xFF261C38),
        contentColor = Color(0xFFF3E8FF),
        rankColor = Color(0xFFA78BFA)
    ),
    TrendingColorTheme(
        containerColor = Color(0xFF332316),
        contentColor = Color(0xFFFEF3C7),
        rankColor = Color(0xFFFBBF24)
    ),
    TrendingColorTheme(
        containerColor = Color(0xFF132A32),
        contentColor = Color(0xFFCFFAFE),
        rankColor = Color(0xFF22D3EE)
    )
)

// 热门搜索胶囊色彩分布序列：打散相邻芯片与上下两行间的对角关联，消除斜向条纹感，呈现自然随机的糖果色分布
private val TRENDING_COLOR_SEQUENCE = intArrayOf(
    0, // 1: 经典蓝
    2, // 2: 玫瑰粉
    1, // 3: 薄荷绿
    4, // 4: 暖琥珀
    3, // 5: 丁香紫
    5, // 6: 碧海青
    4, // 7: 暖琥珀
    0, // 8: 经典蓝
    2, // 9: 玫瑰粉
    1  // 10: 薄荷绿
)

@Composable
private fun TrendingChip(
    rank: Int,
    term: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = LocalPixelPlayDarkTheme.current
    val themes = if (isDark) DarkTrendingThemes else LightTrendingThemes
    val colorIndex = TRENDING_COLOR_SEQUENCE[(rank - 1).mod(TRENDING_COLOR_SEQUENCE.size)]
    val theme = themes[colorIndex.mod(themes.size)]

    Surface(
        shape = CircleShape,
        color = theme.containerColor,
        contentColor = theme.contentColor,
        modifier = modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 17.dp, vertical = 10.5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$rank",
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = GoogleSansRounded,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 16.5.sp,
                    platformStyle = PlatformTextStyle(includeFontPadding = false)
                ),
                color = theme.rankColor,
                modifier = Modifier.offset(y = 2.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = term,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    platformStyle = PlatformTextStyle(includeFontPadding = false)
                ),
                color = theme.contentColor,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun CategoryCard(
    category: SearchCategoryItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val gradientBrush = remember(category.gradientColors) {
        Brush.linearGradient(
            colors = category.gradientColors
        )
    }

    Box(
        modifier = modifier
            .height(96.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(gradientBrush)
            .clickable(onClick = onClick)
    ) {
        // 顶部微弱透光层（营造果冻/玻璃般通透光感）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.16f),
                            Color.Transparent
                        )
                    )
                )
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Icon(
                imageVector = category.icon,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.22f),
                modifier = Modifier
                    .size(56.dp)
                    .align(Alignment.BottomEnd)
                    .graphicsLayer {
                        rotationZ = -14f
                        translationX = 12.dp.toPx()
                        translationY = 14.dp.toPx()
                    }
            )

            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth(0.82f)
            ) {
                Text(
                    text = category.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = GoogleSansRounded,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.5.sp
                    ),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = category.subtitle,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 12.sp
                    ),
                    color = Color.White.copy(alpha = 0.88f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun SearchSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(
                fontFamily = GoogleSansRounded,
                fontWeight = FontWeight.Bold,
                platformStyle = PlatformTextStyle(includeFontPadding = false)
            ),
            color = MaterialTheme.colorScheme.onSurface
        )
        if (action != null) {
            action()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchStartContent(
    history: List<String>,
    suggestions: List<String>,
    onSuggestionClick: (String) -> Unit,
    onDeleteHistoryItem: (String) -> Unit,
    onClearHistory: () -> Unit,
    onCategoryClick: (SearchCategoryItem) -> Unit
) {
    var showClearDialog by remember { mutableStateOf(false) }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.search_history_clear_dialog_title),
                    fontFamily = GoogleSansRounded,
                    fontWeight = FontWeight.Bold
                )
            },
            text = { Text(text = stringResource(R.string.search_history_clear_dialog_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearHistory()
                        showClearDialog = false
                    }
                ) {
                    Text(
                        text = stringResource(R.string.search_history_clear_confirm),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(text = stringResource(R.string.search_history_cancel))
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .padding(top = 0.dp, bottom = 16.dp)
    ) {
        // --- 1. 热门搜索（置于顶部，统一规范标题栏） ---
        Column(modifier = Modifier.fillMaxWidth()) {
            SearchSectionHeader(
                title = stringResource(R.string.search_popular_title)
            )

            Spacer(modifier = Modifier.height(SearchTitleToContentSpacing))

            val terms = suggestions.take(10)
            val half = (terms.size + 1) / 2
            val row1 = terms.take(half)
            val row2 = terms.drop(half)

            // 两行独立横滑，行间距与列间距统一为 SearchChipSpacing (8.dp)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(SearchChipSpacing)
            ) {
                // 第一行独立横滑
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(SearchChipSpacing)
                ) {
                    row1.forEachIndexed { i, term ->
                        TrendingChip(
                            rank = i + 1,
                            term = term,
                            onClick = { onSuggestionClick(term) }
                        )
                    }
                }
                // 第二行独立横滑
                if (row2.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(SearchChipSpacing)
                    ) {
                        row2.forEachIndexed { i, term ->
                            TrendingChip(
                                rank = i + half + 1,
                                term = term,
                                onClick = { onSuggestionClick(term) }
                            )
                        }
                    }
                }
            }
        }

        // --- 2. 搜索历史（置于热门搜索下方，单行展示，字号胶囊尺寸与热门一致） ---
        if (history.isNotEmpty()) {
            val displayedHistory = remember(history) { history.take(5) }
            Spacer(modifier = Modifier.height(SearchSectionSpacing))

            Column(modifier = Modifier.fillMaxWidth()) {
                SearchSectionHeader(
                    title = stringResource(R.string.search_history_title),
                    action = {
                        IconButton(
                            onClick = { showClearDialog = true },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = stringResource(R.string.search_history_clear),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                )

                Spacer(modifier = Modifier.height(SearchTitleToContentSpacing))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(SearchChipSpacing)
                ) {
                    displayedHistory.forEach { historyItem ->
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f),
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable { onSuggestionClick(historyItem) }
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 17.dp, top = 10.5.dp, bottom = 10.5.dp, end = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = historyItem,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontSize = 15.sp,
                                        platformStyle = PlatformTextStyle(includeFontPadding = false)
                                    ),
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 180.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .clickable { onDeleteHistoryItem(historyItem) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = stringResource(R.string.search_history_delete_single),
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // --- 3. 探索分类（统一规范标题栏） ---
        // 板块间距统一为 SearchSectionSpacing
        Spacer(modifier = Modifier.height(SearchSectionSpacing))

        Column(modifier = Modifier.fillMaxWidth()) {
            SearchSectionHeader(
                title = stringResource(R.string.search_categories_title)
            )

            Spacer(modifier = Modifier.height(SearchTitleToContentSpacing))

            val categoryRows = remember { SEARCH_CATEGORIES.chunked(2) }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(SearchCardGridSpacing)
            ) {
                categoryRows.forEach { rowPair ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(SearchCardGridSpacing)
                    ) {
                        rowPair.forEach { category ->
                            CategoryCard(
                                category = category,
                                onClick = { onCategoryClick(category) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (rowPair.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EmptySearchResults(searchQuery: String, colorScheme: ColorScheme) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = stringResource(R.string.search_cd_no_results),
            modifier = Modifier
                .size(80.dp)
                .padding(bottom = 16.dp),
            tint = colorScheme.primary.copy(alpha = 0.6f)
        )

        Text(
            text = if (searchQuery.isNotBlank()) {
                stringResource(R.string.search_no_results_for_query, searchQuery)
            } else {
                stringResource(R.string.search_nothing_found)
            },
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.search_try_different_or_filters),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun OnlineSearchResultsPane(
    results: List<OnlineSearchResultItem>,
    searchQuery: String,
    isLoading: Boolean,
    errorMessage: String?,
    playerViewModel: PlayerViewModel,
    currentPlayingSongId: String?,
    isPlaying: Boolean,
    onSongMoreOptionsClick: (TransientOnlineSong) -> Unit,
    embedded: Boolean = false
) {
    val localDensity = LocalDensity.current
    val transientSongs = remember(results) { results.map { it.transientSong } }
    val queueName = remember(searchQuery) {
        searchQuery.trim()
            .takeIf { it.isNotEmpty() }
            ?.let { "Online Search: $it" }
            ?: "Online Search"
    }
    val imePadding = WindowInsets.ime.getBottom(localDensity).dp
    val systemBarPaddingBottom = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding() + 94.dp

    when {
        searchQuery.isBlank() -> {
            Spacer(modifier = Modifier.fillMaxSize())
        }
        isLoading && results.isEmpty() -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 48.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
        errorMessage != null && results.isEmpty() -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 48.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = errorMessage.ifBlank { stringResource(R.string.search_online_error) },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }
        }
        results.isEmpty() -> {
            EmptySearchResults(
                searchQuery = searchQuery,
                colorScheme = MaterialTheme.colorScheme
            )
        }
        else -> {
            val resultContent: @Composable () -> Unit = {
                results.forEach { item ->
                    OnlineSearchResultRow(
                        item = item,
                        transientSongs = transientSongs,
                        queueName = queueName,
                        playerViewModel = playerViewModel,
                        currentPlayingSongId = currentPlayingSongId,
                        isPlaying = isPlaying,
                        onSongMoreOptionsClick = onSongMoreOptionsClick
                    )
                }
            }
            if (embedded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    resultContent()
                }
            } else {
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
                        bottom = if (imePadding <= 8.dp) (MiniPlayerHeight + systemBarPaddingBottom) else imePadding
                    )
                ) {
                    items(
                        count = results.size,
                        key = { index -> "online_${results[index].song.id}_${results[index].platform.name}" },
                        contentType = { "online_search_song" }
                    ) { index ->
                        OnlineSearchResultRow(
                            item = results[index],
                            transientSongs = transientSongs,
                            queueName = queueName,
                            playerViewModel = playerViewModel,
                            currentPlayingSongId = currentPlayingSongId,
                            isPlaying = isPlaying,
                            onSongMoreOptionsClick = onSongMoreOptionsClick
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OnlineSearchResultRow(
    item: OnlineSearchResultItem,
    transientSongs: List<TransientOnlineSong>,
    queueName: String,
    playerViewModel: PlayerViewModel,
    currentPlayingSongId: String?,
    isPlaying: Boolean,
    onSongMoreOptionsClick: (TransientOnlineSong) -> Unit
) {
    val song = item.song
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 3.dp)
    ) {
        EnhancedSongListItem(
            song = song,
            isPlaying = isPlaying,
            isCurrentSong = currentPlayingSongId == song.id,
            albumArtSize = 42.dp,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp),
            customShape = RoundedCornerShape(14.dp),
            containerColorOverride = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.45f),
            onMoreOptionsClick = { onSongMoreOptionsClick(item.transientSong) },
            onClick = {
                playerViewModel.recordSearchHistory(item.song.title)
                val playbackQueue = if (transientSongs.any { it.song.id == song.id }) {
                    transientSongs
                } else {
                    listOf(item.transientSong)
                }
                playerViewModel.showAndPlayTransientSong(
                    item.transientSong,
                    playbackQueue,
                    queueName
                )
            },
            sourceLabel = item.platform.label
        )
    }
}
