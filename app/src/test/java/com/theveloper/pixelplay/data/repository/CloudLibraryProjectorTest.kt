package com.theveloper.pixelplay.data.repository

import com.theveloper.pixelplay.data.database.FavoritesDao
import com.theveloper.pixelplay.data.database.MusicDao
import com.theveloper.pixelplay.data.database.NeteaseDao
import com.theveloper.pixelplay.data.database.NeteaseSongEntity
import com.theveloper.pixelplay.data.database.QqMusicDao
import com.theveloper.pixelplay.data.database.SongEntity
import com.theveloper.pixelplay.data.model.Playlist
import com.theveloper.pixelplay.data.preferences.PlaylistPreferencesRepository
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CloudLibraryProjectorTest {
    private val musicDao = mockk<MusicDao>(relaxed = true)
    private val neteaseDao = mockk<NeteaseDao>(relaxed = true)
    private val qqMusicDao = mockk<QqMusicDao>(relaxed = true)
    private val favoritesDao = mockk<FavoritesDao>(relaxed = true)
    private val playlists = mockk<PlaylistPreferencesRepository>(relaxed = true)
    private val projector = CloudLibraryProjector(
        musicDao,
        neteaseDao,
        qqMusicDao,
        favoritesDao,
        playlists
    )

    @BeforeEach
    fun setUp() {
        coEvery { favoritesDao.getFavoriteSongIdsOnce() } returns emptyList()
        coEvery { playlists.getPlaylistsOnce() } returns emptyList()
    }

    @Test
    fun `empty account cache keeps favorites and user playlist songs`() = runTest {
        val favoriteId = OnlineLibraryIds.neteaseSong(1)
        val playlistId = OnlineLibraryIds.neteaseSong(2)
        val removableId = OnlineLibraryIds.neteaseSong(3)
        coEvery { neteaseDao.getAllNeteaseSongsList() } returns emptyList()
        coEvery { musicDao.getAllNeteaseSongIds() } returns listOf(favoriteId, playlistId, removableId)
        coEvery { favoritesDao.getFavoriteSongIdsOnce() } returns listOf(favoriteId)
        coEvery { playlists.getPlaylistsOnce() } returns listOf(
            Playlist(id = "local", name = "Local", songIds = listOf(playlistId.toString()))
        )

        projector.projectNetease()

        coVerify(exactly = 1) { musicDao.deleteSongsAndRelatedData(listOf(removableId)) }
    }

    @Test
    fun `projection uses shared stable ids`() = runTest {
        val capturedSongs = slot<List<SongEntity>>()
        coEvery { neteaseDao.getAllNeteaseSongsList() } returns listOf(
            NeteaseSongEntity(
                id = "playlist_42",
                neteaseId = 42,
                playlistId = 1,
                title = "Song",
                artist = "Artist",
                album = "Album",
                albumId = 9,
                duration = 1234,
                albumArtUrl = null,
                mimeType = "audio/mpeg",
                bitrate = null,
                dateAdded = 10
            )
        )
        coEvery { musicDao.getAllNeteaseSongIds() } returns emptyList()

        projector.projectNetease()

        coVerify(exactly = 1) {
            musicDao.incrementalSyncMusicData(
                capture(capturedSongs),
                any(),
                any(),
                any(),
                emptyList()
            )
        }
        assertThat(capturedSongs.captured.single().id).isEqualTo(OnlineLibraryIds.neteaseSong(42))
    }
}
