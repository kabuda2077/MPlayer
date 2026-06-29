package com.theveloper.pixelplay.data.download

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DownloadEntity)

    @Query("""
        SELECT * FROM downloads
        WHERE public_uri IS NOT NULL
           OR status != 2
        ORDER BY updated_at DESC
    """)
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads ORDER BY updated_at DESC")
    suspend fun getAll(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE content_uri_string = :contentUri LIMIT 1")
    fun observeByContentUri(contentUri: String): Flow<DownloadEntity?>

    @Query("SELECT * FROM downloads WHERE content_uri_string = :contentUri LIMIT 1")
    suspend fun getByContentUri(contentUri: String): DownloadEntity?

    @Query("""
        SELECT * FROM downloads
        WHERE content_uri_string = :contentUri
          AND status = :completedStatus
          AND public_uri IS NOT NULL
        LIMIT 1
    """)
    suspend fun getCompletedByContentUri(
        contentUri: String,
        completedStatus: Int = DownloadStatus.COMPLETED
    ): DownloadEntity?

    @Query("""
        SELECT * FROM downloads
        WHERE source = :source
          AND platform_song_id = :platformSongId
          AND status = :completedStatus
          AND public_uri IS NOT NULL
        ORDER BY updated_at DESC
        LIMIT 1
    """)
    suspend fun getCompletedBySourcePlatform(
        source: String,
        platformSongId: String,
        completedStatus: Int = DownloadStatus.COMPLETED
    ): DownloadEntity?

    @Query("""
        UPDATE downloads
        SET status = :status,
            progress = :progress,
            updated_at = :updatedAt,
            error_message = :errorMessage
        WHERE content_uri_string = :contentUri
    """)
    suspend fun updateStatus(
        contentUri: String,
        status: Int,
        progress: Float,
        updatedAt: Long,
        errorMessage: String? = null
    )

    @Query("""
        UPDATE downloads
        SET status = :status,
            progress = :progress,
            size_bytes = :sizeBytes,
            mime_type = :mimeType,
            actual_quality = :actualQuality,
            file_path = :filePath,
            public_uri = :publicUri,
            relative_path = :relativePath,
            display_name = :displayName,
            storage_mode = :storageMode,
            local_song_id = :localSongId,
            error_message = NULL,
            updated_at = :updatedAt
        WHERE content_uri_string = :contentUri
    """)
    suspend fun markCompleted(
        contentUri: String,
        status: Int = DownloadStatus.COMPLETED,
        progress: Float = 1f,
        sizeBytes: Long,
        mimeType: String?,
        actualQuality: String?,
        filePath: String?,
        publicUri: String? = null,
        relativePath: String? = null,
        displayName: String? = null,
        storageMode: String = DownloadStorageMode.PUBLIC_MEDIASTORE,
        localSongId: Long? = null,
        updatedAt: Long
    )

    @Query("""
        UPDATE downloads
        SET progress = :progress,
            size_bytes = :sizeBytes,
            updated_at = :updatedAt
        WHERE content_uri_string = :contentUri
          AND status = :downloadingStatus
    """)
    suspend fun updateProgress(
        contentUri: String,
        progress: Float,
        sizeBytes: Long,
        updatedAt: Long,
        downloadingStatus: Int = DownloadStatus.DOWNLOADING
    )

    @Query("DELETE FROM downloads WHERE content_uri_string = :contentUri")
    suspend fun deleteByContentUri(contentUri: String)

    @Query("DELETE FROM downloads WHERE status IN (:statuses)")
    suspend fun deleteByStatuses(statuses: List<Int>)

    @Query("DELETE FROM downloads")
    suspend fun deleteAll()

    @Query("""
        SELECT
            COUNT(*) AS completedCount,
            COALESCE(SUM(size_bytes), 0) AS totalSizeBytes
        FROM downloads
        WHERE status = :completedStatus
          AND public_uri IS NOT NULL
    """)
    fun observeStats(completedStatus: Int = DownloadStatus.COMPLETED): Flow<DownloadStats>

    @Query("""
        SELECT
            COUNT(*) AS completedCount,
            COALESCE(SUM(size_bytes), 0) AS totalSizeBytes
        FROM downloads
        WHERE status = :completedStatus
          AND public_uri IS NOT NULL
    """)
    suspend fun getStats(completedStatus: Int = DownloadStatus.COMPLETED): DownloadStats
}
