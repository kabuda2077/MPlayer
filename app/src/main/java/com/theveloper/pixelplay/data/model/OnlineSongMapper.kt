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

    private fun normalizeArtworkUrl(value: String?): String? =
        value?.trim()?.takeIf(String::isNotBlank)?.let {
            if (it.startsWith("http://", ignoreCase = true)) "https://${it.substring(7)}" else it
        }

    private fun stableId(value: String): Long =
        -kotlin.math.abs(value.lowercase(Locale.ROOT).hashCode().toLong()).coerceAtLeast(1L)

    private const val UNKNOWN_TITLE = "Unknown Title"
    private const val UNKNOWN_ARTIST = "Unknown Artist"
    private const val UNKNOWN_ALBUM = "Unknown Album"
}
