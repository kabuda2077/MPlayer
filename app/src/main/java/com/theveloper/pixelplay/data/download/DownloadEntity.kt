package com.theveloper.pixelplay.data.download

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

object DownloadStatus {
    const val QUEUED = 0
    const val DOWNLOADING = 1
    const val COMPLETED = 2
    const val FAILED = 3
    const val CANCELED = 4

    fun isActive(status: Int): Boolean = status == QUEUED || status == DOWNLOADING
}

object DownloadStorageMode {
    const val PUBLIC_MEDIASTORE = "PUBLIC_MEDIASTORE"
}

@Entity(
    tableName = "downloads",
    indices = [
        Index(value = ["song_id"]),
        Index(value = ["status"]),
        Index(value = ["updated_at"]),
        Index(value = ["source", "platform_song_id"])
    ]
)
data class DownloadEntity(
    @PrimaryKey
    @ColumnInfo(name = "content_uri_string")
    val contentUriString: String,
    @ColumnInfo(name = "song_id")
    val songId: Long?,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "artist")
    val artist: String,
    @ColumnInfo(name = "album")
    val album: String,
    @ColumnInfo(name = "album_art_uri_string")
    val albumArtUriString: String?,
    @ColumnInfo(name = "duration")
    val duration: Long,
    @ColumnInfo(name = "source")
    val source: String,
    @ColumnInfo(name = "platform_song_id")
    val platformSongId: String,
    @ColumnInfo(name = "requested_quality")
    val requestedQuality: String,
    @ColumnInfo(name = "actual_quality")
    val actualQuality: String?,
    @ColumnInfo(name = "status")
    val status: Int,
    @ColumnInfo(name = "progress")
    val progress: Float,
    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long,
    @ColumnInfo(name = "mime_type")
    val mimeType: String?,
    @ColumnInfo(name = "file_path")
    val filePath: String?,
    @ColumnInfo(name = "public_uri")
    val publicUri: String? = null,
    @ColumnInfo(name = "relative_path")
    val relativePath: String? = null,
    @ColumnInfo(name = "display_name")
    val displayName: String? = null,
    @ColumnInfo(name = "storage_mode")
    val storageMode: String = DownloadStorageMode.PUBLIC_MEDIASTORE,
    @ColumnInfo(name = "local_song_id")
    val localSongId: Long? = null,
    @ColumnInfo(name = "error_message")
    val errorMessage: String?,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)

data class DownloadStats(
    val completedCount: Int,
    val totalSizeBytes: Long
)

data class DownloadedAudioFile(
    val contentUriString: String,
    val publicUri: String?,
    val mimeType: String,
    val sizeBytes: Long,
    val quality: String?
)
