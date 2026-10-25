package com.theveloper.pixelplay.data.search

import com.theveloper.pixelplay.data.model.OnlineSearchPlatform
import com.theveloper.pixelplay.data.model.TransientOnlineSong
import com.theveloper.pixelplay.data.netease.NeteaseRepository
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NeteaseSearchProvider @Inject constructor(
    private val neteaseRepository: NeteaseRepository,
    private val okHttpClient: OkHttpClient
) : OnlineSearchProvider {

    override val platform: OnlineSearchPlatform = OnlineSearchPlatform.NETEASE

    override suspend fun searchSongs(query: String, page: Int, limit: Int): List<TransientOnlineSong> {
        val offset = (page - 1).coerceAtLeast(0) * limit
        return neteaseRepository.searchOnline(query, limit = limit, offset = offset).getOrThrow()
            .map { TransientOnlineSong.from(it, OnlineSearchPlatform.NETEASE) }
    }

    override suspend fun loadPopularSearchTerms(limit: Int): List<String> {
        val root = JSONObject(
            executeTextRequest(
                url = "https://music.163.com/api/search/hot?type=1111",
                referer = "https://music.163.com/"
            )
        )
        val hotItems = root.optJSONObject("result")?.optJSONArray("hots") ?: JSONArray()
        return buildList {
            for (index in 0 until hotItems.length()) {
                hotItems.optJSONObject(index)?.optString("first")?.let(::add)
            }
        }
    }

    private fun executeTextRequest(url: String, referer: String? = null): String {
        val requestBuilder = Request.Builder()
            .url(url)
            .header("Accept", "application/json,text/plain,*/*")
        referer?.let { requestBuilder.header("Referer", it) }
        val request = requestBuilder.build()
        return okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Timber.w("Netease popular terms failed url=%s code=%d", url, response.code)
            }
            response.body.string()
        }
    }
}
