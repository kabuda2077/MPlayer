package com.theveloper.pixelplay.presentation.components

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.theveloper.pixelplay.data.model.TransientOnlineSong

@Composable
fun OnlineSongActionsSheet(
    song: TransientOnlineSong,
    onDismiss: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onDownload: () -> Unit,
    onFavorite: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onPlay: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    isFavorite: Boolean = false
) {
    val context = LocalContext.current

    SongInfoBottomSheet(
        song = song.song,
        isFavorite = isFavorite,
        onToggleFavorite = onFavorite,
        onDismiss = onDismiss,
        onPlaySong = {
            onPlay?.invoke() ?: onPlayNext()
        },
        onAddToQueue = onAddToQueue,
        onAddNextToQueue = onPlayNext,
        onAddToPlayList = onAddToPlaylist,
        onDownload = onDownload,
        onShare = {
            if (onShare != null) {
                onShare()
            } else {
                try {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, "${song.song.title} - ${song.song.displayArtist}")
                    }
                    context.startActivity(Intent.createChooser(intent, "分享歌曲"))
                } catch (_: Exception) {}
            }
        }
    )
}
