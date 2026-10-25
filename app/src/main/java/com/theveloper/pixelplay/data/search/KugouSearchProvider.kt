package com.theveloper.pixelplay.data.search

import com.theveloper.pixelplay.data.model.OnlineSearchPlatform
import com.theveloper.pixelplay.data.model.OnlineSongMapper
import com.theveloper.pixelplay.data.model.TransientOnlineSong
import com.theveloper.pixelplay.data.plugin.PluginSource
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KugouSearchProvider @Inject constructor(
    private val okHttpClient: OkHttpClient
) : OnlineSearchProvider {

    override val platform: OnlineSearchPlatform = OnlineSearchPlatform.KUGOU

    override suspend fun searchSongs(query: String, page: Int, limit: Int): List<TransientOnlineSong> {
        val encodedKeyword = URLEncoder.encode(query, "UTF-8")
        val url = "https://mobilecdn.kugou.com/api/v3/search/song?format=json&keyword=$encodedKeyword&page=$page&pagesize=$limit&showtype=1"
        val root = JSONObject(executeTextRequest(url, referer = "https://www.kugou.com/"))
        val list = root.optJSONObject("data")?.optJSONArray("info") ?: JSONArray()
        return buildList {
            for (index in 0 until list.length()) {
                val item = list.optJSONObject(index) ?: continue
                val hash = normalizeId(item.opt("hash") ?: item.opt("HASH") ?: item.opt("filehash")) ?: continue
                val title = item.optString("songname", item.optString("filename"))
                if (title.isBlank()) continue
                val artist = item.optString("singername").ifBlank { UNKNOWN_ARTIST }
                val album = item.optString("album_name").ifBlank { UNKNOWN_ALBUM }
                val durationMs = item.optLong("duration", 0L).let { if (it > 0L) it * 1000L else 0L }
                val artwork = item.optJSONObject("trans_param")
                    ?.optString("union_cover")
                    ?.takeIf { it.isNotBlank() }
                    ?.replace("{size}", "300")
                add(
                    OnlineSongMapper.toTransient(
                        source = PluginSource.KUGOU,
                        sourceSongId = hash,
                        title = title,
                        artist = artist,
                        album = album,
                        durationMs = durationMs,
                        artworkUrl = artwork
                    )
                )
            }
        }
    }

    override suspend fun loadPopularSearchTerms(limit: Int): List<String> {
        val root = JSONObject(
            executeTextRequest(
                url = "https://gateway.kugou.com/api/v3/search/hot_tab?signature=ee44edb9d7155821412d220bcaf509dd&appid=1005&clientver=10026&plat=0",
                headers = mapOf(
                    "dfid" to "1ssiv93oVqMp27cirf2CvoF1",
                    "mid" to "156798703528610303473757548878786007104",
                    "clienttime" to "1584257267",
                    "x-router" to "msearch.kugou.com",
                    "User-Agent" to "Android9-AndroidPhone-10020-130-0-searchrecommendprotocol-wifi",
                    "kg-rc" to "1"
                )
            )
        )
        val tabs = root.optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
        return buildList {
            for (tabIndex in 0 until tabs.length()) {
                val keywords = tabs.optJSONObject(tabIndex)?.optJSONArray("keywords") ?: continue
                for (keywordIndex in 0 until keywords.length()) {
                    keywords.optJSONObject(keywordIndex)?.optString("keyword")?.let(::add)
                }
            }
        }
    }

    private fun executeTextRequest(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap()
    ): String {
        val requestBuilder = Request.Builder()
            .url(url)
            .header("Accept", "application/json,text/plain,*/*")
        referer?.let { requestBuilder.header("Referer", it) }
        headers.forEach(requestBuilder::header)
        val request = requestBuilder.build()
        return okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Timber.w("Kugou search request failed url=%s code=%d", url.substringBefore('?'), response.code)
            }
            response.body.string()
        }
    }

    private fun normalizeId(value: Any?): String? {
        val raw = value?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return raw.removePrefix("MUSIC_").takeIf { it.isNotBlank() }
    }

    private companion object {
        const val UNKNOWN_ARTIST = "Unknown Artist"
        const val UNKNOWN_ALBUM = "Unknown Album"
    }
}
