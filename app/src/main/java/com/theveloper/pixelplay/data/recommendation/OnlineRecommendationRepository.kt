package com.theveloper.pixelplay.data.recommendation

import com.theveloper.pixelplay.data.model.OnlineBrowsePlatform
import com.theveloper.pixelplay.data.model.RecommendationPage
import com.theveloper.pixelplay.data.model.RemoteChartDetail
import com.theveloper.pixelplay.data.model.RemoteChartReference
import com.theveloper.pixelplay.data.model.RemotePlaylistDetail
import com.theveloper.pixelplay.data.model.RemotePlaylistReference
import com.theveloper.pixelplay.data.model.TransientOnlineSong
import kotlinx.coroutines.flow.StateFlow

interface OnlineRecommendationProvider {
    val platform: OnlineBrowsePlatform

    suspend fun loadHomePage(forceRefresh: Boolean = false): Result<RecommendationPage>

    suspend fun loadSection(sectionId: String): Result<List<TransientOnlineSong>>

    suspend fun loadPlaylist(reference: RemotePlaylistReference): Result<RemotePlaylistDetail>

    suspend fun loadChart(reference: RemoteChartReference): Result<RemoteChartDetail>
}

interface OnlineRecommendationRepository {
    val platform: StateFlow<OnlineBrowsePlatform>

    suspend fun setPlatform(platform: OnlineBrowsePlatform)

    suspend fun refresh(
        platform: OnlineBrowsePlatform = this.platform.value,
        force: Boolean = false
    ): Result<RecommendationPage>

    suspend fun loadPlaylist(reference: RemotePlaylistReference): Result<RemotePlaylistDetail>

    suspend fun loadChart(reference: RemoteChartReference): Result<RemoteChartDetail>

    suspend fun loadSection(
        platform: OnlineBrowsePlatform,
        sectionId: String
    ): Result<List<TransientOnlineSong>>
}
