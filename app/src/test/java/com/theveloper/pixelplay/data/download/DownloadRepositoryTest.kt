package com.theveloper.pixelplay.data.download

import androidx.work.WorkManager
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class DownloadRepositoryTest {
    @Test
    fun removesCompletedRecordWhenPublicAudioIsNoLongerReadable() = runTest {
        val dao = mockk<DownloadDao>()
        val store = mockk<PublicAudioDownloadStore>()
        val repository = DownloadRepository(dao, store, mockk<WorkManager>())
        val entity = completedDownload()

        coEvery { dao.getCompletedByContentUri(entity.contentUriString, any()) } returns entity
        every { store.completedAudio(entity) } returns null
        coEvery { dao.deleteByContentUri(entity.contentUriString) } returns Unit

        assertThat(repository.getCompletedAudio(entity.contentUriString)).isNull()
        coVerify(exactly = 1) { dao.deleteByContentUri(entity.contentUriString) }
    }

    private fun completedDownload() = DownloadEntity(
        contentUriString = "qqmusic://mid",
        songId = 1L,
        title = "Song",
        artist = "Artist",
        album = "Album",
        albumArtUriString = null,
        duration = 180_000L,
        source = "tx",
        platformSongId = "mid",
        requestedQuality = "320k",
        actualQuality = "320k",
        status = DownloadStatus.COMPLETED,
        progress = 1f,
        sizeBytes = 1024L,
        mimeType = "audio/mpeg",
        filePath = null,
        publicUri = "content://media/external/audio/media/1",
        errorMessage = null,
        createdAt = 1L,
        updatedAt = 1L
    )
}
