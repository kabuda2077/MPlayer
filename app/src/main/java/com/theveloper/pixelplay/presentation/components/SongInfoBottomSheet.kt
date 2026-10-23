package com.theveloper.pixelplay.presentation.components

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.ai.SongMetadata
import com.theveloper.pixelplay.data.download.DownloadTarget
import com.theveloper.pixelplay.data.media.CoverArtUpdate
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.presentation.viewmodel.SongInfoBottomSheetViewModel
import com.theveloper.pixelplay.ui.theme.GoogleSansRounded
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("UNUSED_PARAMETER")
fun SongInfoBottomSheet(
    song: Song,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onDismiss: () -> Unit,
    onPlaySong: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddNextToQueue: () -> Unit,
    onAddToPlayList: () -> Unit,
    onDownload: (() -> Unit)? = null,
    onDeleteFromDevice: (activity: Activity, song: Song, onResult: (Boolean) -> Unit) -> Unit = { _, _, _ -> },
    onNavigateToAlbum: () -> Unit = {},
    onNavigateToArtist: () -> Unit = {},
    onNavigateToArtistById: (Long) -> Unit = { onNavigateToArtist() },
    onNavigateToGenre: () -> Unit = {},
    onEditSong: (
        title: String,
        artist: String,
        album: String,
        albumArtist: String,
        composer: String,
        genre: String,
        lyrics: String,
        trackNumber: Int,
        discNumber: Int?,
        replayGainTrackGainDb: String,
        replayGainAlbumGainDb: String,
        coverArtUpdate: CoverArtUpdate?
    ) -> Unit = { _, _, _, _, _, _, _, _, _, _, _, _ -> },
    generateAiMetadata: suspend (List<String>) -> Result<SongMetadata> = { Result.failure(Exception()) },
    removeFromListTrigger: () -> Unit = {},
    isGeneratingMetadata: Boolean = false,
    aiMetadataSuccess: Boolean = false,
    aiError: String? = null,
    onRetryMetadata: () -> Unit = {},
    songInfoViewModel: SongInfoBottomSheetViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val shareChooserTitle = stringResource(R.string.song_info_share_chooser_title)
    val errorShareSongFormat = stringResource(R.string.song_info_error_share_song)
    var showEditSheet by remember { mutableStateOf(false) }

    val evenCornerRadiusElems = 26.dp
    val albumArtShape = remember(evenCornerRadiusElems) {
        AbsoluteSmoothCornerShape(
            cornerRadiusTR = evenCornerRadiusElems, smoothnessAsPercentBR = 60, cornerRadiusBR = evenCornerRadiusElems,
            smoothnessAsPercentTL = 60, cornerRadiusTL = evenCornerRadiusElems, smoothnessAsPercentBL = 60,
            cornerRadiusBL = evenCornerRadiusElems, smoothnessAsPercentTR = 60
        )
    }

    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Expanded,
        confirmValueChange = { true }
    )

    ModalBottomSheet(
        onDismissRequest = {
            if (!showEditSheet) {
                onDismiss()
            }
        },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
            // Header: Cover + Title + Artist + Edit button
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SmartImage(
                        model = song.albumArtUriString,
                        contentDescription = stringResource(R.string.common_album_art),
                        shape = albumArtShape,
                        modifier = Modifier.size(80.dp),
                        contentScale = ContentScale.Crop
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = song.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            fontFamily = GoogleSansRounded,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = song.displayArtist.ifBlank { song.artist }.ifBlank { "Unknown Artist" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = GoogleSansRounded,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    val isEditable = remember(song) { songInfoViewModel.isSongEditable(song) }
                    if (isEditable) {
                        FilledTonalIconButton(
                            modifier = Modifier
                                .fillMaxHeight()
                                .padding(vertical = 6.dp),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceBright,
                                contentColor = MaterialTheme.colorScheme.onSurface
                            ),
                            onClick = { showEditSheet = true },
                        ) {
                            Icon(
                                modifier = Modifier.padding(horizontal = 8.dp),
                                imageVector = Icons.Rounded.Edit,
                                contentDescription = stringResource(R.string.song_info_cd_edit_metadata)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Unified 3-row capsule actions
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SongActionsLayout(
                    isFavorite = isFavorite,
                    onPlay = onPlaySong,
                    onToggleFavorite = onToggleFavorite,
                    onShare = {
                        try {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "audio/*"
                                putExtra(Intent.EXTRA_STREAM, song.contentUriString.toUri())
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(
                                Intent.createChooser(
                                    shareIntent,
                                    shareChooserTitle
                                )
                            )
                        } catch (e: Exception) {
                            Toast.makeText(
                                context,
                                errorShareSongFormat.format(e.localizedMessage ?: ""),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    },
                    onAddToQueue = onAddToQueue,
                    onAddNextToQueue = onAddNextToQueue,
                    onAddToPlaylist = onAddToPlayList,
                    onDownload = {
                        if (onDownload != null) {
                            onDownload()
                        } else {
                            val target = DownloadTarget.fromSong(song)
                            if (target != null) {
                                Toast.makeText(context, "下载任务已提交", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "该歌曲已在本地设备", Toast.LENGTH_SHORT).show()
                            }
                        }
                        onDismiss()
                    }
                )

                Spacer(Modifier.height(8.dp))
                Spacer(Modifier.navigationBarsPadding())
            }
        }
    }

    EditSongSheet(
        visible = showEditSheet,
        song = song,
        onDismiss = { showEditSheet = false },
        onSave = { title, artist, album, albumArtist, composer, genre, lyrics, trackNumber, discNumber, replayGainTrackGainDb, replayGainAlbumGainDb, coverArt ->
            onEditSong(
                title,
                artist,
                album,
                albumArtist,
                composer,
                genre,
                lyrics,
                trackNumber,
                discNumber,
                replayGainTrackGainDb,
                replayGainAlbumGainDb,
                coverArt
            )
            showEditSheet = false
        },
    )
}
