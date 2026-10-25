package com.theveloper.pixelplay.data.search

import com.theveloper.pixelplay.data.model.OnlineSearchPlatform
import com.theveloper.pixelplay.data.model.TransientOnlineSong

/**
 * Strategy interface for multi-platform online music search providers.
 */
interface OnlineSearchProvider {
    val platform: OnlineSearchPlatform

    suspend fun searchSongs(query: String, page: Int = 1, limit: Int = 30): List<TransientOnlineSong>

    suspend fun loadPopularSearchTerms(limit: Int): List<String>
}
