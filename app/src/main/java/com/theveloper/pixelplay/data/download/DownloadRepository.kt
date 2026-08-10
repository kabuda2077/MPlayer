package com.theveloper.pixelplay.data.download

import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.model.TransientOnlineSong
import com.theveloper.pixelplay.data.model.LibrarySong
import com.theveloper.pixelplay.data.plugin.PluginSource
import com.theveloper.pixelplay.utils.MediaSourceClassifier
import com.theveloper.pixelplay.utils.PlaybackTrace
import com.theveloper.pixelplay.utils.PlaybackTraceContext
import com.theveloper.pixelplay.utils.PlaybackTraceOrigin
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

@Singleton
class DownloadRepository @Inject constructor(
    private val downloadDao: DownloadDao,
    private val publicStore: PublicAudioDownloadStore,
    private val workManager: WorkManager
) {
    fun observeDownloads(): Flow<List<DownloadEntity>> = downloadDao.observeAll()

    fun observeStats(): Flow<DownloadStats> = downloadDao.observeStats()

    fun observeDownload(contentUriString: String): Flow<DownloadEntity?> =
        downloadDao.observeByContentUri(contentUriString)

    suspend fun getCompletedAudio(contentUriString: String): DownloadedAudioFile? {
        val entity = downloadDao.getCompletedByContentUri(contentUriString) ?: return null
        return publicStore.completedAudio(entity) ?: run {
            downloadDao.deleteByContentUri(contentUriString)
            null
        }
    }

    private suspend fun startDownloadSong(
        song: Song,
        libraryId: Long?,
        traceContext: PlaybackTraceContext = PlaybackTrace.newContext(
            mediaId = song.id,
            sourceUri = song.contentUriString,
            origin = PlaybackTraceOrigin.DOWNLOAD
        )
    ): DownloadStartResult = withContext(Dispatchers.IO) {
        val target = DownloadTarget.fromSong(song) ?: throw IllegalArgumentException("Unsupported download source")
        val now = System.currentTimeMillis()
        val duplicate = downloadDao.getCompletedBySourcePlatform(target.source.lxSource, target.platformSongId)
        if (duplicate != null && completedAudioForEntity(duplicate) != null) {
            return@withContext DownloadStartResult.ALREADY_DOWNLOADED
        }
        val existing = downloadDao.getByContentUri(song.contentUriString)
        if (existing != null) {
            if (DownloadStatus.isActive(existing.status)) return@withContext DownloadStartResult.ALREADY_ACTIVE
            if (completedAudioForEntity(existing) != null) return@withContext DownloadStartResult.ALREADY_DOWNLOADED
            publicStore.delete(existing.publicUri)
        }

        downloadDao.upsert(
            DownloadEntity(
                contentUriString = song.contentUriString,
                songId = libraryId,
                title = song.title,
                artist = song.displayArtist,
                album = song.album,
                albumArtUriString = song.albumArtUriString,
                duration = song.duration,
                source = target.source.lxSource,
                platformSongId = target.platformSongId,
                requestedQuality = "preferred",
                actualQuality = null,
                status = DownloadStatus.QUEUED,
                progress = 0f,
                sizeBytes = 0L,
                mimeType = null,
                filePath = null,
                publicUri = null,
                relativePath = null,
                displayName = null,
                storageMode = DownloadStorageMode.PUBLIC_MEDIASTORE,
                localSongId = null,
                errorMessage = null,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now
            )
        )

        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setInputData(
                workDataOf(
                    DownloadWorker.KEY_CONTENT_URI to song.contentUriString,
                    DownloadWorker.KEY_TRACE_ID to traceContext.requestId
                )
            )
            .addTag(WORK_TAG)
            .build()

        workManager.enqueueUniqueWork(
            uniqueWorkName(song.contentUriString),
            ExistingWorkPolicy.REPLACE,
            request
        )
        DownloadStartResult.STARTED
    }

    suspend fun startDownload(song: TransientOnlineSong): DownloadStartResult =
        startDownloadSong(song.song, libraryId = null)

    suspend fun startDownload(song: LibrarySong): DownloadStartResult =
        startDownloadSong(song.song, libraryId = song.libraryId)

    suspend fun retry(entity: DownloadEntity) = withContext(Dispatchers.IO) {
        if (entity.status != DownloadStatus.COMPLETED) {
            publicStore.delete(entity.publicUri)
        }
        downloadDao.updateStatus(
            contentUri = entity.contentUriString,
            status = DownloadStatus.QUEUED,
            progress = 0f,
            updatedAt = System.currentTimeMillis(),
            errorMessage = null
        )
        val traceContext = PlaybackTrace.newContext(
            mediaId = entity.songId?.toString() ?: "${entity.source}:${entity.platformSongId}",
            sourceUri = entity.contentUriString,
            origin = PlaybackTraceOrigin.DOWNLOAD
        )
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setInputData(
                workDataOf(
                    DownloadWorker.KEY_CONTENT_URI to entity.contentUriString,
                    DownloadWorker.KEY_TRACE_ID to traceContext.requestId
                )
            )
            .addTag(WORK_TAG)
            .build()
        workManager.enqueueUniqueWork(
            uniqueWorkName(entity.contentUriString),
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    suspend fun cancel(contentUriString: String) = withContext(Dispatchers.IO) {
        workManager.cancelUniqueWork(uniqueWorkName(contentUriString))
        downloadDao.getByContentUri(contentUriString)?.let { publicStore.delete(it.publicUri) }
        downloadDao.updateStatus(
            contentUri = contentUriString,
            status = DownloadStatus.CANCELED,
            progress = 0f,
            updatedAt = System.currentTimeMillis(),
            errorMessage = null
        )
    }

    suspend fun cancelAllActive() = withContext(Dispatchers.IO) {
        downloadDao.getAll()
            .filter { DownloadStatus.isActive(it.status) }
            .forEach { entity ->
                workManager.cancelUniqueWork(uniqueWorkName(entity.contentUriString))
                publicStore.delete(entity.publicUri)
                downloadDao.updateStatus(
                    contentUri = entity.contentUriString,
                    status = DownloadStatus.CANCELED,
                    progress = 0f,
                    updatedAt = System.currentTimeMillis(),
                    errorMessage = null
                )
            }
    }

    suspend fun retryFailedAndCanceled() = withContext(Dispatchers.IO) {
        downloadDao.getAll()
            .filter { it.status == DownloadStatus.FAILED || it.status == DownloadStatus.CANCELED }
            .forEach { retry(it) }
    }

    suspend fun delete(entity: DownloadEntity) = withContext(Dispatchers.IO) {
        workManager.cancelUniqueWork(uniqueWorkName(entity.contentUriString))
        publicStore.delete(entity.publicUri)
        downloadDao.deleteByContentUri(entity.contentUriString)
    }

    suspend fun clearCompletedAndFailed() = withContext(Dispatchers.IO) {
        val downloads = downloadDao.getAll()
        downloads
            .filter { it.status == DownloadStatus.COMPLETED || it.status == DownloadStatus.FAILED || it.status == DownloadStatus.CANCELED }
            .forEach {
                publicStore.delete(it.publicUri)
            }
        downloadDao.deleteByStatuses(listOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED, DownloadStatus.CANCELED))
    }

    suspend fun clearFailedAndCanceled() = withContext(Dispatchers.IO) {
        val statuses = listOf(DownloadStatus.FAILED, DownloadStatus.CANCELED)
        downloadDao.getAll()
            .filter { it.status in statuses }
            .forEach { publicStore.delete(it.publicUri) }
        downloadDao.deleteByStatuses(statuses)
    }

    suspend fun deleteCompletedDownloads() = withContext(Dispatchers.IO) {
        val statuses = listOf(DownloadStatus.COMPLETED)
        downloadDao.getAll()
            .filter { it.status in statuses }
            .forEach { publicStore.delete(it.publicUri) }
        downloadDao.deleteByStatuses(statuses)
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        workManager.cancelAllWorkByTag(WORK_TAG)
        downloadDao.getAll().forEach { publicStore.delete(it.publicUri) }
        downloadDao.deleteAll()
    }

    private fun completedAudioForEntity(entity: DownloadEntity): DownloadedAudioFile? =
        publicStore.completedAudio(entity)

    companion object {
        const val WORK_TAG = "online_audio_download"

        fun uniqueWorkName(contentUriString: String): String =
            "download:${hashKey(contentUriString)}"

        private fun hashKey(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}

enum class DownloadStartResult {
    STARTED,
    ALREADY_ACTIVE,
    ALREADY_DOWNLOADED
}

data class DownloadTarget(
    val source: PluginSource,
    val platformSongId: String
) {
    companion object {
        fun fromSong(song: Song): DownloadTarget? = fromContentUri(song.contentUriString)

        fun fromContentUri(contentUriString: String): DownloadTarget? {
            val source = MediaSourceClassifier.pluginSource(contentUriString) ?: return null
            val id = MediaSourceClassifier.idPart(contentUriString, source)
                ?: return null
            return DownloadTarget(source = source, platformSongId = id)
        }
    }
}
