package com.theveloper.pixelplay.presentation.plugin

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.database.PluginEntity
import com.theveloper.pixelplay.data.plugin.PluginQuality
import com.theveloper.pixelplay.data.plugin.PluginRuntimeState
import com.theveloper.pixelplay.data.plugin.PluginScriptContent
import com.theveloper.pixelplay.presentation.components.CollapsibleCommonTopBar
import com.theveloper.pixelplay.presentation.components.MiniPlayerHeight
import com.theveloper.pixelplay.ui.theme.GoogleSansRounded
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioSourcePluginScreen(
    onBack: () -> Unit,
    onMessage: (String) -> Unit,
    viewModel: AudioSourcePluginViewModel = hiltViewModel()
) {
    val uiState = viewModel.uiState.collectAsStateWithLifecycle().value
    var showUrlDialog by remember { mutableStateOf(false) }
    var updateSourcePlugin by remember { mutableStateOf<PluginEntity?>(null) }
    var selectedPluginForOptions by remember { mutableStateOf<PluginEntity?>(null) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.importPlugin(uri)
    }

    LaunchedEffect(uiState.message) {
        val message = uiState.message ?: return@LaunchedEffect
        onMessage(message)
        viewModel.clearMessage()
    }

    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    val lazyListState = rememberLazyListState()

    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val minTopBarHeight = 64.dp + statusBarHeight
    val maxTopBarHeight = 168.dp

    val minTopBarHeightPx = with(density) { minTopBarHeight.toPx() }
    val maxTopBarHeightPx = with(density) { maxTopBarHeight.toPx() }
    val topBarHeight = remember { Animatable(maxTopBarHeightPx) }
    var collapseFraction by remember { mutableStateOf(0f) }

    LaunchedEffect(topBarHeight.value) {
        collapseFraction = 1f - (
            (topBarHeight.value - minTopBarHeightPx) / (maxTopBarHeightPx - minTopBarHeightPx)
        ).coerceIn(0f, 1f)
    }

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val delta = available.y
                val isScrollingDown = delta < 0
                if (!isScrollingDown && (lazyListState.firstVisibleItemIndex > 0 || lazyListState.firstVisibleItemScrollOffset > 0)) {
                    return Offset.Zero
                }
                val previousHeight = topBarHeight.value
                val newHeight = (previousHeight + delta).coerceIn(minTopBarHeightPx, maxTopBarHeightPx)
                val consumed = newHeight - previousHeight
                if (consumed.roundToInt() != 0) {
                    coroutineScope.launch { topBarHeight.snapTo(newHeight) }
                }
                val canConsumeScroll = !(isScrollingDown && newHeight == minTopBarHeightPx)
                return if (canConsumeScroll) Offset(0f, consumed) else Offset.Zero
            }
        }
    }

    LaunchedEffect(lazyListState.isScrollInProgress) {
        if (!lazyListState.isScrollInProgress) {
            val shouldExpand = topBarHeight.value > (minTopBarHeightPx + maxTopBarHeightPx) / 2
            val canExpand = lazyListState.firstVisibleItemIndex == 0 && lazyListState.firstVisibleItemScrollOffset == 0
            val targetValue = if (shouldExpand && canExpand) maxTopBarHeightPx else minTopBarHeightPx
            if (topBarHeight.value != targetValue) {
                coroutineScope.launch {
                    topBarHeight.animateTo(targetValue, spring(stiffness = Spring.StiffnessMedium))
                }
            }
        }
    }

    val currentTopBarHeightDp = with(density) { topBarHeight.value.toDp() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(nestedScrollConnection)
    ) {
        LazyColumn(
            state = lazyListState,
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = currentTopBarHeightDp + 8.dp,
                bottom = MiniPlayerHeight + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 18.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item {
                PluginImportHeaderCard(
                    hasPlugins = uiState.plugins.isNotEmpty(),
                    busy = uiState.busy,
                    onImportUrl = { showUrlDialog = true },
                    onImportFile = { importer.launch("*/*") }
                )
            }

            if (uiState.plugins.isNotEmpty()) {
                item {
                    Text(
                        text = "已安装 · ${uiState.plugins.size}",
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = GoogleSansRounded,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }

            if (uiState.plugins.isEmpty()) {
                item { EmptyPluginCard() }
            } else {
                items(uiState.plugins.sortedByDescending { it.createdAt }, key = { it.id }) { plugin ->
                    PluginCard(
                        modifier = Modifier.animateItem(
                            placementSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMedium
                            )
                        ),
                        plugin = plugin,
                        runtimeState = uiState.runtimeState,
                        preferredQuality = uiState.preferredQuality,
                        busy = uiState.busy,
                        onPreferredQualityChange = viewModel::setPreferredQuality,
                        onEnable = { viewModel.enablePlugin(plugin.id) },
                        onDisable = { viewModel.disablePlugin(plugin.id) },
                        onOptionsClick = { selectedPluginForOptions = plugin },
                        onCheckUpdate = {
                            if (plugin.sourceUrl.isNullOrBlank()) updateSourcePlugin = plugin
                            else viewModel.checkUpdate(plugin.id)
                        }
                    )
                }
            }
        }


        CollapsibleCommonTopBar(
            title = stringResource(R.string.plugin_screen_title),
            collapseFraction = collapseFraction,
            headerHeight = currentTopBarHeightDp,
            onBackClick = onBack,
            expandedTitleStartPadding = 20.dp,
            collapsedTitleStartPadding = 68.dp
        )
    }

    selectedPluginForOptions?.let { plugin ->
        PluginOptionsBottomSheet(
            plugin = plugin,
            enabled = !uiState.busy,
            onDismiss = { selectedPluginForOptions = null },
            onEditSource = {
                selectedPluginForOptions = null
                updateSourcePlugin = plugin
            },
            onCheckUpdate = {
                selectedPluginForOptions = null
                if (plugin.sourceUrl.isNullOrBlank()) updateSourcePlugin = plugin
                else viewModel.checkUpdate(plugin.id)
            },
            onDelete = {
                selectedPluginForOptions = null
                viewModel.deletePlugin(plugin.id)
            }
        )
    }

    if (showUrlDialog || updateSourcePlugin != null) {
        val plugin = updateSourcePlugin
        PluginUrlDialog(
            initialUrl = plugin?.sourceUrl.orEmpty(),
            forUpdate = plugin != null,
            onDismiss = { showUrlDialog = false; updateSourcePlugin = null },
            onSubmit = { url ->
                if (plugin == null) viewModel.importFromUrl(url) else viewModel.checkUpdate(plugin.id, url)
                showUrlDialog = false
                updateSourcePlugin = null
            }
        )
    }

    uiState.pendingUpdate?.let { update ->
        val current = uiState.plugins.firstOrNull { it.id == update.pluginId }
        AlertDialog(
            onDismissRequest = viewModel::dismissUpdate,
            shape = RoundedCornerShape(24.dp),
            title = {
                Text(
                    text = "发现脚本更新",
                    fontFamily = GoogleSansRounded,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(update.metadata.name, fontWeight = FontWeight.SemiBold)
                    Text("${current?.version.orEmpty().ifBlank { "未标注版本" }} → ${update.metadata.version.ifBlank { "未标注版本" }}")
                    if (current?.version == update.metadata.version) Text("版本号未变，但脚本内容已变化。")
                    Text("来源：${update.sourceUrl}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (current?.enabled == true) "更新后会重新加载当前音源。" else "更新后保留当前启用状态。")
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::applyUpdate, enabled = !uiState.busy) {
                    Text(if (uiState.busy) "更新中…" else "更新")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissUpdate, enabled = !uiState.busy) { Text("稍后") }
            }
        )
    }
}

@Composable
private fun PluginImportHeaderCard(
    hasPlugins: Boolean,
    busy: Boolean,
    onImportUrl: () -> Unit,
    onImportFile: () -> Unit
) {
    if (hasPlugins) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Rounded.Extension,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "导入与扩展音源",
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = GoogleSansRounded,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "为在线歌曲提供播放与搜索解析来源",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    FilledTonalButton(
                        onClick = onImportUrl,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Rounded.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("链接导入", fontWeight = FontWeight.SemiBold)
                    }
                    FilledTonalButton(
                        onClick = onImportFile,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Rounded.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("本地文件", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    } else {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.Extension,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
                Text(
                    text = "拓展你的音乐来源",
                    style = MaterialTheme.typography.titleLarge,
                    fontFamily = GoogleSansRounded,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "导入音源插件，为在线歌曲提供播放来源。每次可启用一个插件。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onImportUrl,
                        enabled = !busy,
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Rounded.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("链接导入")
                    }
                    OutlinedButton(
                        onClick = onImportFile,
                        enabled = !busy,
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Rounded.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("本地文件")
                    }
                }
            }
        }
    }
}

@Composable
private fun PluginCard(
    modifier: Modifier = Modifier,
    plugin: PluginEntity,
    runtimeState: PluginRuntimeState,
    preferredQuality: PluginQuality,
    busy: Boolean,
    onPreferredQualityChange: (PluginQuality) -> Unit,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
    onOptionsClick: () -> Unit,
    onCheckUpdate: () -> Unit
) {
    var showQualitySettings by rememberSaveable(plugin.id) { mutableStateOf(false) }

    LaunchedEffect(plugin.enabled) {
        if (!plugin.enabled) showQualitySettings = false
    }

    val cardColor by animateColorAsState(
        targetValue = if (plugin.enabled) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "pluginCardColor"
    )

    val iconContainerColor by animateColorAsState(
        targetValue = if (plugin.enabled) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "pluginIconContainerColor"
    )

    val iconTint by animateColorAsState(
        targetValue = if (plugin.enabled) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "pluginIconTint"
    )

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = cardColor,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = iconContainerColor,
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.Extension,
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = plugin.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = GoogleSansRounded,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val detail = listOf(plugin.version, plugin.author)
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                    if (detail.isNotBlank()) {
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Switch(
                    checked = plugin.enabled,
                    onCheckedChange = { checked ->
                        if (!busy) {
                            if (checked) onEnable() else onDisable()
                        }
                    }
                )
            }

            PluginStatusText(plugin, runtimeState)

            Spacer(Modifier.height(12.dp))

            AnimatedVisibility(
                visible = plugin.enabled,
                enter = expandVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                ) + fadeIn(animationSpec = tween(150)),
                exit = shrinkVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                ) + fadeOut(animationSpec = tween(100))
            ) {
                Column {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { showQualitySettings = !showQualitySettings }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "平台与音质 · ${preferredQuality.displayName}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                imageVector = if (showQualitySettings) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                contentDescription = if (showQualitySettings) "收起" else "展开",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    AnimatedVisibility(visible = showQualitySettings) {
                        Column {
                            Spacer(Modifier.height(8.dp))
                            PlatformQualitySelector(
                                state = runtimeState,
                                selectedQuality = preferredQuality,
                                onQualitySelected = onPreferredQualityChange,
                                enabled = !busy && runtimeState.initialized
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                FilledTonalButton(
                    onClick = onCheckUpdate,
                    enabled = !busy,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("检查更新", style = MaterialTheme.typography.labelMedium)
                }

                Spacer(Modifier.weight(1f))

                IconButton(
                    onClick = onOptionsClick,
                    enabled = !busy,
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "更多操作")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PluginOptionsBottomSheet(
    plugin: PluginEntity,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onEditSource: () -> Unit,
    onCheckUpdate: () -> Unit,
    onDelete: () -> Unit
) {
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Expanded)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Text(
                    text = plugin.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontFamily = GoogleSansRounded,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.plugin_options_title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(bottom = 8.dp)
            )

            PluginSheetActionRow(
                icon = Icons.Rounded.Refresh,
                iconTint = MaterialTheme.colorScheme.primary,
                iconContainerColor = MaterialTheme.colorScheme.primaryContainer,
                title = stringResource(R.string.plugin_check_update),
                subtitle = if (plugin.sourceUrl.isNullOrBlank()) "配置直链后可检查更新" else "从已关联直链检查最新内容",
                enabled = enabled,
                onClick = onCheckUpdate
            )

            PluginSheetActionRow(
                icon = Icons.Rounded.Link,
                iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                iconContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                title = stringResource(R.string.plugin_edit_source),
                subtitle = if (plugin.sourceUrl.isNullOrBlank()) "尚未设置更新链接" else plugin.sourceUrl.orEmpty(),
                enabled = enabled,
                onClick = onEditSource
            )

            val canDelete = !plugin.enabled
            PluginSheetActionRow(
                icon = Icons.Rounded.DeleteOutline,
                iconTint = if (canDelete) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                iconContainerColor = if (canDelete) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                title = stringResource(R.string.plugin_delete),
                subtitle = if (plugin.enabled) stringResource(R.string.plugin_delete_disabled_hint) else stringResource(R.string.plugin_delete_confirm_hint),
                enabled = enabled && canDelete,
                isDestructive = canDelete,
                onClick = onDelete
            )
        }
    }
}

@Composable
private fun PluginSheetActionRow(
    icon: ImageVector,
    iconTint: androidx.compose.ui.graphics.Color,
    iconContainerColor: androidx.compose.ui.graphics.Color,
    title: String,
    subtitle: String,
    enabled: Boolean,
    isDestructive: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.42f),
        color = androidx.compose.ui.graphics.Color.Transparent
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = iconContainerColor,
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isDestructive) MaterialTheme.colorScheme.error.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PluginUrlDialog(
    initialUrl: String,
    forUpdate: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    var url by remember(initialUrl) { mutableStateOf(initialUrl) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = {
            Text(
                text = if (forUpdate) "设置更新链接" else "从链接导入",
                fontFamily = GoogleSansRounded,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("填写 JavaScript 脚本直链。保存后可从此链接检查更新。")
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; error = null },
                    label = { Text("脚本链接") },
                    placeholder = { Text("https://example.com/source.js") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = { error?.let { Text(it) } },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = url.isNotBlank(),
                onClick = {
                    runCatching { PluginScriptContent.normalizeUrl(url) }.fold(
                        onSuccess = onSubmit,
                        onFailure = { error = it.message }
                    )
                }
            ) {
                Text(if (forUpdate) "保存并检查" else "导入")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun PluginStatusText(plugin: PluginEntity, state: PluginRuntimeState) {
    if (!plugin.enabled) return
    val message = when {
        !state.errorMessage.isNullOrBlank() -> state.errorMessage
        state.pluginId == plugin.id && state.initializing -> "正在连接音源…"
        state.pluginId != plugin.id -> "正在准备音源…"
        else -> null
    } ?: return

    val isError = !state.errorMessage.isNullOrBlank()
    Column {
        Spacer(Modifier.height(6.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PlatformQualitySelector(
    state: PluginRuntimeState,
    selectedQuality: PluginQuality,
    onQualitySelected: (PluginQuality) -> Unit,
    enabled: Boolean
) {
    val caps = state.capabilities.sources.values
        .filter { sourceDisplayName(it.source) != null }
        .sortedBy { MUSIC_SOURCE_ORDER.indexOf(it.source).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }
    val sourceKey = caps.joinToString("|") { it.source }
    var selectedSource by remember(sourceKey) { mutableStateOf(caps.firstOrNull()?.source) }
    if (selectedSource !in caps.map { it.source }) {
        selectedSource = caps.firstOrNull()?.source
    }
    val selectedCap = caps.firstOrNull { it.source == selectedSource }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("支持平台", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        if (caps.isEmpty()) {
            Text(
                "音源就绪后，这里会显示可用平台和音质。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            caps.forEach { cap ->
                FilterChip(
                    selected = cap.source == selectedSource,
                    onClick = { selectedSource = cap.source },
                    enabled = enabled,
                    shape = RoundedCornerShape(12.dp),
                    label = { Text(sourceDisplayName(cap.source).orEmpty()) }
                )
            }
        }
        Text("首选音质", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        val supportedQualities = selectedCap?.normalizedQualities().orEmpty()
        val visibleQualities = if (supportedQualities.isNotEmpty()) {
            PluginQuality.preferenceOrder.filter { it in supportedQualities }
        } else {
            emptyList()
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            visibleQualities.forEach { quality ->
                FilterChip(
                    selected = quality == selectedQuality,
                    onClick = { onQualitySelected(quality) },
                    enabled = enabled,
                    shape = RoundedCornerShape(12.dp),
                    label = { Text(quality.displayName) }
                )
            }
        }
        Text(
            "优先使用所选音质，无法播放时会自动尝试其他来源或较低音质。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EmptyPluginCard() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.size(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Rounded.Extension,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
            Text(
                text = "还没有音源",
                style = MaterialTheme.typography.titleMedium,
                fontFamily = GoogleSansRounded,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "从链接或本地文件导入，再启用音源，即可用于在线歌曲播放。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private val MUSIC_SOURCE_ORDER = listOf("wy", "kg", "kw", "tx")

private fun sourceDisplayName(source: String): String? = when (source) {
    "wy" -> "网易云"
    "tx" -> "QQ音乐"
    "kw" -> "酷我"
    "kg" -> "酷狗"
    else -> null
}
