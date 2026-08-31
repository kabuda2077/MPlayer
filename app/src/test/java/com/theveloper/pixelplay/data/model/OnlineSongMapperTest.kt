package com.theveloper.pixelplay.data.model

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.plugin.PluginSource
import org.junit.Test

class OnlineSongMapperTest {
    @Test
    fun toTransient_buildsCanonicalSourceIdentityAndNormalizesArtwork() {
        val transient = OnlineSongMapper.toTransient(
            source = PluginSource.QQ_MUSIC,
            sourceSongId = "song-mid",
            title = "Song",
            artist = "Artist A / Artist B",
            artistNames = listOf("Artist A", "Artist B"),
            album = "Album",
            durationMs = 123_000,
            artworkUrl = "http://example.com/cover.jpg"
        )

        assertThat(transient.source).isEqualTo(PluginSource.QQ_MUSIC)
        assertThat(transient.sourceSongId).isEqualTo("song-mid")
        assertThat(transient.song.id).isEqualTo("qqmusic_song-mid")
        assertThat(transient.song.contentUriString).isEqualTo("qqmusic://song-mid")
        assertThat(transient.song.qqMusicMid).isEqualTo("song-mid")
        assertThat(transient.song.albumArtUriString).isEqualTo("https://example.com/cover.jpg")
        assertThat(transient.song.artists.map { it.name }).containsExactly("Artist A", "Artist B").inOrder()
    }
}
