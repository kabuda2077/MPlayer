package com.theveloper.pixelplay.presentation.online

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.model.OnlineBrowsePlatform
import com.theveloper.pixelplay.data.model.RecommendationAuthState
import com.theveloper.pixelplay.data.model.RecommendationPage
import com.theveloper.pixelplay.data.recommendation.OnlineRecommendationRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnlineBrowseViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun refreshPublishesEachPlatformAsSoonAsItCompletes() = runTest(dispatcher) {
        val netease = CompletableDeferred<Result<RecommendationPage>>()
        val qq = CompletableDeferred<Result<RecommendationPage>>()
        val repository = mockk<OnlineRecommendationRepository>()
        coEvery { repository.refresh(OnlineBrowsePlatform.NETEASE, false) } coAnswers { netease.await() }
        coEvery { repository.refresh(OnlineBrowsePlatform.QQ, false) } coAnswers { qq.await() }
        val viewModel = OnlineBrowseViewModel(repository)
        runCurrent()

        netease.complete(Result.success(page(OnlineBrowsePlatform.NETEASE)))
        runCurrent()

        assertThat(viewModel.uiState.value.pages.map { it.platform })
            .containsExactly(OnlineBrowsePlatform.NETEASE)
        assertThat(viewModel.uiState.value.isLoading).isTrue()

        qq.complete(Result.success(page(OnlineBrowsePlatform.QQ)))
        runCurrent()

        assertThat(viewModel.uiState.value.pages.map { it.platform })
            .containsExactly(OnlineBrowsePlatform.NETEASE, OnlineBrowsePlatform.QQ)
            .inOrder()
        assertThat(viewModel.uiState.value.isLoading).isFalse()
    }

    private fun page(platform: OnlineBrowsePlatform) = RecommendationPage(
        platform = platform,
        authState = RecommendationAuthState.UNKNOWN,
        sections = emptyList(),
        fetchedAt = 1L
    )
}
