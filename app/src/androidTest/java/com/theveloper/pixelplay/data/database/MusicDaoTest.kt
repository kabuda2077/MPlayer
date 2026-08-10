package com.theveloper.pixelplay.data.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class MusicDaoTest {

    private lateinit var musicDao: MusicDao
    private lateinit var db: PixelPlayDatabase

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PixelPlayDatabase::class.java)
            .addCallback(PixelPlayDatabase.createRuntimeArtifactsCallback())
            .allowMainThreadQueries() // Permite consultas en el hilo principal para tests
            .build()
        musicDao = db.musicDao()
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
    }

    private fun createSongEntity(id: Long, title: String, artist: String, album: String, path: String, genre: String = "Pop"): SongEntity {
        return SongEntity(
            id = id,
            title = title,
            artistName = artist,
            artistId = 101L,
            albumName = album,
            albumId = 201L,
            contentUriString = "uri_$id",
            albumArtUriString = "art_uri_$id",
            duration = 180000,
            genre = genre,
            filePath = path,
            parentDirectoryPath = path.substringBeforeLast("/"),
            year = 2023,
            trackNumber = 1
        )
    }

    private fun createAlbumEntity(id: Long, title: String): AlbumEntity {
        return AlbumEntity(
            id = id,
            title = title,
            artistName = "Artist",
            artistId = 101L,
            albumArtUriString = "art_uri_$id",
            songCount = 5,
            dateAdded = 0L,
            year = 2023
        )
    }

    private fun createArtistEntity(id: Long, name: String): ArtistEntity {
        return ArtistEntity(id = id, name = name, trackCount = 10, imageUrl = null)
    }

    /**
     * Songs have real Room foreign keys to albums and artists. Keep test fixtures valid instead
     * of relying on the pre-foreign-key insertion order used by the original tests.
     */
    private suspend fun insertDefaultParents() {
        musicDao.insertArtists(listOf(createArtistEntity(101L, "Artist 1")))
        musicDao.insertAlbums(listOf(createAlbumEntity(201L, "Album X")))
    }

    @Test
    @Throws(Exception::class)
    fun insertAndGetSongs() = runTest {
        insertDefaultParents()
        val songList = listOf(
            createSongEntity(1L, "Song A", "Artist 1", "Album X", "/path/a/songA.mp3"),
            createSongEntity(2L, "Song B", "Artist 2", "Album Y", "/path/b/songB.mp3", "Rock")
        )
        musicDao.insertSongs(songList)

        // New signature: getSongs(allowedParentDirs, applyDirectoryFilter)
        val retrievedSongs = musicDao.getSongs(emptyList(), false).first()
        assertEquals(2, retrievedSongs.size)
        // Sort by title logic in Dao is ASC. Song A comes before Song B.
        assertEquals("Song A", retrievedSongs[0].title)
        assertEquals("Song B", retrievedSongs[1].title)
    }

    @Test
    @Throws(Exception::class)
    fun insertAndGetAlbums() = runTest {
        musicDao.insertArtists(listOf(createArtistEntity(101L, "Artist 1")))
        val albumList = listOf(
            createAlbumEntity(201L, "Album X"), // Matches song's albumId 201L default
            createAlbumEntity(202L, "Album Y")
        )
        musicDao.insertAlbums(albumList)

        val songs = listOf(
            createSongEntity(1L, "Song A", "Artist 1", "Album X", "/path/a/songA.mp3"),
            createSongEntity(2L, "Song B", "Artist 1", "Album X", "/path/a/songB.mp3")
        )
        musicDao.insertSongs(songs) // Needed for inner join in getAlbums
        
        val retrievedAlbums = musicDao.getAlbums(emptyList(), false, 0, 1).first()
        // getAlbums uses INNER JOIN with songs, so only albums with songs are returned
        // My createSongEntity uses albumId 201L (Album X).
        // So Album Y (202L) should NOT be returned if logic holds (INNER JOIN songs ON albums.id = songs.album_id)
        
        assertEquals(1, retrievedAlbums.size)
        assertEquals("Album X", retrievedAlbums[0].title)
        assertEquals(2, retrievedAlbums[0].songCount)
    }

    @Test
    @Throws(Exception::class)
    fun insertAndGetArtists() = runTest {
        val artistList = listOf(
            createArtistEntity(101L, "Artist 1"), // Matches song's artistId 101L default
            createArtistEntity(102L, "Artist 2")
        )
        musicDao.insertArtists(artistList)
        musicDao.insertAlbums(listOf(createAlbumEntity(201L, "Album X")))

        val song = createSongEntity(1L, "Song A", "Artist 1", "Album X", "/path/a/songA.mp3")
        musicDao.insertSongs(listOf(song)) // Needed for inner join in getArtists

        val retrievedArtists = musicDao.getArtists(emptyList(), false).first()
        // getArtists uses INNER JOIN with songs
        // Song uses artistId 101L.
        assertEquals(1, retrievedArtists.size)
        assertEquals("Artist 1", retrievedArtists[0].name)
    }

    @Test
    @Throws(Exception::class)
    fun insertMusicData_upsertsWithoutClearingExistingRows() = runTest {
        insertDefaultParents()
        val oldSong = createSongEntity(1L, "Old Song", "Old Artist", "Old Album", "/old/path/old.mp3")
        musicDao.insertSongs(listOf(oldSong))

        val songs = listOf(
            createSongEntity(10L, "Song A", "Artist 1", "Album X", "/path/a/songA.mp3")
        )
        val albums = listOf(
            createAlbumEntity(201L, "Album X")
        )
        val artists = listOf(
            createArtistEntity(101L, "Artist 1")
        )

        musicDao.insertMusicData(songs, albums, artists)

        val newSongRetrieved = musicDao.getSongById(10L).first()
        assertNotNull(newSongRetrieved)

        val oldSongStillThere = musicDao.getSongById(1L).first()
        assertNotNull(oldSongStillThere)
    }

    @Test
    @Throws(Exception::class)
    fun searchSongs_returnsMatchingSongs() = runTest {
        insertDefaultParents()
        val songs = listOf(
            createSongEntity(1L, "Cool Song", "Artist A", "Album X", "/p1/s1.mp3"),
            createSongEntity(2L, "Another Song", "Artist B", "Album Y", "/p2/s2.mp3", "Rock"),
            createSongEntity(3L, "Coolest Song Ever", "Artist C", "Album Z", "/p3/s3.mp3")
        )
        musicDao.insertSongs(songs)

        val results = musicDao.searchSongs("Cool", emptyList(), false).first()
        assertEquals(2, results.size)
        // Check contents
        val titles = results.map { it.title }.sorted()
        assertEquals(listOf("Cool Song", "Coolest Song Ever"), titles)
    }
}
