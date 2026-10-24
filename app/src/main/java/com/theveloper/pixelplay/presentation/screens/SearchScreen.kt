@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.theveloper.pixelplay.presentation.screens

import com.theveloper.pixelplay.presentation.navigation.navigateSafely

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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

enum class SearchScope(val labelResId: Int) {
    ALL(R.string.search_scope_all),
    LIBRARY(R.string.search_scope_library),
    ONLINE(R.string.search_scope_online)
}

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
        gradientColors = listOf(Color(0xFFE53935), Color(0xFFFF7043)),
        icon = Icons.Rounded.MusicNote
    ),
    SearchCategoryItem(
        title = "欧美潮流",
        subtitle = "全球榜单先锋",
        queryKeyword = "欧美流行",
        gradientColors = listOf(Color(0xFF7B1FA2), Color(0xFFBA68C8)),
        icon = Icons.Rounded.Headphones
    ),
    SearchCategoryItem(
        title = "摇滚力量",
        subtitle = "吉他与现场狂想",
        queryKeyword = "摇滚",
        gradientColors = listOf(Color(0xFF263238), Color(0xFF455A64)),
        icon = Icons.Rounded.GraphicEq
    ),
    SearchCategoryItem(
        title = "说唱嘻哈",
        subtitle = "节拍与街头律动",
        queryKeyword = "说唱",
        gradientColors = listOf(Color(0xFFE65100), Color(0xFFFFB74D)),
        icon = Icons.Rounded.Whatshot
    ),
    SearchCategoryItem(
        title = "轻松治愈",
        subtitle = "专注与静心睡眠",
        queryKeyword = "轻音乐",
        gradientColors = listOf(Color(0xFF00695C), Color(0xFF4DB6AC)),
        icon = Icons.Rounded.NightsStay
    ),
    SearchCategoryItem(
        title = "经典怀旧",
        subtitle = "岁月的回响金曲",
        queryKeyword = "经典老歌",
        gradientColors = listOf(Color(0xFFBF360C), Color(0xFF8D6E63)),
        icon = Icons.Rounded.Radio
    ),
    SearchCategoryItem(
        title = "动漫游戏",
        subtitle = "二次元与原声大碟",
        queryKeyword = "ACG",
        gradientColors = listOf(Color(0xFFAD1457), Color(0xFFF06292)),
        icon = Icons.Rounded.AutoAwesome
    ),
    SearchCategoryItem(
        title = "电音派对",
        subtitle = "低音冲击与舞池",
        queryKeyword = "电音",
        gradientColors = listOf(Color(0xFF1565C0), Color(0xFF42A5F5)),
        icon = Icons.Rounded.PlayArrow
    )
)

private data class SearchUiSlice(
    val onlineSearchResults: ImmutableList<OnlineSearchResultItem> = persistentListOf(),
    val onlineSearchPlatform: OnlineSearchPlatform = OnlineSearchPlatform.NETEASE,
    val onlineSearchLoading: Boolean = false,
    val onlineSearchError: String? = null,
    val librarySearchResults: ImmutableList<SearchResultItem> = persistentListOf(),
    val librarySearchLoading: Boolean = false,
    val librarySearchError: String? = null,
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
    var selectedScope by rememberSaveable { mutableStateOf(SearchScope.ALL) }
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
                    librarySearchResults = uiState.librarySearchResults,
                    librarySearchLoading = uiState.librarySearchLoading,
                    librarySearchError = uiState.librarySearchError,
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
        searchBarVisible = true,
        searchQuery = searchQuery,
        onSearchQueryChange = {
            searchQuery = it
            playerViewModel.updateSearchQuery(it)
        },
        onSearch = { query ->
            keyboardController?.hide()
            playerViewModel.recordSearchHistory(query)
        },
        onClearSearch = {
            searchQuery = ""
            playerViewModel.updateSearchQuery("")
        },
        searchInputModifier = Modifier.focusRequester(searchInputFocusRequester),
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
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = 8.dp,
                bottom = MiniPlayerHeight + paddingValues.calculateBottomPadding() + 96.dp
            ),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            // --- 状态 1：未输入搜索词（探索中心） ---
            if (searchQuery.isBlank()) {
                item(key = "search_start_content") {
                    SearchStartContent(
                        history = searchUiState.searchHistory,
                        suggestions = searchUiState.popularSearchTerms,
                        currentPlatform = currentOnlinePlatform,
                        onPlatformSelected = { platform ->
                            playerViewModel.updateOnlineSearchPlatform(platform)
                        },
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

            // --- 状态 2：输入了搜索词（搜索结果分段过滤） ---
            if (searchQuery.isNotBlank()) {
                // Apple Music 风格的分段切换栏：[ 全部 ] [ 资料库 ] [ 在线 ]
                item(key = "search_scope_selector") {
                    SearchScopeSegmentedControl(
                        selectedScope = selectedScope,
                        onScopeSelected = { selectedScope = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 6.dp)
                    )
                }

                // 资料库部分（全部 或 资料库模式下显示）
                if (selectedScope == SearchScope.ALL || selectedScope == SearchScope.LIBRARY) {
                    val showLibrarySection = selectedScope == SearchScope.LIBRARY ||
                        searchUiState.librarySearchResults.isNotEmpty() ||
                        searchUiState.librarySearchLoading ||
                        searchUiState.librarySearchError != null

                    if (showLibrarySection) {
                        item(key = "search_library_header") {
                            Text(
                                text = stringResource(R.string.search_scope_library),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 18.dp, top = 10.dp, bottom = 4.dp)
                            )
                        }
                        item(key = "search_library_results") {
                            when {
                                searchUiState.librarySearchLoading && searchUiState.librarySearchResults.isEmpty() ->
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 24.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                                    }
                                searchUiState.librarySearchError != null && searchUiState.librarySearchResults.isEmpty() ->
                                    Text(
                                        text = searchUiState.librarySearchError.orEmpty(),
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(18.dp)
                                    )
                                searchUiState.librarySearchResults.isEmpty() && !searchUiState.librarySearchLoading ->
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 28.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = stringResource(R.string.search_no_library_results),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                else -> SearchResultsList(
                                    results = searchUiState.librarySearchResults,
                                    searchQuery = searchQuery,
                                    playerViewModel = playerViewModel,
                                    onItemSelected = {
                                        playerViewModel.recordSearchHistory(searchQuery)
                                    },
                                    currentPlayingSongId = stablePlayerState.currentSong?.id,
                                    isPlaying = stablePlayerState.isPlaying,
                                    onSongMoreOptionsClick = playerViewModel::selectSongForInfo,
                                    navController = navController,
                                    embedded = true
                                )
                            }
                        }
                    }
                }

                // 在线音乐部分（全部 或 在线模式下显示）
                if (selectedScope == SearchScope.ALL || selectedScope == SearchScope.ONLINE) {
                    item(key = "search_online_header") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.search_scope_online),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            SearchPlatformSwitcher(
                                currentPlatform = currentOnlinePlatform,
                                onPlatformSelected = { platform ->
                                    playerViewModel.updateOnlineSearchPlatform(platform)
                                }
                            )
                        }
                    }

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

@Composable
private fun SearchScopeSegmentedControl(
    selectedScope: SearchScope,
    onScopeSelected: (SearchScope) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SearchScope.entries.forEach { scope ->
            val isSelected = scope == selectedScope
            val bgColor by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent,
                label = "scope_bg"
            )
            val textColor by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                label = "scope_text"
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(17.dp))
                    .background(bgColor)
                    .clickable { onScopeSelected(scope) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(scope.labelResId),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = textColor,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
fun SearchPlatformSwitcher(
    currentPlatform: OnlineSearchPlatform,
    onPlatformSelected: (OnlineSearchPlatform) -> Unit,
    modifier: Modifier = Modifier
) {
    val platforms = remember {
        listOf(
            OnlineSearchPlatform.NETEASE,
            OnlineSearchPlatform.QQ,
            OnlineSearchPlatform.KUGOU,
            OnlineSearchPlatform.KUWO
        )
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        platforms.forEach { platform ->
            val isSelected = platform == currentPlatform
            val bgColor by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                label = "platform_bg"
            )
            val textColor by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                label = "platform_text"
            )

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(bgColor)
                    .clickable { onPlatformSelected(platform) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = platform.label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = textColor,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun TrendingItemRow(
    rank: Int,
    term: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isTop3 = rank <= 3
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 38.dp),
        shape = RoundedCornerShape(10.dp),
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 7.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "%02d".format(rank),
                style = MaterialTheme.typography.labelLarge,
                fontFamily = GoogleSansRounded,
                fontWeight = FontWeight.Bold,
                color = if (isTop3) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                },
                modifier = Modifier.width(28.dp)
            )
            Text(
                text = term,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isTop3) FontWeight.SemiBold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
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
            .height(86.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(gradientBrush)
            .clickable(onClick = onClick)
            .padding(14.dp)
    ) {
        Icon(
            imageVector = category.icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.22f),
            modifier = Modifier
                .size(52.dp)
                .align(Alignment.BottomEnd)
                .graphicsLayer {
                    rotationZ = -14f
                    translationX = 10.dp.toPx()
                    translationY = 12.dp.toPx()
                }
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth(0.82f)
        ) {
            Text(
                text = category.title,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = GoogleSansRounded,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = category.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchStartContent(
    history: List<String>,
    suggestions: List<String>,
    currentPlatform: OnlineSearchPlatform,
    onPlatformSelected: (OnlineSearchPlatform) -> Unit,
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
            .padding(horizontal = 20.dp, vertical = 18.dp)
    ) {
        // --- 1. 搜索历史 ---
        if (history.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.search_history_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = GoogleSansRounded,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                IconButton(
                    onClick = { showClearDialog = true },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.search_history_clear),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                history.forEach { historyItem ->
                    Surface(
                        onClick = { onSuggestionClick(historyItem) },
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = historyItem,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 180.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .clickable { onDeleteHistoryItem(historyItem) },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.search_history_delete_single),
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }

        // --- 2. 热门搜索 ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.search_popular_title),
                style = MaterialTheme.typography.titleMedium,
                fontFamily = GoogleSansRounded,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            SearchPlatformSwitcher(
                currentPlatform = currentPlatform,
                onPlatformSelected = onPlatformSelected
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 双列 Top 10 排行榜
        val terms = suggestions.take(10)
        val leftColumn = terms.take(5)
        val rightColumn = if (terms.size > 5) terms.drop(5).take(5) else emptyList()
        val maxRows = maxOf(leftColumn.size, rightColumn.size)

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            for (i in 0 until maxRows) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val leftTerm = leftColumn.getOrNull(i)
                    if (leftTerm != null) {
                        TrendingItemRow(
                            rank = i + 1,
                            term = leftTerm,
                            onClick = { onSuggestionClick(leftTerm) },
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }

                    val rightTerm = rightColumn.getOrNull(i)
                    if (rightTerm != null) {
                        TrendingItemRow(
                            rank = i + 6,
                            term = rightTerm,
                            onClick = { onSuggestionClick(rightTerm) },
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        // --- 3. 探索分类（Apple Music / Spotify 风格彩色卡片） ---
        Spacer(modifier = Modifier.height(28.dp))

        Text(
            text = stringResource(R.string.search_categories_title),
            style = MaterialTheme.typography.titleMedium,
            fontFamily = GoogleSansRounded,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(14.dp))

        val categoryRows = remember { SEARCH_CATEGORIES.chunked(2) }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            categoryRows.forEach { rowPair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
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
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
        errorMessage != null && results.isEmpty() -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
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
    Box(modifier = Modifier.padding(bottom = 8.dp)) {
        EnhancedSongListItem(
            song = song,
            isPlaying = isPlaying,
            isCurrentSong = currentPlayingSongId == song.id,
            albumArtSize = 48.dp,
            customShape = RoundedCornerShape(18.dp),
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
