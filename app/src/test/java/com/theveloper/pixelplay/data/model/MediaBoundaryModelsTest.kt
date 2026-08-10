package com.theveloper.pixelplay.data.model

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.plugin.PluginSource
import org.junit.Test

class MediaBoundaryModelsTest {
    @Test
    fun transientSearchSongHasNoLibraryIdentity() {
        val transient = TransientOnlineSong.from(
            song = song(id = "kugou_hash", uri = "kugou://hash"),
            platform = OnlineSearchPlatform.KUGOU
        )

        assertThat(transient.source).isEqualTo(PluginSource.KUGOU)
        assertThat(transient.sourceSongId).isEqualTo("hash")
        assertThat(transient.toPlaybackItem().libraryId).isNull()
        assertThat(LibrarySong.from(transient.song)).isNull()
        assertThat(transient.canSaveToLibrary).isFalse()

        val netease = TransientOnlineSong.from(
            song = song(id = "netease_42", uri = "netease://42"),
            platform = OnlineSearchPlatform.NETEASE
        )
        assertThat(netease.canSaveToLibrary).isTrue()
    }

    @Test
    fun librarySongRequiresNumericIdentity() {
        assertThat(LibrarySong.from(song(id = "42", uri = "content://media/external/audio/42", libraryId = 42L))).isNotNull()
        assertThat(LibrarySong.from(song(id = "temporary", uri = "content://media/external/audio/42"))).isNull()
        assertThat(LibrarySong.from(song(id = "42", uri = "netease://42"))).isNull()
    }

    private fun song(id: String, uri: String, libraryId: Long? = null) = Song(
        id = id,
        libraryId = libraryId,
        title = "Title",
        artist = "Artist",
        artistId = 1L,
        album = "Album",
        albumId = 2L,
        path = "",
        contentUriString = uri,
        albumArtUriString = null,
        duration = 1_000L,
        mimeType = "audio/mpeg",
        bitrate = null,
        sampleRate = null
    )
}
