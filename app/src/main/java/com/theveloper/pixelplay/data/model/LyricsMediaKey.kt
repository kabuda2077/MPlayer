package com.theveloper.pixelplay.data.model

import com.theveloper.pixelplay.utils.MediaSourceClassifier

/** Stable, source-aware identity used by lyrics persistence and memory caches. */
@JvmInline
value class LyricsMediaKey(val value: String) {
    init {
        require(value.isNotBlank()) { "Lyrics media key must not be blank" }
    }

    companion object {
        fun fromSong(song: Song): LyricsMediaKey {
            val uri = song.contentUriString.trim()
            return if (MediaSourceClassifier.isLocal(uri)) {
                song.libraryId?.let(::fromLibraryId)
                    ?: LyricsMediaKey("local-uri:$uri")
            } else {
                require(uri.isNotBlank()) { "Online lyrics identity requires a source URI" }
                LyricsMediaKey("uri:$uri")
            }
        }

        fun fromLibraryId(songId: Long): LyricsMediaKey = LyricsMediaKey("library:$songId")
    }
}
