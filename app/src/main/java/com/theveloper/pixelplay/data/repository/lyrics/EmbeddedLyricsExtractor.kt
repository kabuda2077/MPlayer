package com.theveloper.pixelplay.data.repository.lyrics

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.net.toUri
import com.kyant.taglib.TagLib
import com.theveloper.pixelplay.data.model.Lyrics
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.utils.LogUtils
import com.theveloper.pixelplay.utils.LyricsUtils
import com.theveloper.pixelplay.utils.MediaSourceClassifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object EmbeddedLyricsExtractor {
    private const val TAG = "EmbeddedLyricsExtractor"
    private val EMBEDDED_LYRICS_KEYS = listOf("LYRICS", "SYNCEDLYRICS", "TTML", "UNSYNCEDLYRICS")

    private fun Lyrics.isValid(): Boolean = !synced.isNullOrEmpty() || !plain.isNullOrEmpty()

    fun parseBestEmbeddedLyricsField(propertyMap: Map<String, Array<String>>?): Lyrics? {
        var firstPlainLyrics: Lyrics? = null

        EMBEDDED_LYRICS_KEYS.forEach { key ->
            propertyMap?.get(key).orEmpty().forEach { field ->
                if (field.isBlank()) return@forEach

                val parsedLyrics = LyricsUtils.parseLyrics(field)
                if (!parsedLyrics.isValid()) return@forEach

                val localLyrics = parsedLyrics.copy(areFromRemote = false)
                if (!localLyrics.synced.isNullOrEmpty()) return localLyrics
                if (firstPlainLyrics == null) firstPlainLyrics = localLyrics
            }
        }

        return firstPlainLyrics
    }

    suspend fun loadEmbeddedLyricsFromMetadata(context: Context, song: Song): Lyrics? = withContext(Dispatchers.IO) {
        if (!isLocalMetadataReadableUri(song.contentUriString)) {
            return@withContext null
        }

        return@withContext try {
            val uri = song.contentUriString.toUri()
            val tempFile = createTempFileFromUri(context, uri)
            if (tempFile == null) {
                LogUtils.w(EmbeddedLyricsExtractor, "Could not create temp file from URI: ${song.contentUriString}")
                return@withContext null
            }

            try {
                ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    val metadata = TagLib.getMetadata(fd.detachFd())
                    val propertyMap = metadata?.propertyMap
                    val parsedLyrics = parseBestEmbeddedLyricsField(propertyMap)

                    if (parsedLyrics != null) {
                        Log.d(TAG, "===== FOUND EMBEDDED LYRICS =====")
                        parsedLyrics
                    } else {
                        null
                    }
                }
            } finally {
                tempFile.delete()
            }
        } catch (e: Exception) {
            LogUtils.e(EmbeddedLyricsExtractor, e, "Error reading lyrics from file metadata")
            null
        }
    }

    fun isLocalMetadataReadableUri(contentUriString: String): Boolean {
        return contentUriString.isNotBlank() && MediaSourceClassifier.isLocal(contentUriString)
    }

    fun createTempFileFromUri(context: Context, uri: Uri): File? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val fileName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex >= 0) cursor.getString(nameIndex) else "temp_audio"
                    } else "temp_audio"
                } ?: "temp_audio"

                val tempFile = File.createTempFile("lyrics_", "_$fileName", context.cacheDir)
                FileOutputStream(tempFile).use { output ->
                    inputStream.copyTo(output)
                }
                tempFile
            }
        } catch (e: Exception) {
            LogUtils.e(EmbeddedLyricsExtractor, e, "Error creating temp file from URI")
            null
        }
    }
}
