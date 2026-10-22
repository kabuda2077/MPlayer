package com.theveloper.pixelplay.data.model

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.utils.MediaSourceKind
import org.junit.Test

class MediaSourceOriginTest {

    @Test
    fun localSong_resolvesLocalMediaOrigin() {
        val song = Song.emptySong().copy(
            id = "1",
            title = "Local Song",
            artist = "Local Artist",
            path = "/storage/emulated/0/Music/song.mp3",
            contentUriString = "content://media/external/audio/media/1"
        )

        val origin = song.sourceOrigin
        assertThat(origin.kind).isEqualTo(MediaSourceKind.LOCAL)
        assertThat(origin.isOnline).isFalse()
        assertThat(origin.isLocal).isTrue()
        assertThat(song.isOnline).isFalse()
        assertThat(song.isLocal).isTrue()
    }

    @Test
    fun neteaseSong_resolvesNeteaseMediaOriginWithSourceId() {
        val song = Song.emptySong().copy(
            id = "netease:123456",
            title = "Netease Song",
            artist = "Netease Artist",
            contentUriString = "netease://123456",
            neteaseId = 123456L
        )

        val origin = song.sourceOrigin
        assertThat(origin.kind).isEqualTo(MediaSourceKind.NETEASE)
        assertThat(origin.sourceId).isEqualTo("123456")
        assertThat(origin.isOnline).isTrue()
        assertThat(origin.isLocal).isFalse()
        assertThat(song.isOnline).isTrue()
        assertThat(song.isLocal).isFalse()
    }

    @Test
    fun qqMusicSong_resolvesQqMediaOriginWithMid() {
        val song = Song.emptySong().copy(
            id = "qqmusic:0039MnYb0qxYAc",
            title = "QQ Song",
            artist = "QQ Artist",
            contentUriString = "qqmusic://0039MnYb0qxYAc",
            qqMusicMid = "0039MnYb0qxYAc"
        )

        val origin = song.sourceOrigin
        assertThat(origin.kind).isEqualTo(MediaSourceKind.QQ_MUSIC)
        assertThat(origin.sourceId).isEqualTo("0039MnYb0qxYAc")
        assertThat(origin.isOnline).isTrue()
        assertThat(origin.isLocal).isFalse()
        assertThat(song.isOnline).isTrue()
        assertThat(song.isLocal).isFalse()
    }
}
