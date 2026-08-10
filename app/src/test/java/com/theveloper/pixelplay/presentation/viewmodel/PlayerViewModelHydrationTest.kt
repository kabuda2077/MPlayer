package com.theveloper.pixelplay.presentation.viewmodel

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.model.Song
import org.junit.Test

class PlayerViewModelHydrationTest {

    @Test
    fun withRepositoryHydration_fillsLookupFieldsWithoutReintroducingLegacyLyrics() {
        val currentSong = testSong(
            path = "",
            contentUriString = "content://media/external/audio/media/42",
            albumArtUriString = "content://art/current",
            lyrics = null
        )
        val repositorySong = testSong(
            path = "/music/track.mp3",
            contentUriString = "content://media/external/audio/media/42",
            albumArtUriString = null,
            lyrics = "[00:01.00]Hello world"
        )

        val hydratedSong = currentSong.withRepositoryHydration(repositorySong)

        assertThat(hydratedSong.path).isEqualTo("/music/track.mp3")
        assertThat(hydratedSong.lyrics).isNull()
        assertThat(hydratedSong.albumArtUriString).isEqualTo("content://art/current")
    }

    @Test
    fun improvesLyricsLookupComparedTo_returnsTrueWhenHydrationAddsPath() {
        val partialSong = testSong(path = "", lyrics = null)
        val hydratedSong = testSong(path = "/music/track.mp3", lyrics = "Stored lyrics")

        assertThat(hydratedSong.improvesLyricsLookupComparedTo(partialSong)).isTrue()
        assertThat(partialSong.improvesLyricsLookupComparedTo(hydratedSong)).isFalse()
    }

    @Test
    fun numericTransientSongCannotHydrateFromCollidingLibraryId() {
        val transient = testSong(
            contentUriString = "netease://42",
            lyrics = null,
            libraryId = null
        )
        val repositorySong = testSong(lyrics = "Wrong library lyrics", libraryId = 42L)

        assertThat(transient.withRepositoryHydration(repositorySong)).isEqualTo(transient)
    }

    private fun testSong(
        path: String = "/music/original.mp3",
        contentUriString: String = "content://media/external/audio/media/42",
        albumArtUriString: String? = "content://art/original",
        lyrics: String? = null,
        libraryId: Long? = 42L
    ): Song {
        return Song(
            id = "42",
            title = "Track",
            artist = "Artist",
            artistId = 7L,
            album = "Album",
            albumId = 11L,
            path = path,
            contentUriString = contentUriString,
            albumArtUriString = albumArtUriString,
            duration = 215_000L,
            lyrics = lyrics,
            mimeType = "audio/mpeg",
            bitrate = 320_000,
            sampleRate = 44_100,
            libraryId = libraryId
        )
    }
}
