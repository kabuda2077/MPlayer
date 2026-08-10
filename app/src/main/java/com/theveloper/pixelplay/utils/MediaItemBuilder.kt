package com.theveloper.pixelplay.utils

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MediaMetadata.PICTURE_TYPE_FRONT_COVER
import androidx.media3.common.util.UnstableApi
import com.theveloper.pixelplay.data.provider.SharedArtworkContentProvider
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.model.PlaybackItem
import java.io.File

object MediaItemBuilder {
    private const val EXTERNAL_MEDIA_ID_PREFIX = "external:"
    private const val EXTERNAL_EXTRA_PREFIX = "com.theveloper.pixelplay.external."
    private val DIRECT_FILE_URI_MIME_TYPES = setOf(
        "audio/mp4",
        "audio/m4a",
        "audio/x-m4a",
        "audio/mp4a-latm",
        "audio/aac",
        "audio/x-aac",
        "audio/3gp",
        "audio/3gpp",
        "audio/3gpp2",
        "audio/amr",
        "audio/amr-wb",
        "audio/evrc",
        "audio/qcelp",
        "audio/x-ima-adpcm",
        "audio/x-ms-wma",
        "audio/x-aiff",
        "audio/ac3",
        "audio/vnd.dts",
    )
    private val DIRECT_FILE_URI_EXTENSIONS = setOf(
        "m4a",
        "m4b",
        "m4p",
        "mp4",
        "aac",
        "3ga",
        "3gp",
        "3gpp",
        "alac",
        "amr",
        "awb",
        "evrc",
        "qcp",
        "ima",
        "wma",
        "aif",
        "aiff",
        "ac3",
        "dts",
    )
    private val EXTRACTOR_FIRST_MIME_TYPES = setOf(
        "audio/mp4",
        "audio/m4a",
        "audio/x-m4a",
        "audio/mp4a-latm",
        "audio/alac",
        "audio/x-alac",
        "audio/x-aiff",
    )
    private val SUPPORTED_INTERNAL_ARTWORK_SCHEMES = setOf(
        LocalArtworkUri.SCHEME,
        "content",
        "file",
        "android.resource",
    )
    private val SUPPORTED_EXTERNAL_ARTWORK_SCHEMES = setOf(
        "content",
        "file",
        "android.resource",
    )
    const val EXTERNAL_EXTRA_FLAG = EXTERNAL_EXTRA_PREFIX + "FLAG"
    const val EXTERNAL_EXTRA_ALBUM = EXTERNAL_EXTRA_PREFIX + "ALBUM"
    const val EXTERNAL_EXTRA_DURATION = EXTERNAL_EXTRA_PREFIX + "DURATION"
    const val EXTERNAL_EXTRA_CONTENT_URI = EXTERNAL_EXTRA_PREFIX + "CONTENT_URI"
    const val EXTERNAL_EXTRA_ALBUM_ART = EXTERNAL_EXTRA_PREFIX + "ALBUM_ART"
    const val EXTERNAL_EXTRA_GENRE = EXTERNAL_EXTRA_PREFIX + "GENRE"
    const val EXTERNAL_EXTRA_TRACK = EXTERNAL_EXTRA_PREFIX + "TRACK"
    const val EXTERNAL_EXTRA_YEAR = EXTERNAL_EXTRA_PREFIX + "YEAR"
    const val EXTERNAL_EXTRA_DATE_ADDED = EXTERNAL_EXTRA_PREFIX + "DATE_ADDED"
    const val EXTERNAL_EXTRA_MIME_TYPE = EXTERNAL_EXTRA_PREFIX + "MIME_TYPE"
    const val EXTERNAL_EXTRA_BITRATE = EXTERNAL_EXTRA_PREFIX + "BITRATE"
    const val EXTERNAL_EXTRA_SAMPLE_RATE = EXTERNAL_EXTRA_PREFIX + "SAMPLE_RATE"
    const val EXTERNAL_EXTRA_FILE_PATH = EXTERNAL_EXTRA_PREFIX + "FILE_PATH"
    const val EXTERNAL_EXTRA_LIBRARY_ID = EXTERNAL_EXTRA_PREFIX + "LIBRARY_ID"

    fun build(item: PlaybackItem, playbackRequestId: String = PlaybackTrace.newRequestId()): MediaItem {
        return MediaItem.Builder()
            .setMediaId(item.mediaId)
            .setUri(playbackUri(item.sourceUri, item.path, item.mimeType))
            .setMimeType(playbackMimeType(item.sourceUri, item.path, item.mimeType))
            .setMediaMetadata(
                    buildMediaMetadata(
                        mediaId = item.mediaId,
                        libraryId = item.libraryId,
                    title = item.title,
                    artist = item.artist,
                    album = item.album,
                    duration = item.durationMs,
                    contentUri = item.sourceUri,
                    albumArtUri = item.artworkUri,
                    genre = item.genre,
                    trackNumber = item.trackNumber,
                    year = item.year,
                    dateAdded = item.dateAdded,
                    mimeType = item.mimeType,
                    bitrate = item.bitrate,
                    sampleRate = item.sampleRate,
                    path = item.path,
                    playbackRequestId = playbackRequestId
                )
            )
            .build()
    }

    fun buildForExternalController(
        context: Context,
        song: Song,
        playbackRequestId: String = PlaybackTrace.newRequestId()
    ): MediaItem {
        // This is the MediaSession item path for Android Auto / other external controllers;
        // time it so the performance report can attribute browse/queue lag here.
        return com.theveloper.pixelplay.data.diagnostics.PerformanceMetrics.time(
            com.theveloper.pixelplay.data.diagnostics.PerformanceMetrics.Timings.MEDIASESSION_ITEM_BUILD
        ) {
            MediaItem.Builder()
                .setMediaId(song.id)
                .setUri(playbackUri(song))
                .setMimeType(playbackMimeType(song))
                .setMediaMetadata(
                    buildMediaMetadataForSong(
                        song = song,
                        exposedArtworkUri = externalControllerArtworkUri(context, song.albumArtUriString),
                        playbackRequestId = playbackRequestId
                    )
                )
                .build()
        }
    }

    fun playbackUri(song: Song): Uri = playbackUri(
        contentUriString = song.contentUriString,
        filePath = song.path,
        mimeType = song.mimeType
    )

    internal fun playbackMimeType(song: Song): String? = playbackMimeType(
        contentUriString = song.contentUriString,
        filePath = song.path,
        mimeType = song.mimeType
    )

    fun playbackUri(
        contentUriString: String,
        filePath: String? = null,
        mimeType: String? = null
    ): Uri {
        directLocalFileUri(contentUriString, filePath, mimeType)?.let { return it }
        val uri = runCatching { Uri.parse(contentUriString) }.getOrNull()
            ?: return Uri.fromFile(File(contentUriString))
        // Normalize absolute paths so ExoPlayer always gets a canonical local-file URI.
        return if (uri.scheme.isNullOrBlank() && contentUriString.startsWith("/")) {
            Uri.fromFile(File(contentUriString))
        } else {
            uri
        }
    }

    internal fun playbackMimeType(
        contentUriString: String,
        filePath: String?,
        mimeType: String?
    ): String? {
        val normalizedMimeType = mimeType?.trim()?.lowercase()
        if (normalizedMimeType.isNullOrBlank()) {
            return null
        }

        val isLikelyLocalMedia = LocalArtworkUri.isLikelyLocalMedia(contentUriString) ||
            filePath?.startsWith("/") == true
        if (!isLikelyLocalMedia) {
            return mimeType
        }

        val extension = filePath
            ?.substringAfterLast('.', "")
            ?.lowercase()
            .orEmpty()

        return if (
            normalizedMimeType in EXTRACTOR_FIRST_MIME_TYPES ||
            extension in DIRECT_FILE_URI_EXTENSIONS
        ) {
            null
        } else {
            mimeType
        }
    }

    private fun directLocalFileUri(
        contentUriString: String,
        filePath: String?,
        mimeType: String?
    ): Uri? {
        val normalizedPath = filePath?.takeIf { it.startsWith("/") } ?: return null
        if (!shouldPreferDirectLocalFileUri(contentUriString, normalizedPath, mimeType)) {
            return null
        }

        return Uri.fromFile(File(normalizedPath))
    }

    internal fun shouldPreferDirectLocalFileUri(
        contentUriString: String,
        filePath: String?,
        mimeType: String?
    ): Boolean {
        val normalizedPath = filePath?.takeIf { it.startsWith("/") } ?: return false
        if (!LocalArtworkUri.isLikelyLocalMedia(contentUriString)) {
            return false
        }

        if (!contentUriString.startsWith("content://")) {
            return false
        }

        return shouldPreferDirectFileUri(normalizedPath, mimeType)
    }

    private fun shouldPreferDirectFileUri(
        filePath: String,
        mimeType: String?
    ): Boolean {
        val normalizedMimeType = mimeType?.lowercase()
        if (normalizedMimeType != null && normalizedMimeType in DIRECT_FILE_URI_MIME_TYPES) {
            return true
        }

        val extension = filePath.substringAfterLast('.', "").lowercase()
        return extension in DIRECT_FILE_URI_EXTENSIONS
    }

    /**
     * Artwork URIs are surfaced to external controllers (Android Auto, widgets, etc.).
     * Keep only schemes that these surfaces can usually resolve, and normalize raw paths.
     */
    fun artworkUri(rawArtworkUri: String?): Uri? {
        return normalizeArtworkUri(rawArtworkUri, SUPPORTED_INTERNAL_ARTWORK_SCHEMES)
    }

    fun externalControllerArtworkUri(context: Context, rawArtworkUri: String?): Uri? {
        if (LocalArtworkUri.isLocalArtworkUri(rawArtworkUri)) {
            val songId = rawArtworkUri?.let(LocalArtworkUri::parseSongId) ?: return null
            return SharedArtworkContentProvider.buildSongUri(
                context = context.applicationContext,
                songId = songId,
                cacheBustToken = LocalArtworkUri.extractCacheBustToken(rawArtworkUri)
            )
        }

        LocalArtworkUri.parseSongIdFromVolatileArtworkUri(rawArtworkUri)?.let { songId ->
            return SharedArtworkContentProvider.buildSongUri(
                context = context.applicationContext,
                songId = songId,
                cacheBustToken = LocalArtworkUri.extractCacheBustToken(rawArtworkUri)
            )
        }

        val normalizedUri = normalizeArtworkUri(rawArtworkUri, SUPPORTED_EXTERNAL_ARTWORK_SCHEMES)
            ?: return null
        return when (normalizedUri.scheme?.lowercase()) {
            "file" -> normalizedUri.path
                ?.takeIf { it.isNotBlank() }
                ?.let(::File)
                ?.let { file -> providerBackedArtworkUri(context, file) ?: normalizedUri }
                ?: normalizedUri
            null -> null
            else -> normalizedUri
        }
    }

    @OptIn(UnstableApi::class)
    private fun buildMediaMetadataForSong(
        song: Song,
        exposedArtworkUri: Uri? = artworkUri(song.albumArtUriString),
        playbackRequestId: String
    ): MediaMetadata = buildMediaMetadata(
        mediaId = song.id,
        libraryId = song.libraryId,
        title = song.title,
        artist = song.displayArtist,
        album = song.album,
        duration = song.duration,
        contentUri = song.contentUriString,
        albumArtUri = song.albumArtUriString,
        exposedArtworkUri = exposedArtworkUri,
        genre = song.genre,
        trackNumber = song.trackNumber,
        year = song.year,
        dateAdded = song.dateAdded,
        mimeType = song.mimeType,
        bitrate = song.bitrate,
        sampleRate = song.sampleRate,
        path = song.path,
        playbackRequestId = playbackRequestId
    )

    @OptIn(UnstableApi::class)
    private fun buildMediaMetadata(
        mediaId: String,
        libraryId: Long?,
        title: String,
        artist: String,
        album: String,
        duration: Long,
        contentUri: String,
        albumArtUri: String?,
        exposedArtworkUri: Uri? = artworkUri(albumArtUri),
        genre: String?,
        trackNumber: Int,
        year: Int,
        dateAdded: Long,
        mimeType: String?,
        bitrate: Int?,
        sampleRate: Int?,
        path: String,
        playbackRequestId: String
    ): MediaMetadata {
        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)

        exposedArtworkUri?.let { artworkUri ->
            metadataBuilder.setArtworkUri(artworkUri)
        }

        val extras = Bundle().apply {
            putBoolean(EXTERNAL_EXTRA_FLAG, mediaId.startsWith(EXTERNAL_MEDIA_ID_PREFIX))
            libraryId?.let { putLong(EXTERNAL_EXTRA_LIBRARY_ID, it) }
            putString(EXTERNAL_EXTRA_ALBUM, album)
            putLong(EXTERNAL_EXTRA_DURATION, duration)
            putString(EXTERNAL_EXTRA_CONTENT_URI, contentUri)
            (exposedArtworkUri?.toString() ?: albumArtUri)?.let {
                putString(EXTERNAL_EXTRA_ALBUM_ART, it)
            }
            genre?.let { putString(EXTERNAL_EXTRA_GENRE, it) }
            putInt(EXTERNAL_EXTRA_TRACK, trackNumber)
            putInt(EXTERNAL_EXTRA_YEAR, year)
            putLong(EXTERNAL_EXTRA_DATE_ADDED, dateAdded)
            putString(EXTERNAL_EXTRA_MIME_TYPE, mimeType)
            putInt(EXTERNAL_EXTRA_BITRATE, bitrate ?: 0)
            putInt(EXTERNAL_EXTRA_SAMPLE_RATE, sampleRate ?: 0)
            putString(EXTERNAL_EXTRA_FILE_PATH, path)
            putString(PlaybackTrace.EXTRA_REQUEST_ID, playbackRequestId)
        }

        metadataBuilder.setExtras(extras)
        return metadataBuilder.build()
    }

    private fun normalizeArtworkUri(
        rawArtworkUri: String?,
        supportedSchemes: Set<String>
    ): Uri? {
        if (rawArtworkUri.isNullOrBlank()) {
            return null
        }

        if (LocalArtworkUri.isLocalArtworkUri(rawArtworkUri)) {
            return if (LocalArtworkUri.SCHEME in supportedSchemes) {
                Uri.parse(rawArtworkUri)
            } else {
                null
            }
        }

        if (rawArtworkUri.startsWith("/")) {
            return Uri.fromFile(File(rawArtworkUri))
        }

        val uri = rawArtworkUri.toUri()
        val scheme = uri.scheme?.lowercase()
        return if (scheme != null && scheme in supportedSchemes) {
            uri
        } else {
            null
        }
    }

    private fun providerBackedArtworkUri(context: Context, file: File): Uri? {
        val canonicalFile = runCatching { file.canonicalFile }.getOrElse { file.absoluteFile }
        if (!isInsideAppStorage(context, canonicalFile)) {
            return null
        }

        return runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.provider", canonicalFile)
        }.getOrNull()
    }

    private fun isInsideAppStorage(context: Context, file: File): Boolean {
        val storageRoots = listOf(context.cacheDir, context.filesDir)
            .mapNotNull { root -> runCatching { root.canonicalFile }.getOrNull() }

        return storageRoots.any { root ->
            file.path == root.path || file.path.startsWith(root.path + File.separator)
        }
    }
}
