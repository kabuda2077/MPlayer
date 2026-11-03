package com.theveloper.pixelplay.data.model

import com.theveloper.pixelplay.data.plugin.PluginSource
import java.util.Locale

/** Builds transient online songs without leaking persistence rules into providers. */
object OnlineSongMapper {
    fun toTransient(
        source: PluginSource,
        sourceSongId: String,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        artworkUrl: String? = null,
        mimeType: String? = "audio/mpeg",
        artistNames: List<String> = emptyList()
    ): TransientOnlineSong {
        val normalizedTitle = title.ifBlank { UNKNOWN_TITLE }
        val normalizedArtist = artist.ifBlank { UNKNOWN_ARTIST }
        val normalizedAlbum = album.ifBlank { UNKNOWN_ALBUM }
        val artists = artistNames
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .ifEmpty { listOf(normalizedArtist) }
            .mapIndexed { index, name -> ArtistRef(stableId(name), name, index == 0) }
        val song = Song(
            id = "${source.uriScheme}_$sourceSongId",
            title = normalizedTitle,
            artist = normalizedArtist,
            artistId = artists.first().id,
            artists = artists,
            album = normalizedAlbum,
            albumId = stableId("${source.name}:$normalizedAlbum"),
            path = "",
            contentUriString = "${source.uriScheme}://$sourceSongId",
            albumArtUriString = normalizeArtworkUrl(artworkUrl),
            duration = durationMs,
            mimeType = mimeType,
            bitrate = null,
            sampleRate = null,
            neteaseId = sourceSongId.toLongOrNull().takeIf { source == PluginSource.NETEASE },
            qqMusicMid = sourceSongId.takeIf { source == PluginSource.QQ_MUSIC }
        )
        return TransientOnlineSong.from(song)
    }

    fun normalizeArtworkUrl(value: String?): String? {
        val trimmed = value?.trim()?.takeIf(String::isNotBlank) ?: return null
        val https = if (trimmed.startsWith("http://", ignoreCase = true)) "https://${trimmed.substring(7)}" else trimmed
        if (https.contains("music.126.net") && !https.contains("param=")) {
            return if (https.contains("?")) "$https&param=300y300" else "$https?param=300y300"
        }
        if (https.contains("y.gtimg.cn") || https.contains("y.qq.com")) {
            if (https.contains("R800x800")) return https.replace("R800x800", "R300x300")
            if (https.contains("R500x500")) return https.replace("R500x500", "R300x300")
        }
        return https
    }

    private fun stableId(value: String): Long =
        -kotlin.math.abs(value.lowercase(Locale.ROOT).hashCode().toLong()).coerceAtLeast(1L)

    private const val UNKNOWN_TITLE = "Unknown Title"
    private const val UNKNOWN_ARTIST = "Unknown Artist"
    private const val UNKNOWN_ALBUM = "Unknown Album"
}
