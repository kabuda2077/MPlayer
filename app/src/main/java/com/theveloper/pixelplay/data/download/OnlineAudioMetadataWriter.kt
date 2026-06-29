package com.theveloper.pixelplay.data.download

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.kyant.taglib.Picture
import com.kyant.taglib.TagLib
import com.theveloper.pixelplay.utils.AlbumArtUtils
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.AndroidArtwork
import timber.log.Timber

@Singleton
class OnlineAudioMetadataWriter @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    fun write(
        context: Context,
        audioFile: File,
        entity: DownloadEntity
    ): Boolean {
        val artworkBytes = loadArtworkBytes(context, entity.albumArtUriString)
        if (writeWithTagLib(audioFile, entity, artworkBytes)) {
            return true
        }
        return writeWithJAudioTagger(audioFile, entity, artworkBytes)
    }

    private fun writeWithTagLib(
        audioFile: File,
        entity: DownloadEntity,
        artworkBytes: ByteArray?
    ): Boolean {
        return runCatching {
            ParcelFileDescriptor.open(audioFile, ParcelFileDescriptor.MODE_READ_WRITE).use { fd ->
                val metadataFd = fd.dup()
                val propertyMap = HashMap(TagLib.getMetadata(metadataFd.detachFd())?.propertyMap ?: emptyMap())
                propertyMap["TITLE"] = arrayOf(entity.title)
                propertyMap["ARTIST"] = arrayOf(entity.artist)
                propertyMap["ALBUM"] = arrayOf(entity.album)
                propertyMap["ALBUMARTIST"] = arrayOf(entity.artist)

                val saveFd = fd.dup()
                val metadataSaved = TagLib.savePropertyMap(saveFd.detachFd(), propertyMap)
                if (!metadataSaved) return@runCatching false

                if (artworkBytes != null) {
                    val pictureFd = fd.dup()
                    TagLib.savePictures(
                        pictureFd.detachFd(),
                        arrayOf(
                            Picture(
                                data = artworkBytes,
                                description = "Front Cover",
                                pictureType = "Front Cover",
                                mimeType = guessImageMimeType(artworkBytes) ?: "image/jpeg"
                            )
                        )
                    )
                }
            }
            syncFile(audioFile)
            Timber.d(
                "Download metadata embedded via TagLib title=%s artist=%s file=%s",
                entity.title,
                entity.artist,
                audioFile.name
            )
            true
        }.onFailure { error ->
            Timber.d(error, "TagLib download metadata write failed for ${entity.title}")
        }.getOrDefault(false)
    }

    private fun writeWithJAudioTagger(
        audioFile: File,
        entity: DownloadEntity,
        artworkBytes: ByteArray?
    ): Boolean {
        return runCatching {
            java.util.logging.Logger.getLogger("org.jaudiotagger").level = java.util.logging.Level.OFF

            val parsed = AudioFileIO.read(audioFile)
            val tag = parsed.tag ?: parsed.createDefaultTag().also(parsed::setTag)

            entity.title.takeIf { it.isNotBlank() }?.let { tag.setField(FieldKey.TITLE, it) }
            entity.artist.takeIf { it.isNotBlank() }?.let { tag.setField(FieldKey.ARTIST, it) }
            entity.album.takeIf { it.isNotBlank() }?.let { tag.setField(FieldKey.ALBUM, it) }
            entity.artist.takeIf { it.isNotBlank() }?.let { tag.setField(FieldKey.ALBUM_ARTIST, it) }

            artworkBytes?.let { bytes ->
                val artwork = AndroidArtwork().apply {
                    binaryData = bytes
                    mimeType = guessImageMimeType(bytes)
                    pictureType = 3
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    width = options.outWidth
                    height = options.outHeight
                }
                runCatching { tag.deleteArtworkField() }
                tag.setField(artwork)
            }

            parsed.commit()
            syncFile(audioFile)
            Timber.d(
                "Download metadata embedded via JAudioTagger title=%s artist=%s file=%s",
                entity.title,
                entity.artist,
                audioFile.name
            )
            true
        }.onFailure { error ->
            Timber.w(error, "Failed to embed download metadata for ${entity.title}")
        }.getOrDefault(false)
    }

    private fun syncFile(audioFile: File) {
        runCatching {
            java.io.RandomAccessFile(audioFile, "rw").use { it.fd.sync() }
        }
    }

    private fun loadArtworkBytes(context: Context, rawUri: String?): ByteArray? {
        if (rawUri.isNullOrBlank()) return null
        return runCatching {
            val uri = Uri.parse(rawUri)
            when (uri.scheme?.lowercase()) {
                "http", "https" -> {
                    val request = Request.Builder().url(rawUri).build()
                    okHttpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@use null
                        val contentLength = response.header("Content-Length")?.toLongOrNull()
                        if (contentLength != null && contentLength > MAX_ARTWORK_BYTES) return@use null
                        response.body.byteStream().use { input ->
                            input.readBytesCapped(MAX_ARTWORK_BYTES)
                        }
                    }
                }
                else -> {
                    AlbumArtUtils.openArtworkInputStream(context, uri)?.use { input ->
                        input.readBytesCapped(MAX_ARTWORK_BYTES)
                    }
                }
            }
        }.onFailure { error ->
            Timber.d(error, "Unable to load artwork for downloaded audio")
        }.getOrNull()
    }

    private fun java.io.InputStream.readBytesCapped(maxBytes: Int): ByteArray? {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read == -1) break
            total += read
            if (total > maxBytes) return null
            output.write(buffer, 0, read)
        }
        return output.toByteArray().takeIf { it.isNotEmpty() && isSupportedImage(it) }
    }

    private fun guessImageMimeType(bytes: ByteArray): String? = when {
        isJpeg(bytes) -> "image/jpeg"
        isPng(bytes) -> "image/png"
        isWebp(bytes) -> "image/webp"
        else -> null
    }

    private fun isSupportedImage(bytes: ByteArray): Boolean = guessImageMimeType(bytes) != null

    private fun isJpeg(bytes: ByteArray): Boolean =
        bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte()

    private fun isPng(bytes: ByteArray): Boolean =
        bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte()

    private fun isWebp(bytes: ByteArray): Boolean =
        bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() &&
            bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() &&
            bytes[11] == 'P'.code.toByte()

    companion object {
        private const val MAX_ARTWORK_BYTES = 5 * 1024 * 1024
    }
}
