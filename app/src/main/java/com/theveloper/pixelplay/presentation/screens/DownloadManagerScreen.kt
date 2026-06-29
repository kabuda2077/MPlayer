package com.theveloper.pixelplay.presentation.screens

import android.text.format.Formatter
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.download.DownloadEntity
import com.theveloper.pixelplay.data.download.DownloadStatus
import com.theveloper.pixelplay.presentation.components.MiniPlayerHeight
import com.theveloper.pixelplay.presentation.components.OptimizedAlbumArt
import com.theveloper.pixelplay.presentation.viewmodel.DownloadManagerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadManagerScreen(
    onBackClick: () -> Unit,
    viewModel: DownloadManagerViewModel = hiltViewModel()
) {
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val sizeLabel = Formatter.formatShortFileSize(context, stats.totalSizeBytes)
    val hasActiveDownloads = downloads.any { DownloadStatus.isActive(it.status) }
    val hasRetryableDownloads = downloads.any { it.status == DownloadStatus.FAILED || it.status == DownloadStatus.CANCELED }
    val hasFinishedProblemRecords = hasRetryableDownloads
    val hasCompletedDownloads = downloads.any { it.status == DownloadStatus.COMPLETED }
    var showMenu by remember { mutableStateOf(false) }
    var showCancelAllConfirm by remember { mutableStateOf(false) }
    var showDeleteAllConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.download_manager_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.common_more_options))
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.download_cancel_all_active)) },
                            enabled = hasActiveDownloads,
                            onClick = {
                                showMenu = false
                                showCancelAllConfirm = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.download_retry_failed_canceled)) },
                            enabled = hasRetryableDownloads,
                            onClick = {
                                showMenu = false
                                viewModel.retryFailedAndCanceled()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.download_clear_failed_canceled)) },
                            enabled = hasFinishedProblemRecords,
                            onClick = {
                                showMenu = false
                                viewModel.clearFailedAndCanceled()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.download_delete_all_downloaded)) },
                            enabled = hasCompletedDownloads,
                            onClick = {
                                showMenu = false
                                showDeleteAllConfirm = true
                            }
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = MiniPlayerHeight + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 18.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                DownloadSummaryCard(
                    count = stats.completedCount,
                    sizeLabel = sizeLabel
                )
            }

            if (downloads.isEmpty()) {
                item {
                    EmptyDownloadsCard()
                }
            } else {
                items(downloads, key = { it.contentUriString }) { entity ->
                    DownloadItemCard(
                        entity = entity,
                        onCancel = { viewModel.cancel(entity) },
                        onRetry = { viewModel.retry(entity) },
                        onDelete = { viewModel.delete(entity) }
                    )
                }
            }
        }
    }

    if (showCancelAllConfirm) {
        AlertDialog(
            onDismissRequest = { showCancelAllConfirm = false },
            title = { Text(stringResource(R.string.download_cancel_all_title)) },
            text = { Text(stringResource(R.string.download_cancel_all_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCancelAllConfirm = false
                        viewModel.cancelAllActive()
                    }
                ) {
                    Text(stringResource(R.string.download_cancel_all_active))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelAllConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    if (showDeleteAllConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteAllConfirm = false },
            title = { Text(stringResource(R.string.download_delete_all_title)) },
            text = { Text(stringResource(R.string.download_delete_all_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteAllConfirm = false
                        viewModel.deleteCompletedDownloads()
                    }
                ) {
                    Text(stringResource(R.string.common_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

@Composable
private fun DownloadSummaryCard(count: Int, sizeLabel: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Rounded.Download,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.download_summary_title, count),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.download_summary_size, sizeLabel),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun EmptyDownloadsCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier.padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Rounded.Download,
                contentDescription = null,
                modifier = Modifier.size(34.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
            )
            Text(
                text = stringResource(R.string.download_empty_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.download_empty_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DownloadItemCard(
    entity: DownloadEntity,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val statusText = downloadStatusLabel(entity.status)
    val sizeText = if (entity.sizeBytes > 0L) Formatter.formatShortFileSize(context, entity.sizeBytes) else ""
    val titleText = listOf(entity.title, entity.artist)
        .filter { it.isNotBlank() }
        .joinToString(" - ")
    val meta = listOfNotNull(
        entity.actualQuality?.takeIf { it.isNotBlank() } ?: entity.requestedQuality.takeIf { it != "preferred" },
        sizeText.takeIf { it.isNotBlank() },
        statusText
    ).joinToString("  •  ")

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OptimizedAlbumArt(
                    uri = entity.albumArtUriString,
                    title = entity.title,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = titleText,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    when (entity.status) {
                        DownloadStatus.QUEUED,
                        DownloadStatus.DOWNLOADING -> {
                            IconButton(
                                onClick = onCancel,
                                modifier = Modifier.size(40.dp),
                                colors = IconButtonDefaults.iconButtonColors(
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            ) {
                                Icon(
                                    Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.common_cancel),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        DownloadStatus.FAILED,
                        DownloadStatus.CANCELED -> {
                            IconButton(
                                onClick = onRetry,
                                modifier = Modifier.size(40.dp),
                                colors = IconButtonDefaults.iconButtonColors(
                                    contentColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Icon(
                                    Icons.Rounded.Refresh,
                                    contentDescription = stringResource(R.string.download_retry),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(40.dp),
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(
                            Icons.Rounded.Delete,
                            contentDescription = stringResource(R.string.common_delete),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            if (entity.status == DownloadStatus.DOWNLOADING || entity.status == DownloadStatus.QUEUED) {
                LinearProgressIndicator(
                    progress = { entity.progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                )
            }

            if (!entity.errorMessage.isNullOrBlank()) {
                Text(
                    text = entity.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun downloadStatusLabel(status: Int): String = when (status) {
    DownloadStatus.QUEUED -> stringResource(R.string.download_status_queued)
    DownloadStatus.DOWNLOADING -> stringResource(R.string.download_status_downloading)
    DownloadStatus.COMPLETED -> stringResource(R.string.download_status_completed)
    DownloadStatus.FAILED -> stringResource(R.string.download_status_failed)
    DownloadStatus.CANCELED -> stringResource(R.string.download_status_canceled)
    else -> stringResource(R.string.download_status_unknown)
}
