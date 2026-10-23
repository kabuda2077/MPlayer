package com.theveloper.pixelplay.presentation.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.presentation.components.subcomps.TightWrapText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Unified 3-row capsule action buttons for song sheets (Library & Online).
 */
@Composable
fun SongActionsLayout(
    isFavorite: Boolean,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    onShare: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddNextToQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Row 1: Play + Favorite + Share
        Row1Capsules(
            isFavorite = isFavorite,
            onPlay = onPlay,
            onToggleFavorite = onToggleFavorite,
            onShare = onShare
        )

        // Row 2: Add to Queue + Play Next
        Row2Capsules(
            onAddToQueue = onAddToQueue,
            onAddNextToQueue = onAddNextToQueue
        )

        // Row 3: Add to Playlist + Download
        Row3Capsules(
            onAddToPlaylist = onAddToPlaylist,
            onDownload = onDownload
        )
    }
}

@Composable
private fun Row1Capsules(
    isFavorite: Boolean,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    onShare: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var clickPending by remember { mutableStateOf(false) }

    val playInteraction = remember { MutableInteractionSource() }
    val isPlayPressed by playInteraction.collectIsPressedAsState()
    var playVisualPressed by remember { mutableStateOf(false) }
    LaunchedEffect(isPlayPressed) {
        if (isPlayPressed) playVisualPressed = true
        else { delay(180); playVisualPressed = false }
    }

    val favInteraction = remember { MutableInteractionSource() }
    val isFavPressed by favInteraction.collectIsPressedAsState()
    var favVisualPressed by remember { mutableStateOf(false) }
    LaunchedEffect(isFavPressed) {
        if (isFavPressed) favVisualPressed = true
        else { delay(180); favVisualPressed = false }
    }

    val shareInteraction = remember { MutableInteractionSource() }
    val isSharePressed by shareInteraction.collectIsPressedAsState()
    var shareVisualPressed by remember { mutableStateOf(false) }
    LaunchedEffect(isSharePressed) {
        if (isSharePressed) shareVisualPressed = true
        else { delay(180); shareVisualPressed = false }
    }

    val pressSpec = spring<Float>(dampingRatio = 0.8f, stiffness = 300f)
    val pressFractionPlay by animateFloatAsState(if (playVisualPressed) 1f else 0f, pressSpec, label = "PlayP")
    val pressFractionFav by animateFloatAsState(if (favVisualPressed) 1f else 0f, pressSpec, label = "FavP")
    val pressFractionShare by animateFloatAsState(if (shareVisualPressed) 1f else 0f, pressSpec, label = "ShareP")

    val weightPlay = (0.50f + 0.08f * pressFractionPlay - 0.04f * pressFractionFav - 0.04f * pressFractionShare).coerceAtLeast(0.1f)
    val weightFav = (0.25f + 0.08f * pressFractionFav - 0.04f * pressFractionPlay - 0.04f * pressFractionShare).coerceAtLeast(0.1f)
    val weightShare = (0.25f + 0.08f * pressFractionShare - 0.04f * pressFractionPlay - 0.04f * pressFractionFav).coerceAtLeast(0.1f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Play
        FilledTonalButton(
            modifier = Modifier
                .weight(weightPlay)
                .heightIn(min = 66.dp),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ),
            contentPadding = PaddingValues(horizontal = 12.dp),
            shape = CircleShape,
            interactionSource = playInteraction,
            onClick = {
                if (clickPending) return@FilledTonalButton
                clickPending = true
                playVisualPressed = true
                coroutineScope.launch {
                    delay(180)
                    playVisualPressed = false
                    clickPending = false
                    onPlay()
                }
            }
        ) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = stringResource(R.string.common_play),
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(6.dp))
            TightWrapText(
                text = stringResource(R.string.common_play),
                modifier = Modifier.padding(end = 4.dp),
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
                lineHeight = 20.sp
            )
        }

        // Favorite
        FilledTonalIconButton(
            modifier = Modifier
                .weight(weightFav)
                .fillMaxHeight(),
            onClick = {
                if (clickPending) return@FilledTonalIconButton
                clickPending = true
                favVisualPressed = true
                coroutineScope.launch {
                    delay(180)
                    favVisualPressed = false
                    clickPending = false
                    onToggleFavorite()
                }
            },
            shape = CircleShape,
            interactionSource = favInteraction,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (isFavorite) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                else MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = if (isFavorite) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        ) {
            Icon(
                modifier = Modifier.size(FloatingActionButtonDefaults.LargeIconSize),
                imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                contentDescription = stringResource(
                    if (isFavorite) R.string.song_info_cd_remove_from_favorites else R.string.song_info_cd_add_to_favorites
                )
            )
        }

        // Share
        FilledTonalIconButton(
            modifier = Modifier
                .weight(weightShare)
                .fillMaxHeight(),
            onClick = {
                if (clickPending) return@FilledTonalIconButton
                clickPending = true
                shareVisualPressed = true
                coroutineScope.launch {
                    delay(180)
                    shareVisualPressed = false
                    clickPending = false
                    onShare()
                }
            },
            shape = CircleShape,
            interactionSource = shareInteraction,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        ) {
            Icon(
                modifier = Modifier.size(FloatingActionButtonDefaults.LargeIconSize),
                imageVector = Icons.Rounded.Share,
                contentDescription = stringResource(R.string.song_info_cd_share_song_file)
            )
        }
    }
}

@Composable
private fun Row2Capsules(
    onAddToQueue: () -> Unit,
    onAddNextToQueue: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var clickPending by remember { mutableStateOf(false) }

    val queueInteraction = remember { MutableInteractionSource() }
    val isQueuePressed by queueInteraction.collectIsPressedAsState()
    var queueVisualPressed by remember { mutableStateOf(false) }
    LaunchedEffect(isQueuePressed) {
        if (isQueuePressed) queueVisualPressed = true
        else { delay(180); queueVisualPressed = false }
    }

    val nextInteraction = remember { MutableInteractionSource() }
    val isNextPressed by nextInteraction.collectIsPressedAsState()
    var nextVisualPressed by remember { mutableStateOf(false) }
    LaunchedEffect(isNextPressed) {
        if (isNextPressed) nextVisualPressed = true
        else { delay(180); nextVisualPressed = false }
    }

    val pressSpec = spring<Float>(dampingRatio = 0.8f, stiffness = 300f)
    val pressFractionQueue by animateFloatAsState(if (queueVisualPressed) 1f else 0f, pressSpec, label = "QueueP")
    val pressFractionNext by animateFloatAsState(if (nextVisualPressed) 1f else 0f, pressSpec, label = "NextP")

    val weightQueue = (0.54f + 0.08f * pressFractionQueue - 0.08f * pressFractionNext).coerceAtLeast(0.1f)
    val weightNext = (0.46f + 0.08f * pressFractionNext - 0.08f * pressFractionQueue).coerceAtLeast(0.1f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Add to Queue
        FilledTonalButton(
            modifier = Modifier
                .weight(weightQueue)
                .heightIn(min = 66.dp),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer
            ),
            contentPadding = PaddingValues(horizontal = 10.dp),
            shape = CircleShape,
            interactionSource = queueInteraction,
            onClick = {
                if (clickPending) return@FilledTonalButton
                clickPending = true
                queueVisualPressed = true
                coroutineScope.launch {
                    delay(180)
                    queueVisualPressed = false
                    clickPending = false
                    onAddToQueue()
                }
            }
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                contentDescription = stringResource(R.string.song_info_cd_add_to_queue)
            )
            Spacer(Modifier.width(6.dp))
            TightWrapText(
                text = stringResource(R.string.song_info_action_add_to_queue),
                modifier = Modifier.padding(end = 4.dp),
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
                lineHeight = 20.sp
            )
        }

        // Play Next
        FilledTonalButton(
            modifier = Modifier
                .weight(weightNext)
                .heightIn(min = 66.dp),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.tertiary,
                contentColor = MaterialTheme.colorScheme.onTertiary
            ),
            contentPadding = PaddingValues(horizontal = 10.dp),
            shape = CircleShape,
            interactionSource = nextInteraction,
            onClick = {
                if (clickPending) return@FilledTonalButton
                clickPending = true
                nextVisualPressed = true
                coroutineScope.launch {
                    delay(180)
                    nextVisualPressed = false
                    clickPending = false
                    onAddNextToQueue()
                }
            }
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                contentDescription = stringResource(R.string.song_info_cd_queue_next)
            )
            Spacer(Modifier.width(6.dp))
            TightWrapText(
                text = stringResource(R.string.song_info_action_queue_next),
                modifier = Modifier.padding(end = 4.dp),
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
                lineHeight = 20.sp
            )
        }
    }
}

@Composable
private fun Row3Capsules(
    onAddToPlaylist: () -> Unit,
    onDownload: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var clickPending by remember { mutableStateOf(false) }

    val playlistInteraction = remember { MutableInteractionSource() }
    val isPlaylistPressed by playlistInteraction.collectIsPressedAsState()
    var playlistVisualPressed by remember { mutableStateOf(false) }
    LaunchedEffect(isPlaylistPressed) {
        if (isPlaylistPressed) playlistVisualPressed = true
        else { delay(180); playlistVisualPressed = false }
    }

    val downloadInteraction = remember { MutableInteractionSource() }
    val isDownloadPressed by downloadInteraction.collectIsPressedAsState()
    var downloadVisualPressed by remember { mutableStateOf(false) }
    LaunchedEffect(isDownloadPressed) {
        if (isDownloadPressed) downloadVisualPressed = true
        else { delay(180); downloadVisualPressed = false }
    }

    val pressSpec = spring<Float>(dampingRatio = 0.8f, stiffness = 300f)
    val pressFractionPlaylist by animateFloatAsState(if (playlistVisualPressed) 1f else 0f, pressSpec, label = "PlaylistP")
    val pressFractionDownload by animateFloatAsState(if (downloadVisualPressed) 1f else 0f, pressSpec, label = "DownloadP")

    val weightPlaylist = (0.5f + 0.08f * pressFractionPlaylist - 0.08f * pressFractionDownload).coerceAtLeast(0.1f)
    val weightDownload = (0.5f + 0.08f * pressFractionDownload - 0.08f * pressFractionPlaylist).coerceAtLeast(0.1f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Add to Playlist
        FilledTonalButton(
            modifier = Modifier
                .weight(weightPlaylist)
                .heightIn(min = 66.dp),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            ),
            contentPadding = PaddingValues(horizontal = 10.dp),
            shape = CircleShape,
            interactionSource = playlistInteraction,
            onClick = {
                if (clickPending) return@FilledTonalButton
                clickPending = true
                playlistVisualPressed = true
                coroutineScope.launch {
                    delay(180)
                    playlistVisualPressed = false
                    clickPending = false
                    onAddToPlaylist()
                }
            }
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.PlaylistAdd,
                contentDescription = stringResource(R.string.search_online_action_add_to_playlist)
            )
            Spacer(Modifier.width(6.dp))
            TightWrapText(
                text = stringResource(R.string.search_online_action_add_to_playlist),
                modifier = Modifier.padding(end = 4.dp),
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
                lineHeight = 20.sp
            )
        }

        // Download
        FilledTonalButton(
            modifier = Modifier
                .weight(weightDownload)
                .heightIn(min = 66.dp),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface
            ),
            contentPadding = PaddingValues(horizontal = 10.dp),
            shape = CircleShape,
            interactionSource = downloadInteraction,
            onClick = {
                if (clickPending) return@FilledTonalButton
                clickPending = true
                downloadVisualPressed = true
                coroutineScope.launch {
                    delay(180)
                    downloadVisualPressed = false
                    clickPending = false
                    onDownload()
                }
            }
        ) {
            Icon(
                imageVector = Icons.Rounded.Download,
                contentDescription = stringResource(R.string.download_action)
            )
            Spacer(Modifier.width(6.dp))
            TightWrapText(
                text = stringResource(R.string.download_action),
                modifier = Modifier.padding(end = 4.dp),
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
                lineHeight = 20.sp
            )
        }
    }
}
