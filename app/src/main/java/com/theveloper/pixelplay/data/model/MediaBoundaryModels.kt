package com.theveloper.pixelplay.data.model

import androidx.compose.runtime.Immutable
import com.theveloper.pixelplay.data.plugin.PluginSource
import com.theveloper.pixelplay.utils.MediaSourceClassifier

@Immutable
class LibrarySong private constructor(
    val libraryId: Long,
    val song: Song
) {
    companion object {
        fun from(song: Song): LibrarySong? {
            if (!MediaSourceClassifier.isLibraryPersistable(song.contentUriString)) return null
            val libraryId = song.libraryId ?: return null
            return LibrarySong(libraryId, song)
        }

        fun require(song: Song): LibrarySong = requireNotNull(from(song)) {
            "Song is not a persistable library item: ${song.contentUriString}"
        }
    }
}

@Immutable
class TransientOnlineSong private constructor(
    val source: PluginSource,
    val sourceSongId: String,
    val song: Song
) {
    val canSaveToLibrary: Boolean
        get() = source == PluginSource.NETEASE || source == PluginSource.QQ_MUSIC

    fun toPlaybackItem(): PlaybackItem = PlaybackItem.fromTransient(this)

    companion object {
        fun from(song: Song): TransientOnlineSong {
            val actualSource = requireNotNull(MediaSourceClassifier.pluginSource(song.contentUriString)) {
                "Transient online song must use a plugin source URI"
            }
            val sourceSongId = requireNotNull(
                MediaSourceClassifier.idPart(song.contentUriString, actualSource)
            ) { "Transient online song has no platform song ID" }
            return TransientOnlineSong(actualSource, sourceSongId, song)
        }

        fun from(song: Song, platform: OnlineSearchPlatform): TransientOnlineSong {
            val transient = from(song)
            val actualSource = transient.source
            require(actualSource == platform.source) {
                "Search result source $actualSource does not match ${platform.source}"
            }
            return transient
        }
    }
}

@Immutable
data class PlaybackItem(
    val mediaId: String,
    val stableKey: String,
    val sourceUri: String,
    val title: String,
    val artist: String,
    val album: String,
    val artworkUri: String?,
    val durationMs: Long,
    val libraryId: Long?,
    val path: String,
    val genre: String?,
    val trackNumber: Int,
    val year: Int,
    val dateAdded: Long,
    val mimeType: String?,
    val bitrate: Int?,
    val sampleRate: Int?
) {
    companion object {
        fun fromLibrary(value: LibrarySong): PlaybackItem = create(value.song, value.libraryId)

        fun fromTransient(value: TransientOnlineSong): PlaybackItem =
            create(value.song, libraryId = null)

        /** Non-library local items received through Android's external VIEW intent. */
        fun fromExternal(song: Song): PlaybackItem {
            require(song.id.startsWith("external:") || MediaSourceClassifier.isLocal(song.contentUriString)) {
                "Only external/local media can use the external playback boundary"
            }
            return create(song, libraryId = null)
        }

        internal fun fromCompatibleSong(song: Song): PlaybackItem {
            LibrarySong.from(song)?.let { return fromLibrary(it) }
            if (MediaSourceClassifier.isPluginOnline(song.contentUriString)) {
                return fromTransient(TransientOnlineSong.from(song))
            }
            return fromExternal(song)
        }

        private fun create(song: Song, libraryId: Long?): PlaybackItem = PlaybackItem(
            mediaId = song.id,
            stableKey = "${song.contentUriString}#${song.id}",
            sourceUri = song.contentUriString,
            title = song.title,
            artist = song.displayArtist,
            album = song.album,
            artworkUri = song.albumArtUriString,
            durationMs = song.duration,
            libraryId = libraryId,
            path = song.path,
            genre = song.genre,
            trackNumber = song.trackNumber,
            year = song.year,
            dateAdded = song.dateAdded,
            mimeType = song.mimeType,
            bitrate = song.bitrate,
            sampleRate = song.sampleRate
        )
    }
}
