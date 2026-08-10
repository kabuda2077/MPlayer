package com.theveloper.pixelplay.data.download

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.theveloper.pixelplay.data.plugin.PluginAudioSourceResolver
import com.theveloper.pixelplay.data.plugin.PluginStreamRequestHeaders
import com.theveloper.pixelplay.data.stream.CloudStreamSecurity
import com.theveloper.pixelplay.data.worker.SyncManager
import com.theveloper.pixelplay.utils.PlaybackTrace
import com.theveloper.pixelplay.utils.PlaybackTraceContext
import com.theveloper.pixelplay.utils.PlaybackTraceOrigin
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val downloadDao: DownloadDao,
    private val publicStore: PublicAudioDownloadStore,
    private val metadataWriter: OnlineAudioMetadataWriter,
    private val pluginResolver: PluginAudioSourceResolver,
    private val okHttpClient: OkHttpClient,
    private val syncManager: SyncManager
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val contentUri = inputData.getString(KEY_CONTENT_URI) ?: return@withContext Result.failure()
        val entity = downloadDao.getByContentUri(contentUri) ?: return@withContext Result.failure()
        val traceContext = PlaybackTraceContext(
            requestId = inputData.getString(KEY_TRACE_ID) ?: PlaybackTrace.newRequestId(),
            mediaId = entity.songId?.toString() ?: "${entity.source}:${entity.platformSongId}",
            sourceUri = contentUri,
            origin = PlaybackTraceOrigin.DOWNLOAD
        )
        val target = DownloadTarget.fromContentUri(contentUri) ?: return@withContext fail(
            contentUri = contentUri,
            message = "暂不支持该来源下载"
        )
        var pendingPublicDownload: PublicAudioDownloadStore.PendingPublicDownload? = null
        var tempDownloadFile: File? = null

        try {
            downloadDao.updateStatus(
                contentUri = contentUri,
                status = DownloadStatus.DOWNLOADING,
                progress = 0f,
                updatedAt = System.currentTimeMillis(),
                errorMessage = null
            )
            setProgress(workDataOf(KEY_PROGRESS to 0))

            val stream = pluginResolver.resolve(
                source = target.source,
                platformSongId = target.platformSongId,
                traceContext = traceContext
            )
            if (!CloudStreamSecurity.isSafeRemoteStreamUrl(
                    url = stream.url,
                    allowedHostSuffixes = emptySet(),
                    allowHttpForAllowedHosts = true,
                    allowHttpWithoutHostAllowlist = true
                )
            ) {
                return@withContext fail(contentUri, "插件返回了无效音源")
            }

            val requestBuilder = PluginStreamRequestHeaders.applyTo(
                builder = Request.Builder().url(stream.url),
                headers = stream.headers
            )
            okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
                if (response.code != 200) {
                    return@withContext fail(contentUri, "下载请求失败：HTTP ${response.code}")
                }
                val contentType = response.header("Content-Type")
                if (!CloudStreamSecurity.isSupportedAudioContentType(contentType)) {
                    return@withContext fail(contentUri, "下载内容不是音频")
                }
                val contentLengthHeader = response.header("Content-Length")
                if (!CloudStreamSecurity.isAcceptableContentLength(contentLengthHeader)) {
                    return@withContext fail(contentUri, "音频文件过大")
                }

                val body = response.body
                val expectedBytes = contentLengthHeader?.toLongOrNull()
                    ?: body.contentLength().takeIf { it > 0L }
                if (expectedBytes != null && expectedBytes > CloudStreamSecurity.MAX_STREAM_CONTENT_LENGTH_BYTES) {
                    return@withContext fail(contentUri, "音频文件过大")
                }

                val responseMimeType = publicStore.normalizeMimeType(contentType, stream.mimeType)
                val extension = publicStore.extensionForMimeType(responseMimeType)
                val tempFile = File.createTempFile(
                    "mplayer_download_",
                    ".$extension",
                    applicationContext.cacheDir
                )
                tempDownloadFile = tempFile

                val actualSize = FileOutputStream(tempFile).use { output ->
                    writeBodyToOutputStream(
                        contentUri = contentUri,
                        expectedBytes = expectedBytes,
                        input = body.byteStream(),
                        output = output
                    )
                }

                if (expectedBytes != null && expectedBytes > 0L && actualSize != expectedBytes) {
                    return@withContext fail(contentUri, "下载文件不完整")
                }

                val sniffedMimeType = AudioFormatSniffer.detectMimeType(tempFile)
                val normalizedMimeType = sniffedMimeType?.let { publicStore.normalizeMimeType(it, responseMimeType) }
                    ?: responseMimeType
                val normalizedExtension = publicStore.extensionForMimeType(normalizedMimeType)
                val metadataFile = normalizeTempFileExtension(tempFile, normalizedExtension)
                tempDownloadFile = metadataFile

                metadataWriter.write(
                    context = applicationContext,
                    audioFile = metadataFile,
                    entity = entity
                )

                val pending = publicStore.createPending(
                    entity = entity,
                    mimeType = normalizedMimeType,
                    fallbackMimeType = stream.mimeType
                )
                pendingPublicDownload = pending
                publicStore.openOutputStream(pending).use { output ->
                    FileInputStream(metadataFile).use { input ->
                        input.copyTo(output)
                    }
                }

                publicStore.commit(pending)
                pendingPublicDownload = null
                val committedSize = metadataFile.length().takeIf { it > 0L } ?: actualSize
                downloadDao.markCompleted(
                    contentUri = contentUri,
                    sizeBytes = committedSize,
                    mimeType = pending.mimeType,
                    actualQuality = stream.quality,
                    filePath = null,
                    publicUri = pending.uri.toString(),
                    relativePath = pending.relativePath,
                    displayName = pending.displayName,
                    storageMode = DownloadStorageMode.PUBLIC_MEDIASTORE,
                    localSongId = null,
                    updatedAt = System.currentTimeMillis()
                )
                setProgress(workDataOf(KEY_PROGRESS to 100))
                syncManager.notifyPublicDownloadCompleted()
                Timber.tag(PlaybackTrace.LOG_TAG).d(
                    "stage=download_completed requestId=%s mediaId=%s originSource=%s actualSource=%s id=%s quality=%s size=%d uri=%s",
                    traceContext.requestId,
                    traceContext.mediaId,
                    target.source.lxSource,
                    stream.source.orEmpty(),
                    target.platformSongId,
                    stream.quality.orEmpty(),
                    committedSize,
                    pending.uri
                )
                Result.success()
        }
    } catch (e: CancellationException) {
            pendingPublicDownload?.let { publicStore.delete(it.uri.toString()) }
            downloadDao.updateStatus(
                contentUri = contentUri,
                status = DownloadStatus.CANCELED,
                progress = 0f,
                updatedAt = System.currentTimeMillis(),
                errorMessage = null
            )
            throw e
        } catch (e: Exception) {
            pendingPublicDownload?.let { publicStore.delete(it.uri.toString()) }
            Timber.tag(PlaybackTrace.LOG_TAG).w(
                e,
                "stage=download_failed requestId=%s mediaId=%s title=%s",
                traceContext.requestId,
                traceContext.mediaId,
                entity.title
            )
            fail(contentUri, e.message ?: "下载失败")
        } finally {
            tempDownloadFile?.delete()
        }
    }

    private suspend fun writeBodyToOutputStream(
        contentUri: String,
        expectedBytes: Long?,
        input: java.io.InputStream,
        output: OutputStream
    ): Long {
        var downloaded = 0L
        var lastProgressAt = 0L
        var lastProgressPercent = -1
        input.use { source ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = source.read(buffer)
                if (read == -1) break
                output.write(buffer, 0, read)
                downloaded += read

                if (downloaded > CloudStreamSecurity.MAX_STREAM_CONTENT_LENGTH_BYTES) {
                    throw IllegalStateException("音频文件过大")
                }

                val now = System.currentTimeMillis()
                val progress = if (expectedBytes != null && expectedBytes > 0L) {
                    (downloaded.toFloat() / expectedBytes.toFloat()).coerceIn(0f, 0.99f)
                } else {
                    0f
                }
                val progressPercent = (progress * 100).toInt()
                if (now - lastProgressAt >= PROGRESS_UPDATE_INTERVAL_MS || progressPercent != lastProgressPercent) {
                    lastProgressAt = now
                    lastProgressPercent = progressPercent
                    downloadDao.updateProgress(
                        contentUri = contentUri,
                        progress = progress,
                        sizeBytes = downloaded,
                        updatedAt = now
                    )
                    setProgress(workDataOf(KEY_PROGRESS to progressPercent))
                }
            }
        }
        output.flush()
        return downloaded
    }

    private fun normalizeTempFileExtension(tempFile: File, extension: String): File {
        val currentExtension = tempFile.extension.lowercase()
        if (currentExtension == extension.lowercase()) return tempFile

        val targetFile = File(tempFile.parentFile, "${tempFile.nameWithoutExtension}.$extension")
        return if (tempFile.renameTo(targetFile)) {
            targetFile
        } else {
            tempFile.copyTo(targetFile, overwrite = true)
            tempFile.delete()
            targetFile
        }
    }

    private suspend fun fail(contentUri: String, message: String): Result {
        downloadDao.updateStatus(
            contentUri = contentUri,
            status = DownloadStatus.FAILED,
            progress = 0f,
            updatedAt = System.currentTimeMillis(),
            errorMessage = message
        )
        return Result.failure(workDataOf(KEY_ERROR to message))
    }

    companion object {
        const val KEY_CONTENT_URI = "content_uri"
        const val KEY_TRACE_ID = "trace_id"
        const val KEY_PROGRESS = "progress"
        const val KEY_ERROR = "error"
        private const val PROGRESS_UPDATE_INTERVAL_MS = 500L
    }
}
