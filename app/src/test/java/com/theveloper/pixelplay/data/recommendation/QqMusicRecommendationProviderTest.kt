package com.theveloper.pixelplay.data.recommendation

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.model.OnlineBrowsePlatform
import com.theveloper.pixelplay.data.model.RecommendationPage
import com.theveloper.pixelplay.data.model.RecommendationSection
import com.theveloper.pixelplay.data.model.RecommendationSectionKind
import com.theveloper.pixelplay.data.model.RemotePlaylistReference
import com.theveloper.pixelplay.data.network.qqmusic.QqMusicApiService
import com.theveloper.pixelplay.data.qqmusic.QqMusicRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test

class QqMusicRecommendationProviderTest {
    private val api = mockk<QqMusicApiService>()
    private val account = mockk<QqMusicRepository>()

    @Before
    fun setUp() {
        mockkObject(RecommendationJson)
        every { account.isLoggedIn } returns true
        every { RecommendationJson.parseQqSongs(any()) } returns emptyList()
        every { RecommendationJson.parseQqPlaylists(any()) } returns emptyList()
    }

    @After
    fun tearDown() = unmockkObject(RecommendationJson)

    @Test
    fun dailyCardIsPublishedBeforeUnrelatedRequestsCompleteAndNeedsNoCountRequest() = kotlinx.coroutines.runBlocking {
        val remainingRequests = CompletableDeferred<String>()
        val partial = CompletableDeferred<RecommendationPage>()
        val daily = RemotePlaylistReference(OnlineBrowsePlatform.QQ, "30", "每日30首")
        val knownCount = RemotePlaylistReference(OnlineBrowsePlatform.QQ, "42", "推荐歌单", songCount = 42)
        every { RecommendationJson.parseQqHomeFeed("feed") } returns listOf(
            RecommendationSection("feed", "推荐", RecommendationSectionKind.RECOMMENDED_PLAYLISTS, listOf(daily, knownCount))
        )
        coEvery { api.getRecommendHomeFeed() } returns "feed"
        coEvery { api.getRecommendNewSongs() } coAnswers { remainingRequests.await() }
        coEvery { api.getRecommendSonglists() } coAnswers { remainingRequests.await() }
        coEvery { api.getTopListDetail(any(), any(), any()) } coAnswers { remainingRequests.await() }
        val provider = QqMusicRecommendationProvider(api, account)
        val result = async { provider.loadHomePage(false) { partial.complete(it) } }
        try {
            val firstPage = withTimeout(5_000) { partial.await() }
            val firstDaily = firstPage.sections.first().items.first() as RemotePlaylistReference
            assertThat(firstDaily.title).isEqualTo("每日30首")
            assertThat(firstDaily.songCount).isEqualTo(30)
            assertThat(result.isCompleted).isFalse()
            remainingRequests.complete("other")
            assertThat(result.await().isSuccess).isTrue()
            coVerify(exactly = 0) { api.getPlaylistDetail(any(), any(), any()) }
        } finally {
            remainingRequests.complete("other")
            result.cancel()
        }
    }
}
