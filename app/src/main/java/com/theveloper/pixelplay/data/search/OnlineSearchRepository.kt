package com.theveloper.pixelplay.data.search

import com.theveloper.pixelplay.data.model.OnlineSongMapper
import com.theveloper.pixelplay.data.model.OnlineSearchPlatform
import com.theveloper.pixelplay.data.model.OnlineSearchResultItem
import com.theveloper.pixelplay.data.model.TransientOnlineSong
import com.theveloper.pixelplay.data.netease.NeteaseRepository
import com.theveloper.pixelplay.data.network.qqmusic.QqMusicApiService
import com.theveloper.pixelplay.data.plugin.PluginSource
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

@Singleton
class OnlineSearchRepository @Inject constructor(
    private val neteaseRepository: NeteaseRepository,
    private val qqMusicApiService: QqMusicApiService,
    private val okHttpClient: OkHttpClient
) {
    suspend fun loadPopularSearchTerms(
        platform: OnlineSearchPlatform,
        limit: Int = POPULAR_SEARCH_LIMIT
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val terms = when (platform) {
                OnlineSearchPlatform.NETEASE -> loadNeteasePopularTerms()
                OnlineSearchPlatform.QQ -> qqMusicApiService.getHotSearchTerms(limit)
                OnlineSearchPlatform.KUGOU -> loadKugouPopularTerms()
                OnlineSearchPlatform.KUWO -> loadKuwoPopularTerms()
            }
            terms.asSequence()
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
                .take(limit)
                .toList()
                .also { require(it.isNotEmpty()) { "Popular search returned no terms" } }
        }.onFailure { throwable ->
            Timber.w(throwable, "Popular search failed platform=%s", platform.source.lxSource)
        }
    }

    suspend fun searchSongs(
        query: String,
        platform: OnlineSearchPlatform,
        limit: Int = DEFAULT_LIMIT
    ): Result<List<OnlineSearchResultItem>> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext Result.success(emptyList())

        runCatching {
            when (platform) {
                OnlineSearchPlatform.NETEASE -> searchNetease(query, limit)
                OnlineSearchPlatform.QQ -> searchQq(query, limit)
                OnlineSearchPlatform.KUGOU -> searchKugou(query, limit)
                OnlineSearchPlatform.KUWO -> searchKuwo(query, limit)
            }.map { song -> OnlineSearchResultItem(transientSong = song, platform = platform) }
        }.onFailure { throwable ->
            Timber.w(
                throwable,
                "Online song search failed platform=%s query=%s",
                platform.source.lxSource,
                query
            )
        }
    }

    private suspend fun searchNetease(query: String, limit: Int): List<TransientOnlineSong> =
        neteaseRepository.searchOnline(query, limit).getOrThrow()
            .map { TransientOnlineSong.from(it, OnlineSearchPlatform.NETEASE) }

    private suspend fun searchQq(query: String, limit: Int): List<TransientOnlineSong> =
        qqMusicApiService.searchSongs(query, page = 1, pageSize = limit)
            .mapNotNull { item ->
                val songMid = item.mid.ifBlank { item.qqSongId }.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                OnlineSongMapper.toTransient(
                    source = PluginSource.QQ_MUSIC,
                    sourceSongId = songMid,
                    title = item.title,
                    artist = item.artist,
                    album = item.album,
                    durationMs = item.durationMs,
                    artworkUrl = item.albumArtUrl
                )
            }

    private fun loadNeteasePopularTerms(): List<String> {
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

    private fun loadKugouPopularTerms(): List<String> {
        val root = JSONObject(
            executeTextRequest(
                url = "http://gateway.kugou.com/api/v3/search/hot_tab?signature=ee44edb9d7155821412d220bcaf509dd&appid=1005&clientver=10026&plat=0",
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

    private fun loadKuwoPopularTerms(): List<String> {
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

    private fun searchKugou(query: String, limit: Int): List<TransientOnlineSong> {
        val encodedKeyword = URLEncoder.encode(query, "UTF-8")
        val url = "http://mobilecdn.kugou.com/api/v3/search/song?format=json&keyword=$encodedKeyword&page=1&pagesize=$limit&showtype=1"
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

    private fun searchKuwo(query: String, limit: Int): List<TransientOnlineSong> {
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
                Timber.w("Online search request failed url=%s code=%d", url.substringBefore('?'), response.code)
            }
            response.body.string()
        }
    }

    private fun normalizeId(value: Any?): String? {
        val raw = value?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return raw.removePrefix("MUSIC_").takeIf { it.isNotBlank() }
    }

    private companion object {
        const val DEFAULT_LIMIT = 30
        const val POPULAR_SEARCH_LIMIT = 8
        const val UNKNOWN_ARTIST = "Unknown Artist"
        const val UNKNOWN_ALBUM = "Unknown Album"
    }
}
