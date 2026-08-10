package com.theveloper.pixelplay.data.repository

import kotlin.math.absoluteValue

/** Stable negative IDs shared by account sync and explicit online-library saves. */
object OnlineLibraryIds {
    private const val NETEASE_SONG_OFFSET = 3_000_000_000_000L
    private const val NETEASE_ALBUM_OFFSET = 4_000_000_000_000L
    private const val NETEASE_ARTIST_OFFSET = 5_000_000_000_000L
    private const val QQ_SONG_OFFSET = 6_000_000_000_000L
    private const val QQ_ALBUM_OFFSET = 7_000_000_000_000L
    private const val QQ_ARTIST_OFFSET = 8_000_000_000_000L

    fun neteaseSong(sourceId: Long): Long =
        -(NETEASE_SONG_OFFSET + sourceId.absoluteValue)

    fun neteaseAlbum(sourceId: Long?, albumName: String): Long =
        -(NETEASE_ALBUM_OFFSET + normalizedId(sourceId, albumName))

    fun neteaseArtist(artistName: String): Long =
        -(NETEASE_ARTIST_OFFSET + stableHash(artistName))

    fun qqSong(sourceId: String): Long =
        -(QQ_SONG_OFFSET + stableHash(sourceId, lowercase = false))

    fun qqAlbum(sourceId: String?, albumName: String): Long =
        -(QQ_ALBUM_OFFSET + sourceId?.takeIf(String::isNotBlank)
            ?.let { stableHash(it, lowercase = false) }
            .orDefault(stableHash(albumName)))

    fun qqArtist(artistName: String): Long =
        -(QQ_ARTIST_OFFSET + stableHash(artistName))

    private fun normalizedId(sourceId: Long?, fallback: String): Long =
        sourceId?.takeIf { it != 0L }?.absoluteValue ?: stableHash(fallback)

    private fun stableHash(value: String, lowercase: Boolean = true): Long {
        val normalized = if (lowercase) value.lowercase() else value
        return normalized.hashCode().toLong().absoluteValue
    }

    private fun Long?.orDefault(default: Long): Long = this ?: default
}
