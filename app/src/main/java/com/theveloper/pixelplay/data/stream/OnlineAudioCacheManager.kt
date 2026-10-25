package com.theveloper.pixelplay.data.stream

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Properties
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

data class OnlineAudioCacheEntry(
    val key: String,
    val file: File,
    val mimeType: String,
    val size: Long,
    val createdAt: Long,
    val lastAccessed: Long,
    val quality: String?
)

@Singleton
class OnlineAudioCacheManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    @Volatile
    var configuredCacheLimitMb: Int = DEFAULT_CACHE_LIMIT_MB

    private val cleanupMutex = Mutex()

    private val cacheDir: File by lazy {
        val targetDir = File(context.cacheDir, CACHE_DIR_NAME)
        migrateLegacyCacheDirIfNeeded(targetDir)
        if (!targetDir.exists()) targetDir.mkdirs()
        targetDir
    }

    private fun migrateLegacyCacheDirIfNeeded(targetDir: File) {
        runCatching {
            val legacyDir = File(context.filesDir, CACHE_DIR_NAME)
            if (legacyDir.exists() && legacyDir.isDirectory) {
                if (!targetDir.exists()) {
                    if (legacyDir.renameTo(targetDir)) return
                }
                legacyDir.listFiles()?.forEach { file ->
                    val dest = File(targetDir, file.name)
                    if (!dest.exists()) {
                        file.renameTo(dest)
                    } else {
                        file.delete()
                    }
                }
                legacyDir.delete()
            }
        }.onFailure {
            Timber.w(it, "Failed to migrate legacy audio cache from filesDir to cacheDir")
        }
    }

    suspend fun get(cacheKey: String): OnlineAudioCacheEntry? = withContext(Dispatchers.IO) {
        if (configuredCacheLimitMb <= 0) return@withContext null
        val hash = hashKey(cacheKey)
        val audioFile = audioFile(hash)
        val metadataFile = metadataFile(hash)
        if (!audioFile.exists() || audioFile.length() <= 0L || !metadataFile.exists()) {
            return@withContext null
        }

        val metadata = readMetadata(metadataFile) ?: return@withContext null
        val now = System.currentTimeMillis()
        metadata.setProperty(KEY_LAST_ACCESSED, now.toString())
        runCatching { writeMetadata(metadataFile, metadata) }
        audioFile.setLastModified(now)
        metadataFile.setLastModified(now)

        OnlineAudioCacheEntry(
            key = cacheKey,
            file = audioFile,
            mimeType = metadata.getProperty(KEY_MIME_TYPE).orEmpty().ifBlank { "application/octet-stream" },
            size = audioFile.length(),
            createdAt = metadata.getProperty(KEY_CREATED_AT)?.toLongOrNull() ?: audioFile.lastModified(),
            lastAccessed = now,
            quality = metadata.getProperty(KEY_QUALITY)?.takeIf { it.isNotBlank() }
        )
    }

    fun createWriter(
        cacheKey: String,
        contentLength: Long?,
        mimeType: String,
        source: String,
        quality: String?
    ): CacheWriter? {
        val limitBytes = configuredCacheLimitMb.toLong() * 1024L * 1024L
        if (limitBytes <= 0L) return null
        if (contentLength == null || contentLength <= 0L || contentLength > limitBytes) return null

        val hash = hashKey(cacheKey)
        val finalFile = audioFile(hash)
        if (finalFile.exists() && finalFile.length() > 0L) return null

        val tempFile = File(cacheDir, "$hash.${System.nanoTime()}.tmp")
        val stream = FileOutputStream(tempFile)
        return CacheWriter(
            manager = this,
            cacheKey = cacheKey,
            hash = hash,
            tempFile = tempFile,
            expectedBytes = contentLength,
            output = stream,
            mimeType = mimeType,
            source = source,
            quality = quality
        )
    }

    suspend fun cleanCacheIfNeeded() {
        val limitBytes = configuredCacheLimitMb.toLong() * 1024L * 1024L
        withContext(Dispatchers.IO) {
            cleanupMutex.withLock {
                clearTemporaryFilesInternal()
                if (limitBytes <= 0L) {
                    clearAllInternal()
                    return@withLock
                }

                val entries = cacheEntriesInternal()
                var totalBytes = entries.sumOf { it.file.length() }
                if (totalBytes <= limitBytes) return@withLock

                entries.sortedBy { it.lastAccessed }.forEach { entry ->
                    if (totalBytes <= limitBytes) return@forEach
                    val size = entry.file.length()
                    deleteEntry(entry.file)
                    totalBytes -= size
                }
            }
        }
    }

    suspend fun clearAll(): Int = withContext(Dispatchers.IO) {
        cleanupMutex.withLock { clearAllInternal() }
    }

    suspend fun getCacheSizeBytes(): Long = withContext(Dispatchers.IO) {
        cleanupMutex.withLock {
            clearTemporaryFilesInternal()
            cacheDir.listFiles { file -> file.isFile && file.name.endsWith(AUDIO_SUFFIX) }
                ?.sumOf { it.length() }
                ?: 0L
        }
    }

    suspend fun getCacheSizeFormatted(): String {
        val bytes = getCacheSizeBytes()
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        return if (mb >= 1024.0) {
            String.format("%.2f GB", mb / 1024.0)
        } else {
            String.format("%.1f MB", mb)
        }
    }

    private fun commit(
        cacheKey: String,
        hash: String,
        tempFile: File,
        expectedBytes: Long,
        mimeType: String,
        source: String,
        quality: String?
    ) {
        val actualBytes = tempFile.length()
        if (actualBytes != expectedBytes) {
            tempFile.delete()
            return
        }

        val finalFile = audioFile(hash)
        val metadataFile = metadataFile(hash)
        if (!tempFile.renameTo(finalFile)) {
            tempFile.copyTo(finalFile, overwrite = true)
            tempFile.delete()
        }

        val now = System.currentTimeMillis()
        val metadata = Properties().apply {
            setProperty(KEY_CACHE_KEY, cacheKey)
            setProperty(KEY_SOURCE, source)
            setProperty(KEY_QUALITY, quality.orEmpty())
            setProperty(KEY_MIME_TYPE, mimeType)
            setProperty(KEY_SIZE, actualBytes.toString())
            setProperty(KEY_CREATED_AT, now.toString())
            setProperty(KEY_LAST_ACCESSED, now.toString())
        }
        writeMetadata(metadataFile, metadata)
    }

    private fun cacheEntriesInternal(): List<OnlineAudioCacheEntry> {
        return cacheDir.listFiles { file -> file.isFile && file.name.endsWith(AUDIO_SUFFIX) }
            ?.mapNotNull { file ->
                val hash = file.name.removeSuffix(AUDIO_SUFFIX)
                val metadata = readMetadata(metadataFile(hash)) ?: return@mapNotNull null
                OnlineAudioCacheEntry(
                    key = metadata.getProperty(KEY_CACHE_KEY).orEmpty(),
                    file = file,
                    mimeType = metadata.getProperty(KEY_MIME_TYPE).orEmpty(),
                    size = file.length(),
                    createdAt = metadata.getProperty(KEY_CREATED_AT)?.toLongOrNull() ?: file.lastModified(),
                    lastAccessed = metadata.getProperty(KEY_LAST_ACCESSED)?.toLongOrNull() ?: file.lastModified(),
                    quality = metadata.getProperty(KEY_QUALITY)?.takeIf { it.isNotBlank() }
                )
            }
            .orEmpty()
    }

    private fun clearAllInternal(): Int {
        var deleted = 0
        cacheDir.listFiles()?.forEach { file ->
            if (file.isFile && file.delete()) deleted++
        }
        return deleted
    }

    private fun clearTemporaryFilesInternal(): Int {
        var deleted = 0
        cacheDir.listFiles { file -> file.isFile && file.name.endsWith(TEMP_SUFFIX) }
            ?.forEach { file ->
                if (file.delete()) deleted++
            }
        if (deleted > 0) {
            Timber.d("Deleted %d temporary online audio cache files", deleted)
        }
        return deleted
    }

    private fun deleteEntry(audioFile: File) {
        val hash = audioFile.name.removeSuffix(AUDIO_SUFFIX)
        audioFile.delete()
        metadataFile(hash).delete()
    }

    private fun audioFile(hash: String): File = File(cacheDir, "$hash$AUDIO_SUFFIX")

    private fun metadataFile(hash: String): File = File(cacheDir, "$hash$METADATA_SUFFIX")

    private fun readMetadata(file: File): Properties? =
        runCatching {
            if (!file.exists()) return null
            Properties().apply { file.inputStream().use(::load) }
        }.getOrElse {
            Timber.w(it, "Failed to read online audio cache metadata")
            null
        }

    private fun writeMetadata(file: File, properties: Properties) {
        file.outputStream().use { properties.store(it, null) }
    }

    class CacheWriter internal constructor(
        private val manager: OnlineAudioCacheManager,
        private val cacheKey: String,
        private val hash: String,
        private val tempFile: File,
        private val expectedBytes: Long,
        private val output: FileOutputStream,
        private val mimeType: String,
        private val source: String,
        private val quality: String?
    ) {
        private var writtenBytes = 0L
        private var closed = false

        fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (closed) return
            output.write(bytes, offset, length)
            writtenBytes += length
        }

        fun commit() {
            if (closed) return
            closed = true
            runCatching { output.fd.sync() }
            output.close()
            if (writtenBytes == expectedBytes) {
                manager.commit(cacheKey, hash, tempFile, expectedBytes, mimeType, source, quality)
            } else {
                tempFile.delete()
            }
        }

        fun abort() {
            if (!closed) {
                closed = true
                runCatching { output.close() }
            }
            tempFile.delete()
        }
    }

    companion object {
        const val DEFAULT_CACHE_LIMIT_MB = 2048
        private const val CACHE_DIR_NAME = "online_audio_cache"
        private const val AUDIO_SUFFIX = ".audio"
        private const val METADATA_SUFFIX = ".properties"
        private const val TEMP_SUFFIX = ".tmp"
        private const val KEY_CACHE_KEY = "cacheKey"
        private const val KEY_SOURCE = "source"
        private const val KEY_QUALITY = "quality"
        private const val KEY_MIME_TYPE = "mimeType"
        private const val KEY_SIZE = "size"
        private const val KEY_CREATED_AT = "createdAt"
        private const val KEY_LAST_ACCESSED = "lastAccessed"

        private fun hashKey(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
