package com.theveloper.pixelplay.data.qqmusic

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.database.QqMusicDao
import com.theveloper.pixelplay.data.network.qqmusic.QqMusicApiService
import io.mockk.mockk
import org.junit.Test

class QqLyricsFetcherTest {
    private val fetcher = QqLyricsFetcher(
        qqMusicApiService = mockk<QqMusicApiService>(relaxed = true),
        qqMusicDao = mockk<QqMusicDao>(relaxed = true)
    )

    @Test
    fun relativeQrcWordOffsetsAreConvertedToGlobalPlaybackTime() {
        val lyrics = fetcher.parsePersistedRaw(
            "qqlyrics://{\"qrc\":\"[10000,3000]你(0,500)好(500,500)\"}"
        )

        val line = requireNotNull(lyrics?.synced?.single())
        assertThat(line.time).isEqualTo(10_000)
        assertThat(line.words.orEmpty().map { it.time }).containsExactly(10_000, 10_500).inOrder()
    }

    @Test
    fun absoluteQrcWordTimesAreNotOffsetTwice() {
        val lyrics = fetcher.parsePersistedRaw(
            "qqlyrics://{\"qrc\":\"[10000,3000]你(10000,500)好(10500,500)\"}"
        )

        val line = requireNotNull(lyrics?.synced?.single())
        assertThat(line.words.orEmpty().map { it.time }).containsExactly(10_000, 10_500).inOrder()
    }
}
