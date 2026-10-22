package com.theveloper.pixelplay.data.recommendation

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.model.OnlineBrowsePlatform
import com.theveloper.pixelplay.data.model.RecommendationAuthState
import com.theveloper.pixelplay.data.model.RecommendationPage
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test

class OnlineRecommendationRepositoryTest {
    @Test
    fun expiredCacheIsPublishedBeforeNetworkAndOnlyFinalPageIsPersisted() = kotlinx.coroutines.runBlocking {
        val netease = mockk<NeteaseRecommendationProvider>()
        val qq = mockk<QqMusicRecommendationProvider>()
        val store = mockk<RecommendationCacheStore>(relaxed = true)
        every { netease.platform } returns OnlineBrowsePlatform.NETEASE
        every { qq.platform } returns OnlineBrowsePlatform.QQ
        val cached = RecommendationPage(
            platform = OnlineBrowsePlatform.QQ,
            authState = RecommendationAuthState.LOGGED_IN,
            sections = emptyList(),
            fetchedAt = 1L
        )
        val fresh = cached.copy(fetchedAt = 2L)
        every { store.read(OnlineBrowsePlatform.QQ) } returns
            RecommendationCacheStore.CachedRecommendation(cached, System.currentTimeMillis() - 16 * 60 * 1000L)
        val network = CompletableDeferred<Result<RecommendationPage>>()
        val cachedEmission = CompletableDeferred<RecommendationPage>()
        coEvery { qq.loadHomePage(false, any()) } coAnswers { network.await() }
        val repository = OnlineRecommendationRepositoryImpl(netease, qq, store)
        val result = async {
            repository.refresh(OnlineBrowsePlatform.QQ, false) { cachedEmission.complete(it) }
        }
        try {
            assertThat(withTimeout(5_000) { cachedEmission.await() }).isEqualTo(cached.copy(isStale = true))
            assertThat(result.isCompleted).isFalse()
            verify(exactly = 0) { store.write(any()) }
            network.complete(Result.success(fresh))
            assertThat(result.await().getOrThrow()).isEqualTo(fresh)
            verify(exactly = 1) { store.write(fresh) }
        } finally {
            network.complete(Result.success(fresh))
            result.cancel()
        }
    }
}
