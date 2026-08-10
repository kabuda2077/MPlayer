package com.theveloper.pixelplay.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.model.TransientOnlineSong

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun OnlineSongActionsSheet(
    song: TransientOnlineSong,
    onDismiss: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onDownload: () -> Unit,
    onFavorite: () -> Unit,
    onAddToPlaylist: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = song.song.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = song.song.displayArtist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onPlayNext, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.search_online_action_play_next))
            }
            TextButton(onClick = onAddToQueue, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.search_online_action_add_to_queue))
            }
            TextButton(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.download_action))
            }
            if (song.canSaveToLibrary) {
                TextButton(onClick = onFavorite, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.search_online_action_favorite))
                }
                TextButton(onClick = onAddToPlaylist, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.search_online_action_add_to_playlist))
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}
