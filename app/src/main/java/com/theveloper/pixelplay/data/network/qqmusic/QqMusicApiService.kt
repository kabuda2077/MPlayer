package com.theveloper.pixelplay.data.network.qqmusic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.zip.InflaterInputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class QqMusicApiService @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    // P1-3: Inject singleton OkHttpClient instead of creating a new one.
    // Use newBuilder() to share the base connection pool and dispatcher.
    baseOkHttpClient: OkHttpClient
) {

    data class SearchSongResult(
        /** QQ Music's numeric song identifier used by the lyric API, not a Room library id. */
        val qqSongId: String,
        val mid: String,
        val title: String,
        val artist: String,
        val album: String,
        val albumArtUrl: String?,
        val durationMs: Long
    )

    private val cookieStore: MutableMap<String, MutableList<Cookie>> = mutableMapOf()

    @Volatile
    private var persistedCookies: Map<String, String> = emptyMap()

    private val okHttpClient: OkHttpClient = baseOkHttpClient.newBuilder()
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                val host = url.host
                val list = cookieStore.getOrPut(host) { mutableListOf() }
                list.removeAll { c -> cookies.any { it.name == c.name } }
                list.addAll(cookies)
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                return cookieStore[url.host] ?: emptyList()
            }
        })
        .addInterceptor(com.theveloper.pixelplay.data.remote.qqmusic.QQMusicEncryptInterceptor(
            com.theveloper.pixelplay.data.remote.qqmusic.QQSignGenerator(context)
        ))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun hasLogin(): Boolean {
        return !persistedCookies["uin"].isNullOrBlank() ||
            !persistedCookies["qqmusic_key"].isNullOrBlank() ||
            !persistedCookies["qm_keyst"].isNullOrBlank()
    }

    fun setPersistedCookies(cookies: Map<String, String>) {
        persistedCookies = cookies.toMap()
        logCookieKeyDiagnostics("setPersistedCookies")
        seedCookieJar("y.qq.com")
        seedCookieJar("u6.y.qq.com")
        seedCookieJar("u.y.qq.com")
        seedCookieJar("c.y.qq.com")
    }

    fun logout() {
        cookieStore.clear()
        persistedCookies = emptyMap()
    }

    // ─── Content ───────────────────────────────────────────────────────

    private fun getGTK(): Long {
        val skey = persistedCookies["p_skey"] ?: persistedCookies["skey"] ?: ""
        var hash = 5381L
        for (c in skey) {
            hash += (hash shl 5) + c.code.toLong()
        }
        return hash and 0x7fffffffL
    }

    /**
     * Get user's playlists (created and collected).
     */
    suspend fun getUserPlaylists(start: Int = 0, count: Int = 100): String = withContext(Dispatchers.IO) {
        val uin = extractUin()
        val gtk = getGTK()
        val ein = (start + count - 1).coerceAtLeast(start)
        Timber.d("getUserPlaylists: uin=$uin, gtk=$gtk")
        val url = "https://c.y.qq.com/fav/fcgi-bin/fcg_get_profile_order_asset.fcg?" +
                "format=json&inCharset=utf-8&outCharset=utf-8&notice=0" +
                "&platform=yqq&needNewCode=1" +
                "&uin=$uin&g_tk=$gtk&cid=205360956&userid=$uin&reqtype=3&sin=$start&ein=$ein"

        makeGetRequest(url)
    }

    /**
     * Get user's created playlists.
     */
    suspend fun getUserCreatedPlaylists(start: Int = 0, size: Int = 100): String = withContext(Dispatchers.IO) {
        val uin = extractUin()
        val gtk = getGTK()
        Timber.d("getUserCreatedPlaylists: uin=$uin, gtk=$gtk, start=$start, size=$size")
        val url = "https://c6.y.qq.com/rsc/fcgi-bin/fcg_user_created_diss?" +
                "format=json&inCharset=utf-8&outCharset=utf-8&notice=0" +
                "&platform=yqq.json&needNewCode=1" +
                "&uin=$uin&g_tk=$gtk&g_tk_new_20200303=$gtk&hostuin=$uin&sin=$start&size=$size"

        makeGetRequest(url)
    }

    /**
     * Get user avatar URL based on encrypted uin from euin cookie.
     */
    fun getUserAvatarUrl(): String? {
        val uin = extractUin()
        if (uin == "0" || uin.isBlank()) return null

        // Use QQ avatar URL instead of QQ Music avatar
        // QQ Music avatar requires specific cookies and may not be set for all users
        // QQ avatar is more reliable: https://q1.qlogo.cn/g?b=qq&nk={QQ_NUMBER}&s=140
        val avatarUrl = "https://q1.qlogo.cn/g?b=qq&nk=$uin&s=140"

        Timber.d("getUserAvatarUrl: uin=$uin, url=$avatarUrl")
        return avatarUrl
    }


    /**
     * Get playlist detail including all songs.
     */
    suspend fun getPlaylistDetail(
        playlistId: Long,
        songBegin: Int = 0,
        songNum: Int = 1000
    ): String = withContext(Dispatchers.IO) {
        // First try the modern mobile RPC endpoint via musicu.fcg
        val rpcResult = runCatching {
            val payload = JSONObject(
                mapOf(
                    "comm" to mapOf(
                        "ct" to 11,
                        "cv" to "1003006",
                        "v" to "1003006",
                        "os_ver" to "15",
                        "phonetype" to "24122RKC7C",
                        "tmeAppID" to "qqmusiclight",
                        "nettype" to "NETWORK_WIFI",
                        "udid" to "0"
                    ),
                    "request" to mapOf(
                        "module" to "music.srfDissInfo.DissInfo",
                        "method" to "CgiGetDiss",
                        "param" to mapOf(
                            "disstid" to playlistId,
                            "tag" to 1,
                            "userinfo" to 1,
                            "song_begin" to songBegin,
                            "song_num" to songNum
                        )
                    )
                )
            )
            makePostRequest("https://u.y.qq.com/cgi-bin/musicu.fcg", payload)
        }.getOrNull()

        if (!rpcResult.isNullOrBlank()) {
            val root = runCatching { JSONObject(rpcResult) }.getOrNull()
            val code = root?.optInt("code", -1) ?: -1
            val reqCode = root?.optJSONObject("request")?.optInt("code", -1) ?: -1
            if (code == 0 && reqCode == 0) {
                return@withContext rpcResult
            }
        }

        val gtk = getGTK()
        val url = "https://c.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg?" +
                "type=1&json=1&utf8=1&onlysong=0" +
                "&disstid=$playlistId&song_begin=$songBegin&song_num=$songNum" +
                "&g_tk=$gtk&format=json&inCharset=utf-8&outCharset=utf-8"

        makeGetRequest(url)
    }


    suspend fun searchSongs(keyword: String, page: Int = 1, pageSize: Int = 20): List<SearchSongResult> = withContext(Dispatchers.IO) {
        val trimmed = keyword.trim()
        if (trimmed.isBlank()) return@withContext emptyList()

        val searchId = buildSearchId()
        val comm = mutableMapOf<String, Any>(
            "ct" to "11",
            "cv" to "13020508",
            "v" to "13020508",
            "tmeAppID" to "qqmusic",
            "format" to "json",
            "inCharset" to "utf-8",
            "outCharset" to "utf-8",
            "uid" to "3931641530",
            "QIMEI36" to "6c9d3cd110abca9b16311cee10001e717614"
        )
        val uin = extractUin()
        val keyst = extractKeyst()
        if (uin != "0" && keyst.isNotBlank()) {
            comm["qq"] = uin
            comm["authst"] = keyst
        }

        val payload = JSONObject(
            mapOf(
                "comm" to comm,
                "request" to mapOf(
                    "module" to "music.search.SearchCgiService",
                    "method" to "DoSearchForQQMusicMobile",
                    "param" to mapOf(
                        "searchid" to searchId,
                        "query" to trimmed,
                        "search_type" to 0,
                        "num_per_page" to pageSize,
                        "page_num" to page,
                        "highlight" to 1,
                        "grp" to 1
                    )
                )
            )
        )

        val results = runCatching {
            val responseText = makePostRequest("https://u.y.qq.com/cgi-bin/musicu.fcg", payload)
            val response = JSONObject(responseText)
            val request = response.optJSONObject("request")
                ?: response.optJSONObject("music.search.SearchCgiService.DoSearchForQQMusicMobile")
                ?: response
            val data = request.optJSONObject("data") ?: return@runCatching emptyList()
            val body = data.optJSONObject("body") ?: return@runCatching emptyList()
            val items = body.optJSONArray("item_song") ?: JSONArray()
            buildList {
                for (index in 0 until items.length()) {
                    val song = items.optJSONObject(index) ?: continue
                    val songMid = song.optString("mid").ifBlank { song.optString("songmid") }
                    val songId = song.opt("id")?.toString().orEmpty().ifBlank { song.opt("songid")?.toString().orEmpty() }
                    if (songMid.isBlank() && songId.isBlank()) continue

                    val rawTitle = song.optString("title").ifBlank { song.optString("name") }.ifBlank { song.optString("songname") }
                    val title = cleanHighlightTags(rawTitle)
                    val singers = song.optJSONArray("singer")
                    val artist = cleanHighlightTags(buildArtistName(singers))

                    val albumObject = song.optJSONObject("album")
                    val albumName = cleanHighlightTags(albumObject?.optString("name") ?: song.optString("albumname").ifBlank { song.optString("album") })
                    val albumMid = albumObject?.optString("mid").orEmpty()
                        .ifBlank { song.optString("albummid") }
                        .ifBlank { albumObject?.optString("pmid").orEmpty() }

                    val albumArtUrl = albumMid.takeIf { it.isNotBlank() }
                        ?.let { "https://y.qq.com/music/photo_new/T002R300x300M000$it.jpg" }

                    val durationSeconds = song.optLong("interval", 0L)
                    val durationMs = durationSeconds * 1000L

                    add(
                        SearchSongResult(
                            qqSongId = songId,
                            mid = songMid,
                            title = title,
                            artist = artist.ifBlank { "Unknown Artist" },
                            album = albumName,
                            albumArtUrl = albumArtUrl,
                            durationMs = durationMs
                        )
                    )
                }
            }
        }.getOrElse { error ->
            Timber.w(error, "QqMusicApiService: DoSearchForQQMusicMobile failed")
            emptyList()
        }

        if (results.isNotEmpty()) {
            return@withContext results
        }

        val adaptorResults = searchSongsFromAdaptor(trimmed, page, pageSize)
        if (adaptorResults.isNotEmpty()) {
            return@withContext adaptorResults
        }

        // Last-resort fallback to client search
        searchSongsFromClientSearch(trimmed, page, pageSize)
    }

    private fun searchSongsFromAdaptor(keyword: String, page: Int, pageSize: Int): List<SearchSongResult> {
        return runCatching {
            val searchId = buildSearchId()
            val comm = mapOf(
                "ct" to "11",
                "cv" to "13020508",
                "v" to "13020508",
                "tmeAppID" to "qqmusic",
                "format" to "json",
                "inCharset" to "utf-8",
                "outCharset" to "utf-8",
                "uid" to "3931641530",
                "QIMEI36" to "6c9d3cd110abca9b16311cee10001e717614"
            )
            val methodKey = "music.adaptor.SearchAdaptor.do_search_v2"
            val payload = JSONObject(
                mapOf(
                    "comm" to comm,
                    methodKey to mapOf(
                        "module" to "music.adaptor.SearchAdaptor",
                        "method" to "do_search_v2",
                        "param" to mapOf(
                            "searchid" to searchId,
                            "query" to keyword,
                            "search_type" to 100,
                            "page_num" to pageSize,
                            "page_id" to page,
                            "highlight" to 1,
                            "grp" to 1
                        )
                    )
                )
            )
            val response = JSONObject(makePostRequest("https://u.y.qq.com/cgi-bin/musicu.fcg", payload))
            val serviceObj = response.optJSONObject(methodKey) ?: return emptyList()
            val data = serviceObj.optJSONObject("data") ?: return emptyList()
            val body = data.optJSONObject("body") ?: return emptyList()
            val itemSong = body.optJSONObject("item_song") ?: return emptyList()
            val items = itemSong.optJSONArray("items") ?: return emptyList()
            buildList {
                for (index in 0 until items.length()) {
                    val song = items.optJSONObject(index) ?: continue
                    val songMid = song.optString("mid").ifBlank { song.optString("songmid") }
                    val songId = song.opt("id")?.toString().orEmpty().ifBlank { song.opt("songid")?.toString().orEmpty() }
                    if (songMid.isBlank() && songId.isBlank()) continue

                    val rawTitle = song.optString("title").ifBlank { song.optString("name") }
                    val title = cleanHighlightTags(rawTitle)
                    val singers = song.optJSONArray("singer")
                    val artist = cleanHighlightTags(buildArtistName(singers))

                    val albumObject = song.optJSONObject("album")
                    val albumName = cleanHighlightTags(albumObject?.optString("name").orEmpty())
                    val albumMid = albumObject?.optString("mid").orEmpty()
                        .ifBlank { song.optString("albummid") }

                    val albumArtUrl = albumMid.takeIf { it.isNotBlank() }
                        ?.let { "https://y.qq.com/music/photo_new/T002R300x300M000$it.jpg" }

                    val durationSeconds = song.optLong("interval", 0L)
                    val durationMs = durationSeconds * 1000L

                    add(
                        SearchSongResult(
                            qqSongId = songId,
                            mid = songMid,
                            title = title,
                            artist = artist.ifBlank { "Unknown Artist" },
                            album = albumName,
                            albumArtUrl = albumArtUrl,
                            durationMs = durationMs
                        )
                    )
                }
            }
        }.getOrElse { error ->
            Timber.w(error, "QqMusicApiService: searchSongsFromAdaptor fallback failed")
            emptyList()
        }
    }

    private fun cleanHighlightTags(text: String): String {
        if (!text.contains("<")) return text
        return text.replace("<em>", "").replace("</em>", "")
            .replace(Regex("<[^>]+>"), "")
    }

    private fun searchSongsFromClientSearch(keyword: String, page: Int, pageSize: Int): List<SearchSongResult> {
        return runCatching {
            val encodedKeyword = java.net.URLEncoder.encode(keyword, "UTF-8")
            val url = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?p=$page&n=$pageSize&w=$encodedKeyword&format=json"
            val responseText = makeGetRequest(url)
            val jsonStr = if (responseText.startsWith("callback(") || responseText.startsWith("jsonp")) {
                responseText.substringAfter("(").substringBeforeLast(")")
            } else {
                responseText
            }
            val response = JSONObject(jsonStr)
            val data = response.optJSONObject("data") ?: return emptyList()
            val songObj = data.optJSONObject("song") ?: return emptyList()
            val items = songObj.optJSONArray("list") ?: return emptyList()
            buildList {
                for (index in 0 until items.length()) {
                    val song = items.optJSONObject(index) ?: continue
                    val singers = song.optJSONArray("singer")
                    val artist = buildArtistName(singers)
                    val albumMid = song.optString("albummid")
                    val songMid = song.optString("songmid").ifBlank { song.optString("mid") }
                    val songId = song.opt("songid")?.toString().orEmpty().ifBlank { song.opt("id")?.toString().orEmpty() }
                    if (songMid.isBlank() && songId.isBlank()) continue
                    add(
                        SearchSongResult(
                            qqSongId = songId,
                            mid = songMid,
                            title = song.optString("songname").ifBlank { song.optString("title") },
                            artist = artist.ifBlank { "Unknown Artist" },
                            album = song.optString("albumname").ifBlank { song.optString("album") },
                            albumArtUrl = albumMid
                                .takeIf { it.isNotBlank() }
                                ?.let { "https://y.qq.com/music/photo_new/T002R300x300M000$it.jpg" },
                            durationMs = song.optLong("interval", 0L) * 1000L
                        )
                    )
                }
            }
        }.getOrElse { error ->
            Timber.w(error, "QqMusicApiService: client_search_cp failed")
            emptyList()
        }
    }

    suspend fun searchPlaylists(keyword: String, page: Int = 1, pageSize: Int = 30): String = withContext(Dispatchers.IO) {
        val searchId = buildSearchId()
        val comm = mutableMapOf<String, Any>(
            "ct" to "11",
            "cv" to "13020508",
            "v" to "13020508",
            "tmeAppID" to "qqmusic",
            "format" to "json",
            "inCharset" to "utf-8",
            "outCharset" to "utf-8",
            "uid" to "3931641530",
            "QIMEI36" to "6c9d3cd110abca9b16311cee10001e717614"
        )
        val uin = extractUin()
        val keyst = extractKeyst()
        if (uin != "0" && keyst.isNotBlank()) {
            comm["qq"] = uin
            comm["authst"] = keyst
        }
        val payload = JSONObject(
            mapOf(
                "comm" to comm,
                "request" to mapOf(
                    "module" to "music.search.SearchCgiService",
                    "method" to "DoSearchForQQMusicMobile",
                    "param" to mapOf(
                        "search_id" to searchId,
                        "query" to keyword,
                        "search_type" to 3,
                        "num_per_page" to pageSize,
                        "page_num" to page,
                        "highlight" to 1,
                        "grp" to 1
                    )
                )
            )
        )
        makePostRequest("https://u.y.qq.com/cgi-bin/musicu.fcg", payload)
    }

    suspend fun getHotSearchTerms(limit: Int = 8): List<String> = withContext(Dispatchers.IO) {
        val payload = JSONObject(
            mapOf(
                "comm" to mapOf(
                    "ct" to 11,
                    "cv" to "1003006",
                    "v" to "1003006",
                    "os_ver" to "15",
                    "phonetype" to "24122RKC7C",
                    "tmeAppID" to "qqmusiclight",
                    "nettype" to "NETWORK_WIFI",
                    "udid" to "0"
                ),
                "req_0" to mapOf(
                    "module" to "music.musicsearch.HotkeyService",
                    "method" to "GetHotkeyForQQMusicMobile",
                    "param" to mapOf("search_id" to buildSearchId())
                )
            )
        )

        val response = JSONObject(makePostRequest("https://u.y.qq.com/cgi-bin/musicu.fcg", payload))
        val hotKeys = response.optJSONObject("req_0")
            ?.optJSONObject("data")
            ?.optJSONArray("vec_hotkey")
            ?: JSONArray()
        buildList {
            for (index in 0 until hotKeys.length()) {
                val keyword = hotKeys.optJSONObject(index)
                    ?.optString("query")
                    ?.trim()
                    .orEmpty()
                if (keyword.isNotBlank() && keyword !in this) add(keyword)
                if (size >= limit) break
            }
        }
    }

    // ─── Recommendations ─────────────────────────────────────────────

    suspend fun getRecommendHomeFeed(
        page: Int = 1,
        direction: Int = 0,
        shelfCount: Int = 0,
        exposedShelfIds: List<String> = emptyList()
    ): String = withContext(Dispatchers.IO) {
        val payload = JSONObject(
            mapOf(
                "comm" to mapOf(
                    "ct" to 11,
                    "cv" to "1003006",
                    "v" to "1003006",
                    "os_ver" to "15",
                    "phonetype" to "24122RKC7C",
                    "tmeAppID" to "qqmusiclight",
                    "nettype" to "NETWORK_WIFI",
                    "udid" to "0"
                ),
                "request" to mapOf(
                    "module" to "music.recommend.RecommendFeed",
                    "method" to "get_recommend_feed",
                    "param" to mapOf(
                        "direction" to direction,
                        "page" to page,
                        "s_num" to shelfCount,
                        "v_cache" to exposedShelfIds
                    )
                )
            )
        )
        makePostRequest("https://u.y.qq.com/cgi-bin/musicu.fcg", payload)
    }

    suspend fun getRecommendSonglists(from: Int = 0, size: Int = 20): String = withContext(Dispatchers.IO) {
        val payload = JSONObject(
            mapOf(
                "comm" to mapOf("ct" to 11, "cv" to "1003006"),
                "request" to mapOf(
                    "module" to "music.playlist.PlaylistSquare",
                    "method" to "GetRecommendFeed",
                    "param" to mapOf("From" to from, "Size" to size)
                )
            )
        )
        makePostRequest("https://u.y.qq.com/cgi-bin/musicu.fcg", payload)
    }

    suspend fun getRecommendNewSongs(type: Int = 5): String = withContext(Dispatchers.IO) {
        val payload = JSONObject(
            mapOf(
                "comm" to mapOf("ct" to 11, "cv" to "1003006"),
                "request" to mapOf(
                    "module" to "newsong.NewSongServer",
                    "method" to "get_new_song_info",
                    "param" to mapOf("type" to type)
                )
            )
        )
        makePostRequest("https://u.y.qq.com/cgi-bin/musicu.fcg", payload)
    }

    suspend fun getTopListDetail(topId: Int, offset: Int = 0, count: Int = 100): String = withContext(Dispatchers.IO) {
        val payload = JSONObject(
            mapOf(
                "comm" to mapOf("ct" to 11, "cv" to "1003006"),
                "request" to mapOf(
                    // This is the current mobile Toplist module. The older
                    // ToplistInfoServer route can return an envelope without
                    // songInfoList, which made the chart page look empty.
                    "module" to "music.musicToplist.Toplist",
                    "method" to "GetDetail",
                    "param" to mapOf(
                        "topId" to topId,
                        "offset" to offset,
                        "num" to count,
                        "withTags" to true
                    )
                )
            )
        )
        makePostRequest("https://u.y.qq.com/cgi-bin/musicu.fcg", payload)
    }

    suspend fun getLyricPayload(
        songId: Long,
        songName: String,
        singerName: String,
        albumName: String,
        durationSeconds: Int,
        includeQrc: Boolean = true
    ): JSONObject = withContext(Dispatchers.IO) {
        val payload = JSONObject(
            mapOf(
                "comm" to mapOf(
                    "ct" to 11,
                    "cv" to "1003006",
                    "v" to "1003006",
                    "os_ver" to "15",
                    "phonetype" to "24122RKC7C",
                    "tmeAppID" to "qqmusiclight",
                    "nettype" to "NETWORK_WIFI",
                    "udid" to "0"
                ),
                "request" to mapOf(
                    "method" to "GetPlayLyricInfo",
                    "module" to "music.musichallSong.PlayLyricInfo",
                    "param" to mapOf(
                        "albumName" to base64(albumName),
                        "crypt" to 1,
                        "ct" to 19,
                        "cv" to 2111,
                        "interval" to durationSeconds,
                        "lrc_t" to 0,
                        "qrc" to if (includeQrc) 1 else 0,
                        "qrc_t" to 0,
                        "roma" to 1,
                        "roma_t" to 0,
                        "singerName" to base64(singerName),
                        "songID" to songId,
                        "songName" to base64(songName),
                        "trans" to 1,
                        "trans_t" to 0,
                        "type" to 0
                    )
                )
            )
        )

        val response = JSONObject(makePostRequest("https://u.y.qq.com/cgi-bin/musicu.fcg", payload))
        response.optJSONObject("request")?.optJSONObject("data") ?: JSONObject()
    }

    // ─── Helper Methods ────────────────────────────────────────────────

    private fun extractUin(): String {
        val uinStr = persistedCookies["uin"] 
            ?: persistedCookies["p_uin"] 
            ?: persistedCookies["luin"] 
            ?: persistedCookies["wxuin"] 
            ?: "0"
        return uinStr.replace(Regex("[^0-9]"), "").ifBlank { "0" }
    }

    private fun extractKeyst(): String {
        return persistedCookies["qm_keyst"] ?: ""
    }

    private fun base64(value: String): String =
        android.util.Base64.encodeToString(value.toByteArray(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP)

    private fun buildSearchId(): String {
        val part1 = (Math.random() * 20).toLong() * 18014398509481984L
        val part2 = (Math.random() * 4194304).toLong() * 4294967296L
        val part3 = System.currentTimeMillis() % 86400000L
        return (part1 + part2 + part3).toString()
    }

    private fun buildArtistName(singers: JSONArray?): String {
        if (singers == null) return ""
        return buildList {
            for (index in 0 until singers.length()) {
                val singer = singers.optJSONObject(index) ?: continue
                val name = singer.optString("name").ifBlank { singer.optString("title") }
                if (name.isNotBlank()) add(cleanHighlightTags(name))
            }
        }.joinToString(" / ")
    }

    /**
     * Make a GET request and handle potential Zlib-compressed responses.
     * QQ Music FCG endpoints often return application/octet-stream with
     * a 5-byte length prefix followed by Zlib-compressed JSON.
     */
    private fun makeGetRequest(url: String): String {
        val requestBuilder = Request.Builder()
            .url(url)
            .header("Referer", "https://y.qq.com/")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

        buildPersistedCookieHeader()?.let { requestBuilder.header("Cookie", it) }

        return okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
            val bodyBytes = response.body.bytes()
            if (!response.isSuccessful) {
                Timber.w("QqMusicApiService GET request failed: HTTP ${response.code}")
            }
            decompressIfNeeded(bodyBytes)
        }
    }

    private fun makePostRequest(url: String, payload: JSONObject): String {
        logCookieKeyDiagnostics("makePostRequest")

        val requestBuilder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Referer", "https://y.qq.com/")
            .header("Origin", "https://y.qq.com")
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )

        buildPersistedCookieHeader()?.let { requestBuilder.header("Cookie", it) }

        val request = requestBuilder
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        return okHttpClient.newCall(request).execute().use { response ->
            val bodyBytes = response.body.bytes()
            if (!response.isSuccessful) {
                Timber.w("QqMusicApiService POST request failed: HTTP ${response.code}")
            }
            decompressIfNeeded(bodyBytes)
        }
    }

    /**
     * Decompress Zlib-compressed response if needed.
     * QQ Music FCG endpoints return data with a 5-byte prefix + Zlib payload.
     */
    private fun decompressIfNeeded(data: ByteArray): String {
        if (data.isEmpty()) return ""

        // Try direct UTF-8 first (in case it's already plain JSON)
        val directStr = String(data, StandardCharsets.UTF_8).trim()
        if (directStr.startsWith("{") || directStr.startsWith("[")) {
            return directStr
        }

        // QQ Music uses a 5-byte prefix (usually \x00\x00\x00\x00\x00) followed by Zlib data
        // The Zlib data starts with 0x78 (e.g., 0x78 0x01, 0x78 0x9C, 0x78 0xDA)
        try {
            // Find the Zlib header (0x78)
            var offset = 0
            for (i in 0 until minOf(data.size, 10)) {
                if (data[i] == 0x78.toByte() && i + 1 < data.size) {
                    offset = i
                    break
                }
            }
            val zlibData = if (offset > 0) data.copyOfRange(offset, data.size) else data
            val inflater = InflaterInputStream(ByteArrayInputStream(zlibData))
            val output = ByteArrayOutputStream()
            inflater.copyTo(output)
            val result = output.toString(StandardCharsets.UTF_8.name())
            Timber.d("QqMusicApiService: Decompressed ${data.size} bytes -> ${result.length} chars")
            return result
        } catch (e: Exception) {
            Timber.w(e, "QqMusicApiService: Zlib decompression failed, returning raw string")
            return directStr
        }
    }

    private fun buildPersistedCookieHeader(): String? {
        if (persistedCookies.isEmpty()) return null
        return persistedCookies.entries.joinToString("; ") { (k, v) -> "$k=$v" }
    }

    private fun seedCookieJar(host: String) {
        val list = cookieStore.getOrPut(host) { mutableListOf() }
        persistedCookies.forEach { (name, value) ->
            val c = Cookie.Builder()
                .name(name)
                .value(value)
                .domain(host)
                .path("/")
                .build()
            list.removeAll { it.name == name }
            list.add(c)
        }
    }

    private fun logCookieKeyDiagnostics(stage: String) {
        if (persistedCookies.isEmpty()) {
            Timber.d("QqMusicApiService[$stage]: persisted cookies are empty")
            return
        }

        val keys = persistedCookies.keys.sorted()
        val required = listOf("uin", "qm_keyst", "euin", "psrf_qqaccess_token")
        val missing = required.filter { persistedCookies[it].isNullOrBlank() }
        Timber.d("QqMusicApiService[$stage]: cookie keys=%s", keys.joinToString(","))
        Timber.d("QqMusicApiService[$stage]: missing required cookie keys=%s", missing.joinToString(","))
    }
}
