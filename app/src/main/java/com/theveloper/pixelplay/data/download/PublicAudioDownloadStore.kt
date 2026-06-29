package com.theveloper.pixelplay.data.download

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

@Singleton
class PublicAudioDownloadStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val resolver: ContentResolver get() = context.contentResolver

    suspend fun createPending(
        entity: DownloadEntity,
        mimeType: String?,
        fallbackMimeType: String?
    ): PendingPublicDownload = withContext(Dispatchers.IO) {
        val normalizedMimeType = normalizeMimeType(mimeType, fallbackMimeType)
        val extension = extensionForMimeType(normalizedMimeType)
        val baseName = sanitizeFileName("${entity.artist} - ${entity.title}").ifBlank { "MPlayer Audio" }
        val displayName = nextAvailableDisplayName(baseName, extension)
        val nowSeconds = System.currentTimeMillis() / 1000
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, normalizedMimeType)
            put(MediaStore.Audio.Media.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
            put(MediaStore.Audio.Media.TITLE, entity.title)
            put(MediaStore.Audio.Media.ARTIST, entity.artist)
            put(MediaStore.Audio.Media.ALBUM, entity.album)
            put(MediaStore.Audio.Media.DATE_ADDED, nowSeconds)
            put(MediaStore.Audio.Media.DATE_MODIFIED, nowSeconds)
        }
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("无法创建下载文件")
        PendingPublicDownload(
            uri = uri,
            mimeType = normalizedMimeType,
            relativePath = RELATIVE_PATH,
            displayName = displayName
        )
    }

    suspend fun openOutputStream(pending: PendingPublicDownload): OutputStream =
        withContext(Dispatchers.IO) {
            resolver.openOutputStream(pending.uri, "w")
                ?: throw IllegalStateException("无法写入下载文件")
        }

    suspend fun commit(pending: PendingPublicDownload) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.IS_PENDING, 0)
            put(MediaStore.Audio.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
        }
        resolver.update(pending.uri, values, null, null)
    }

    suspend fun delete(uriString: String?) = withContext(Dispatchers.IO) {
        val uri = uriString?.takeIf { it.isNotBlank() }?.let(Uri::parse) ?: return@withContext
        runCatching {
            resolver.delete(uri, null, null)
        }.onFailure { error ->
            // The user may delete the public MediaStore item from the Library or another app
            // before deleting the stale download record. Treat that as already removed.
            Timber.d(error, "Ignoring stale public download uri during delete: $uri")
        }
    }

    fun completedAudio(entity: DownloadEntity): DownloadedAudioFile? {
        if (entity.status != DownloadStatus.COMPLETED) return null
        val publicUri = entity.publicUri?.takeIf { it.isNotBlank() } ?: return null
        val uri = runCatching { Uri.parse(publicUri) }.getOrNull() ?: return null
        val size = entity.sizeBytes.takeIf { it > 0L } ?: querySize(uri)
        if (size <= 0L) return null
        return DownloadedAudioFile(
            contentUriString = entity.contentUriString,
            publicUri = publicUri,
            mimeType = entity.mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream",
            sizeBytes = size,
            quality = entity.actualQuality
        )
    }

    private fun querySize(uri: Uri): Long {
        val projection = arrayOf(MediaStore.Audio.Media.SIZE)
        return runCatching {
            resolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)
                    if (index >= 0) return@runCatching cursor.getLong(index)
                }
            }
            0L
        }.onFailure { error ->
            Timber.d(error, "Unable to query stale public download uri: $uri")
        }.getOrDefault(0L)
    }

    private fun nextAvailableDisplayName(baseName: String, extension: String): String {
        val existing = existingDisplayNames()
        var candidate = "$baseName.$extension"
        var suffix = 1
        while (candidate in existing) {
            candidate = "$baseName ($suffix).$extension"
            suffix++
        }
        return candidate
    }

    private fun existingDisplayNames(): Set<String> {
        val result = mutableSetOf<String>()
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME)
        val selection = "${MediaStore.Audio.Media.RELATIVE_PATH}=?"
        resolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            arrayOf(RELATIVE_PATH),
            null
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                if (nameIndex >= 0) result += cursor.getString(nameIndex).orEmpty()
            }
        }
        return result
    }

    fun normalizeMimeType(primary: String?, fallback: String?): String {
        return listOf(primary, fallback)
            .mapNotNull { value -> value?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotBlank() } }
            .firstOrNull { it.startsWith("audio/") }
            ?: "audio/mpeg"
    }

    fun extensionForMimeType(mimeType: String): String {
        return when (mimeType.substringBefore(';').lowercase()) {
            "audio/flac", "audio/x-flac" -> "flac"
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/mp4", "audio/x-m4a", "audio/aac" -> "m4a"
            "audio/ogg", "application/ogg" -> "ogg"
            "audio/opus" -> "opus"
            "audio/wav", "audio/x-wav" -> "wav"
            else -> MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)?.takeIf { it.isNotBlank() }
                ?: "mp3"
        }
    }

    private fun sanitizeFileName(value: String): String {
        val cleaned = value
            .replace(Regex("""[\\/:*?"<>|]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trimEnd('.')
        return cleaned.take(MAX_BASENAME_LENGTH)
    }

    data class PendingPublicDownload(
        val uri: Uri,
        val mimeType: String,
        val relativePath: String,
        val displayName: String
    )

    companion object {
        val RELATIVE_PATH = "${Environment.DIRECTORY_MUSIC}/MPlayer/"
        private const val MAX_BASENAME_LENGTH = 160

        fun uriFromId(id: Long): Uri =
            ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
    }
}
