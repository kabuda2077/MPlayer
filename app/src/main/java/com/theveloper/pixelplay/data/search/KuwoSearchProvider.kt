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
class KuwoSearchProvider @Inject constructor(
    private val okHttpClient: OkHttpClient
) : OnlineSearchProvider {

    override val platform: OnlineSearchPlatform = OnlineSearchPlatform.KUWO

    override suspend fun searchSongs(query: String, page: Int, limit: Int): List<TransientOnlineSong> {
        val encodedKeyword = URLEncoder.encode(query, "UTF-8")
        val url = "http://search.kuwo.cn/r.s?client=kt&all=$encodedKeyword&pn=0&rn=$limit&uid=794762570&ver=kwplayer_ar_9.2.2.1&vipver=1&show_copyright_off=1&newver=1&ft=music&cluster=0&strategy=2012&encoding=utf8&rformat=json&vermerge=1&mobi=1&issubtitle=1"
        val root = JSONObject(executeTextRequest(url, referer = "https://www.kuwo.cn/"))
        val list = root.optJSONArray("abslist") ?: JSONArray()
        return buildList {
            for (index in 0 until list.length()) {
                val item = list.optJSONObject(index) ?: continue
                val id = normalizeId(item.opt("MUSICRID")) ?: continue
                val title = item.optString("SONGNAME")
                if (title.isBlank()) continue
                val artist = item.optString("ARTIST").ifBlank { UNKNOWN_ARTIST }
                val album = item.optString("ALBUM").ifBlank { UNKNOWN_ALBUM }
                val durationMs = item.optLong("DURATION", 0L).let { if (it > 0L) it * 1000L else 0L }
                val artwork = item.optString("web_albumpic_short")
                    .takeIf { it.isNotBlank() }
                    ?.let { "https://img4.kuwo.cn/star/albumcover/$it" }
                add(
                    OnlineSongMapper.toTransient(
                        source = PluginSource.KUWO,
                        sourceSongId = id,
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
                url = "http://hotword.kuwo.cn/hotword.s?prod=kwplayer_ar_9.3.0.1&corp=kuwo&newver=2&vipver=9.3.0.1&source=kwplayer_ar_9.3.0.1_40.apk&p2p=1&notrace=0&uid=0&plat=kwplayer_ar&rformat=json&encoding=utf8&tabid=1",
                headers = mapOf("User-Agent" to "Dalvik/2.1.0 (Linux; U; Android 9;)")
            )
        )
        val hotItems = root.optJSONArray("tagvalue") ?: JSONArray()
        return buildList {
            for (index in 0 until hotItems.length()) {
                hotItems.optJSONObject(index)?.optString("key")?.let(::add)
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
                Timber.w("Kuwo search request failed url=%s code=%d", url.substringBefore('?'), response.code)
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
