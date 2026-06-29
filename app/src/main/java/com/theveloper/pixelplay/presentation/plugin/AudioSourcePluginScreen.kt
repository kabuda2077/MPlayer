package com.theveloper.pixelplay.presentation.plugin

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theveloper.pixelplay.data.database.PluginEntity
import com.theveloper.pixelplay.data.plugin.PluginQuality
import com.theveloper.pixelplay.data.plugin.PluginRuntimeState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioSourcePluginScreen(
    onBack: () -> Unit,
    onMessage: (String) -> Unit,
    viewModel: AudioSourcePluginViewModel = hiltViewModel()
) {
    val uiState = viewModel.uiState.collectAsStateWithLifecycle().value
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.importPlugin(uri)
    }

    LaunchedEffect(uiState.message) {
        val message = uiState.message ?: return@LaunchedEffect
        onMessage(message)
        viewModel.clearMessage()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("音源插件", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { importer.launch("*/*") }) {
                        Icon(Icons.Rounded.UploadFile, contentDescription = "导入插件")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            if (uiState.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                item {
                    Button(
                        onClick = { importer.launch("*/*") },
                        enabled = !uiState.busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.UploadFile, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("导入 .js 插件")
                    }
                }
                if (uiState.plugins.isEmpty()) {
                    item { EmptyPluginCard() }
                } else {
                    items(uiState.plugins, key = { it.id }) { plugin ->
                        PluginCard(
                            plugin = plugin,
                            runtimeState = uiState.runtimeState,
                            preferredQuality = uiState.preferredQuality,
                            enabled = !uiState.busy,
                            onPreferredQualityChange = viewModel::setPreferredQuality,
                            onEnable = { viewModel.enablePlugin(plugin.id) },
                            onDisable = { viewModel.disablePlugin(plugin.id) },
                            onDelete = { viewModel.deletePlugin(plugin.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PluginCard(
    plugin: PluginEntity,
    runtimeState: PluginRuntimeState,
    preferredQuality: PluginQuality,
    enabled: Boolean,
    onPreferredQualityChange: (PluginQuality) -> Unit,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
    onDelete: () -> Unit
) {
    val isActiveRuntime = plugin.enabled && runtimeState.pluginId == plugin.id
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Extension,
                    contentDescription = null,
                    tint = if (plugin.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        plugin.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    val detail = listOf(plugin.version, plugin.author)
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                    if (detail.isNotBlank()) {
                        Text(
                            detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Switch(
                    checked = plugin.enabled,
                    onCheckedChange = { checked -> if (checked) onEnable() else onDisable() },
                    enabled = enabled
                )
            }

            PluginStatusText(plugin, runtimeState)

            if (isActiveRuntime) {
                PlatformQualitySelector(
                    state = runtimeState,
                    selectedQuality = preferredQuality,
                    onQualitySelected = onPreferredQualityChange,
                    enabled = enabled && runtimeState.initialized
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "更新 ${formatTime(plugin.updatedAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onDelete, enabled = enabled && !plugin.enabled) {
                    Icon(Icons.Rounded.Delete, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text("删除")
                }
            }
        }
    }
}

@Composable
private fun PluginStatusText(plugin: PluginEntity, state: PluginRuntimeState) {
    val status = when {
        !plugin.enabled -> "未启用"
        state.pluginId != plugin.id -> "等待初始化"
        state.initializing -> "正在初始化"
        state.initialized -> "已初始化"
        !state.errorMessage.isNullOrBlank() -> state.errorMessage
        else -> "初始化失败"
    }
    Text(
        status,
        style = MaterialTheme.typography.bodySmall,
        color = if (plugin.enabled && state.initialized) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
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
                "插件初始化后显示平台和音质能力。",
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
                    label = { Text(quality.displayName) }
                )
            }
        }
        Text(
            "播放时会先尝试歌曲原平台；原平台当前音质失败后，按默认顺序查找其他平台候选，然后再向低音质重试。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EmptyPluginCard() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("还没有插件", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "导入 LX 风格 JS 插件后，网易云和 QQ 音乐的播放 URL 会完全交给插件解析。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatTime(timestamp: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))

private val MUSIC_SOURCE_ORDER = listOf("wy", "kg", "kw", "tx", "mg")

private fun sourceDisplayName(source: String): String? = when (source) {
    "wy" -> "网易云"
    "tx" -> "QQ音乐"
    "kw" -> "酷我"
    "kg" -> "酷狗"
    "mg" -> "咪咕"
    else -> null
}
