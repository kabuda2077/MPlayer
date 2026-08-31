package com.theveloper.pixelplay.utils

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.media.MediaMapper
import com.theveloper.pixelplay.data.model.LibrarySong
import com.theveloper.pixelplay.data.model.PlaybackItem
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.model.TransientOnlineSong
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaItemIdentityTest {
    @Test
    fun libraryIdentitySurvivesMediaItemRoundTrip() {
        val song = song(
            id = "42",
            libraryId = 42L,
            contentUri = "content://media/external/audio/media/42"
        )

        val mediaItem = MediaItemBuilder.build(PlaybackItem.fromLibrary(LibrarySong.require(song)))
        val restored = MediaMapper(ApplicationProvider.getApplicationContext())
            .resolveSongFromMediaItem(mediaItem)

        assertThat(
            mediaItem.mediaMetadata.extras?.containsKey(MediaItemBuilder.EXTERNAL_EXTRA_LIBRARY_ID)
        ).isTrue()
        assertThat(restored?.libraryId).isEqualTo(42L)
    }

    @Test
    fun numericTransientIdentityDoesNotBecomeLibraryIdentity() {
        val song = song(
            id = "42",
            libraryId = null,
            contentUri = "netease://42"
        )

        val mediaItem = MediaItemBuilder.build(
            PlaybackItem.fromTransient(TransientOnlineSong.from(song))
        )
        val restored = MediaMapper(ApplicationProvider.getApplicationContext())
            .resolveSongFromMediaItem(mediaItem)

        assertThat(
            mediaItem.mediaMetadata.extras?.containsKey(MediaItemBuilder.EXTERNAL_EXTRA_LIBRARY_ID)
        ).isFalse()
        assertThat(restored?.libraryId).isNull()
        assertThat(restored?.contentUriString).isEqualTo("netease://42")
    }

    @Test
    fun transientOnlineArtworkIsPublishedToMediaSessionMetadata() {
        val mediaItem = MediaItemBuilder.build(
            PlaybackItem.fromTransient(
                TransientOnlineSong.from(
                    song(
                        id = "qq_123",
                        libraryId = null,
                        contentUri = "qqmusic://123",
                        albumArtUri = "https://y.qq.com/music/photo.jpg"
                    )
                )
            )
        )

        assertThat(mediaItem.mediaMetadata.artworkUri?.toString())
            .isEqualTo("https://y.qq.com/music/photo.jpg")
    }

    private fun song(
        id: String,
        libraryId: Long?,
        contentUri: String,
        albumArtUri: String? = null
    ) = Song(
        id = id,
        libraryId = libraryId,
        title = "Track",
        artist = "Artist",
        artistId = -1L,
        album = "Album",
        albumId = -1L,
        path = "",
        contentUriString = contentUri,
        albumArtUriString = albumArtUri,
        duration = 180_000L,
        mimeType = "audio/mpeg",
        bitrate = 320_000,
        sampleRate = 44_100
    )
}
