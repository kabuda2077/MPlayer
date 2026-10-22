package com.theveloper.pixelplay.data.model

import android.os.Parcelable
import androidx.compose.runtime.Immutable
import com.theveloper.pixelplay.utils.MediaSourceClassifier
import com.theveloper.pixelplay.utils.MediaSourceKind
import kotlinx.parcelize.Parcelize

/**
 * Encapsulates the media origin and identifier of an audio item,
 * decoupling domain entities like [Song] from vendor-specific fields.
 */
@Immutable
@Parcelize
data class MediaSourceOrigin(
    val kind: MediaSourceKind,
    val sourceId: String? = null
) : Parcelable {
    val isOnline: Boolean get() = kind.isOnline
    val isLocal: Boolean get() = !kind.isOnline

    companion object {
        fun fromSong(song: Song): MediaSourceOrigin {
            val kind = MediaSourceClassifier.classify(song.contentUriString)
            val id = MediaSourceClassifier.idPart(song.contentUriString)
                ?: when (kind) {
                    MediaSourceKind.NETEASE -> song.neteaseId?.toString()
                    MediaSourceKind.QQ_MUSIC -> song.qqMusicMid
                    else -> null
                }
            return MediaSourceOrigin(kind = kind, sourceId = id)
        }
    }
}
