package com.theveloper.pixelplay.data.worker

import android.content.Context
import android.database.MatrixCursor
import android.net.Uri
import android.provider.MediaStore
import androidx.concurrent.futures.ResolvableFuture
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.database.MusicDao
import com.theveloper.pixelplay.data.database.PixelPlayDatabase
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class SyncWorkerTest {

    private lateinit var context: Context
    private lateinit var database: PixelPlayDatabase
    private lateinit var musicDao: MusicDao
    private lateinit var mockContentResolver: android.content.ContentResolver


    // Test WorkerFactory para inyectar el DAO (y potencialmente el ContentResolver mockeado)
    class TestSyncWorkerFactory(
        private val dao: MusicDao,
        private val resolver: android.content.ContentResolver? = null // Opcional si no se mockea a nivel de worker
    ) : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters
        ): ListenableWorker? {
            return if (workerClassName == SyncWorker::class.java.name) {
                val preferences = mockk<com.theveloper.pixelplay.data.preferences.UserPreferencesRepository>(relaxed = true) {
                    every { artistDelimitersFlow } returns flowOf(listOf(",", "/", "&"))
                    every { artistWordDelimitersFlow } returns flowOf(emptyList())
                    every { extractArtistsFromTitleFlow } returns flowOf(true)
                    every { groupByAlbumArtistFlow } returns flowOf(false)
                    every { artistSettingsRescanRequiredFlow } returns flowOf(false)
                    every { allowedDirectoriesFlow } returns flowOf(emptySet())
                    every { blockedDirectoriesFlow } returns flowOf(emptySet())
                    every { minTracksPerAlbumFlow } returns flowOf(1)
                }
                // Si SyncWorker tomara ContentResolver directamente:
                // SyncWorker(appContext, workerParameters, dao, resolver ?: appContext.contentResolver)
                // Como SyncWorker obtiene el resolver de appContext, no necesitamos pasarlo explícitamente aquí
                // a menos que queramos un mock muy específico a nivel de constructor del worker.
                SyncWorker(
                    appContext = appContext,
                    workerParams = workerParameters,
                    musicDao = dao,
                    userPreferencesRepository = preferences,
                    lyricsRepository = mockk(relaxed = true),
                    cloudLibraryProjector = mockk(relaxed = true),
                    downloadDao = mockk(relaxed = true)
                )
            } else {
                null
            }
        }
    }


    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, PixelPlayDatabase::class.java)
            .addCallback(PixelPlayDatabase.createRuntimeArtifactsCallback())
            .allowMainThreadQueries() // Para tests, está bien.
            .build()
        musicDao = database.musicDao()
        mockContentResolver = mockk() // Mockear el ContentResolver
    }

    @After
    @Throws(IOException::class)
    fun tearDown() {
        database.close()
    }

    private fun createMockSongCursor(projection: Array<String>): MatrixCursor {
        val cursor = MatrixCursor(projection)
        fun row(
            id: Long,
            title: String,
            artist: String,
            artistId: Long,
            album: String,
            albumId: Long,
            duration: Long,
            path: String
        ): Array<Any?> {
            val values = mapOf<String, Any?>(
                MediaStore.Audio.Media._ID to id,
                MediaStore.Audio.Media.TITLE to title,
                MediaStore.Audio.Media.ARTIST to artist,
                MediaStore.Audio.Media.ARTIST_ID to artistId,
                MediaStore.Audio.Media.ALBUM to album,
                MediaStore.Audio.Media.ALBUM_ID to albumId,
                MediaStore.Audio.Media.DURATION to duration,
                MediaStore.Audio.Media.DATA to path,
                MediaStore.Audio.Media.TRACK to 1,
                MediaStore.Audio.Media.YEAR to 2023,
                MediaStore.Audio.Media.DATE_ADDED to 1_700_000_000L,
                MediaStore.Audio.Media.DATE_MODIFIED to 1_700_000_000L,
                MediaStore.Audio.Media.MIME_TYPE to "audio/mpeg",
                MediaStore.Audio.Media.RELATIVE_PATH to "Music/",
                MediaStore.Audio.Media.GENRE to "Pop"
            )
            return projection.map { values[it] }.toTypedArray()
        }
        cursor.addRow(row(1L, "Test Song 1", "Test Artist 1", 101L, "Test Album 1", 201L, 180000L, "/sdcard/Music/song1.mp3"))
        cursor.addRow(row(2L, "Test Song 2", "Test Artist 2", 102L, "Test Album 2", 202L, 240000L, "/sdcard/Music/song2.mp3"))
        return cursor
    }

    private fun createMockAlbumCursor(): MatrixCursor {
        val cursor = MatrixCursor(arrayOf(
            MediaStore.Audio.Albums._ID, MediaStore.Audio.Albums.ALBUM, MediaStore.Audio.Albums.ARTIST
        ))
        cursor.addRow(arrayOf<Any>(201L, "Test Album 1", "Test Artist 1"))
        cursor.addRow(arrayOf<Any>(202L, "Test Album 2", "Test Artist 2"))
        return cursor
    }

    private fun createMockArtistCursor(): MatrixCursor {
         val cursor = MatrixCursor(arrayOf(
            MediaStore.Audio.Artists._ID, MediaStore.Audio.Artists.ARTIST
        ))
        cursor.addRow(arrayOf<Any>(101L, "Test Artist 1"))
        cursor.addRow(arrayOf<Any>(102L, "Test Artist 2"))
        return cursor
    }

    private fun createMockGenreCursor(): MatrixCursor {
        return MatrixCursor(arrayOf(MediaStore.Audio.GenresColumns.NAME)) // Vacío por defecto, o añadir filas si se testea género.
    }


    @Test
    fun testSyncWorker_success_whenMediaStoreHasData() = runBlocking {
        // Configurar mocks para ContentResolver
        every { mockContentResolver.query(any(), any(), any(), any(), any()) } answers {
            when (firstArg<Uri>().toString()) {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.toString() ->
                    createMockSongCursor(secondArg<Array<String>?>() ?: arrayOf(MediaStore.Audio.Media._ID))
                MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI.toString() -> createMockAlbumCursor()
                MediaStore.Audio.Artists.EXTERNAL_CONTENT_URI.toString() -> createMockArtistCursor()
                else -> createMockGenreCursor()
            }
        }


        // Crear una instancia del Contexto que devuelva nuestro ContentResolver mockeado
        val testContext = object : ContextWrapper(context) {
            override fun getContentResolver(): android.content.ContentResolver {
                return mockContentResolver
            }
        }

        val worker = TestListenableWorkerBuilder<SyncWorker>(testContext) // Usar testContext
            .setWorkerFactory(TestSyncWorkerFactory(musicDao))
            .setInputData(workDataOf(SyncWorker.INPUT_RUN_MAINTENANCE to false))
            .build()

        val result = worker.doWork()
        assertThat(result).isInstanceOf(ListenableWorker.Result.Success::class.java)

        // Verificar datos en la base de datos
        val songsInDb = musicDao.getSongs(emptyList(), false).first()
        assertThat(songsInDb).hasSize(2)
        assertThat(songsInDb.find { it.id == 1L }?.title).isEqualTo("Test Song 1")

        val albumsInDb = musicDao.getAlbums(emptyList(), false, 0, 1).first()
        assertThat(albumsInDb).hasSize(2)
        assertThat(albumsInDb.find { it.id == 201L }?.title).isEqualTo("Test Album 1")

        val artistsInDb = musicDao.getArtists(emptyList(), false).first()
        assertThat(artistsInDb).hasSize(2)
        assertThat(artistsInDb.map { it.name }).contains("Test Artist 1")
    }

    @Test
    fun testSyncWorker_success_whenMediaStoreIsEmpty() = runBlocking {
        // Configurar mocks para ContentResolver para devolver cursores vacíos
        every { mockContentResolver.query(any(), any(), any(), any(), any()) } answers {
            MatrixCursor(secondArg<Array<String>?>() ?: arrayOf(MediaStore.Audio.Media._ID))
        }

        val testContext = object : ContextWrapper(context) {
            override fun getContentResolver(): android.content.ContentResolver {
                return mockContentResolver
            }
        }

        val worker = TestListenableWorkerBuilder<SyncWorker>(testContext)
            .setWorkerFactory(TestSyncWorkerFactory(musicDao))
            .setInputData(workDataOf(SyncWorker.INPUT_RUN_MAINTENANCE to false))
            .build()

        val result = worker.doWork()
        assertThat(result).isInstanceOf(ListenableWorker.Result.Success::class.java)
        assertThat(musicDao.getSongCount().first()).isEqualTo(0)
        assertThat(musicDao.getAlbumCount().first()).isEqualTo(0)
        assertThat(musicDao.getArtistCount().first()).isEqualTo(0)
    }
}

// Wrapper simple para Context para poder mockear getContentResolver
open class ContextWrapper(base: Context) : android.content.ContextWrapper(base)
