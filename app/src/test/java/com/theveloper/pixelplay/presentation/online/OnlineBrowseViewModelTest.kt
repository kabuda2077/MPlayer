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
        coEvery { repository.refresh(OnlineBrowsePlatform.NETEASE, false, any()) } coAnswers { netease.await() }
        coEvery { repository.refresh(OnlineBrowsePlatform.QQ, false, any()) } coAnswers { qq.await() }
        val viewModel = OnlineBrowseViewModel(repository)
        runCurrent()

        netease.complete(Result.success(page(OnlineBrowsePlatform.NETEASE)))
        runCurrent()

        assertThat(viewModel.uiState.value.pages.map { it.platform })
            .containsExactly(OnlineBrowsePlatform.NETEASE)
        assertThat(viewModel.uiState.value.isLoading).isTrue()
        assertThat(viewModel.uiState.value.loadingPlatforms).containsExactly(OnlineBrowsePlatform.QQ)

        qq.complete(Result.success(page(OnlineBrowsePlatform.QQ)))
        runCurrent()

        assertThat(viewModel.uiState.value.pages.map { it.platform })
            .containsExactly(OnlineBrowsePlatform.NETEASE, OnlineBrowsePlatform.QQ)
            .inOrder()
        assertThat(viewModel.uiState.value.isLoading).isFalse()
        assertThat(viewModel.uiState.value.loadingPlatforms).isEmpty()
    }

    @Test
    fun partialQqPageIsVisibleWhileEnrichmentIsStillLoading() = runTest(dispatcher) {
        val completion = CompletableDeferred<Result<RecommendationPage>>()
        val partial = page(OnlineBrowsePlatform.QQ).copy(fetchedAt = 2L)
        val final = partial.copy(fetchedAt = 3L)
        val repository = mockk<OnlineRecommendationRepository>()
        coEvery { repository.refresh(OnlineBrowsePlatform.NETEASE, false, any()) } returns
            Result.success(page(OnlineBrowsePlatform.NETEASE))
        coEvery { repository.refresh(OnlineBrowsePlatform.QQ, false, any()) } coAnswers {
            thirdArg<suspend (RecommendationPage) -> Unit>().invoke(partial)
            completion.await()
        }
        val viewModel = OnlineBrowseViewModel(repository)
        runCurrent()

        assertThat(viewModel.uiState.value.pages.last()).isEqualTo(partial)
        assertThat(viewModel.uiState.value.loadingPlatforms).containsExactly(OnlineBrowsePlatform.QQ)

        completion.complete(Result.success(final))
        runCurrent()
        assertThat(viewModel.uiState.value.pages.last()).isEqualTo(final)
        assertThat(viewModel.uiState.value.isLoading).isFalse()
    }

    @Test
    fun platformFailureIsExposedEvenWhenOtherPlatformSucceeds() = runTest(dispatcher) {
        val repository = mockk<OnlineRecommendationRepository>()
        coEvery { repository.refresh(OnlineBrowsePlatform.NETEASE, false, any()) } returns
            Result.success(page(OnlineBrowsePlatform.NETEASE))
        coEvery { repository.refresh(OnlineBrowsePlatform.QQ, false, any()) } returns
            Result.failure(IllegalStateException("QQ timeout"))
        val viewModel = OnlineBrowseViewModel(repository)
        runCurrent()

        assertThat(viewModel.uiState.value.platformErrors[OnlineBrowsePlatform.QQ]).isEqualTo("QQ timeout")
        assertThat(viewModel.uiState.value.error).isNull()
        assertThat(viewModel.uiState.value.loadingPlatforms).isEmpty()
    }

    @Test
    fun cancelledRefreshCannotOverwriteNewRefresh() = runTest(dispatcher) {
        val repository = mockk<OnlineRecommendationRepository>()
        val oldCompletion = CompletableDeferred<Result<RecommendationPage>>()
        coEvery { repository.refresh(any(), false, any()) } coAnswers {
            val platform = firstArg<OnlineBrowsePlatform>()
            // Model a provider wrapping cancellation in Result or returning cached data.
            runCatching { oldCompletion.await().getOrThrow() }
                .recover { page(platform) }
        }
        coEvery { repository.refresh(any(), true, any()) } coAnswers {
            Result.success(page(firstArg()).copy(fetchedAt = 4L))
        }
        val viewModel = OnlineBrowseViewModel(repository)
        runCurrent()
        viewModel.refresh(force = true)
        runCurrent()
        oldCompletion.complete(Result.success(page(OnlineBrowsePlatform.QQ)))
        runCurrent()

        assertThat(viewModel.uiState.value.pages.map { it.fetchedAt }).containsExactly(4L, 4L)
        assertThat(viewModel.uiState.value.isLoading).isFalse()
    }

    private fun page(platform: OnlineBrowsePlatform) = RecommendationPage(
        platform = platform,
        authState = RecommendationAuthState.UNKNOWN,
        sections = emptyList(),
        fetchedAt = 1L
    )
}
