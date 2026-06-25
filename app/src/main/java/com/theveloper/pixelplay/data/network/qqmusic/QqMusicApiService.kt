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
        val id: String,
        val mid: String,
        val title: String,
        val artist: String,
        val album: String,
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
     * Get user profile information including nickname.
     */
    suspend fun getUserProfile(): String = withContext(Dispatchers.IO) {
        val uin = extractUin()
        val gtk = getGTK()
        Timber.d("getUserProfile: uin=$uin, gtk=$gtk")
        val url = "https://c.y.qq.com/rsc/fcgi-bin/fcg_get_profile_homepage.fcg?" +
                "format=json&inCharset=utf-8&outCharset=utf-8" +
                "&cid=205360956&userid=$uin&reqfrom=1" +
                "&g_tk=$gtk"

        makeGetRequest(url)
    }

    /**
     * Get playlist detail including all songs.
     */
    suspend fun getPlaylistDetail(
        playlistId: Long,
        songBegin: Int = 0,
        songNum: Int = 1000
    ): String = withContext(Dispatchers.IO) {
        val gtk = getGTK()
        val url = "https://c.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg?" +
                "type=1&json=1&utf8=1&onlysong=0" +
                "&disstid=$playlistId&song_begin=$songBegin&song_num=$songNum" +
                "&g_tk=$gtk&format=json&inCharset=utf-8&outCharset=utf-8"

        makeGetRequest(url)
    }

    // ─── Song Data ─────────────────────────────────────────────────────

    suspend fun getSongDownloadUrl(songMid: String, songtype: Int = 0, filename: String? = null): String = withContext(Dispatchers.IO) {
        val uin = extractUin()
        val keyst = extractKeyst()
        val param = mutableMapOf<String, Any>(
            "guid" to "327783793guid",
            "songmid" to listOf(songMid),
            "songtype" to listOf(songtype),
            "uin" to uin,
            "loginflag" to 1,
            "platform" to "20",
            "xcdn" to 1
        )
        if (filename != null) {
            param["filename"] = listOf(filename)
        }
        val payload = JSONObject(
            mapOf(
                "req_0" to mapOf(
                    "module" to "music.vkey.GetEVkey",
                    "method" to "GetUrl",
                    "param" to param
                ),
                "comm" to mapOf(
                    "uin" to uin,
                    "format" to "json",
                    "ct" to 19,
                    "cv" to 1602,
                    "authst" to keyst
                )
            )
        )

        // Keep host aligned with validated reverse-engineering pipeline.
        makePostRequest("https://u6.y.qq.com/cgi-bin/musics.fcg", payload)
    }

    suspend fun searchSongs(keyword: String, page: Int = 1, pageSize: Int = 20): List<SearchSongResult> = withContext(Dispatchers.IO) {
        val searchId = buildSearchId()
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
                    "method" to "DoSearchForQQMusicLite",
                    "module" to "music.search.SearchCgiService",
                    "param" to mapOf(
                        "search_id" to searchId,
                        "remoteplace" to "search.android.keyboard",
                        "query" to keyword,
                        "search_type" to 0,
                        "num_per_page" to pageSize,
                        "page_num" to page,
                        "highlight" to 0,
                        "nqc_flag" to 0,
                        "page_id" to 1,
                        "grp" to 1
                    )
                )
            )
        )

        val response = JSONObject(makePostRequest("https://u.y.qq.com/cgi-bin/musicu.fcg", payload))
        val request = response.optJSONObject("request") ?: return@withContext emptyList()
        val data = request.optJSONObject("data") ?: return@withContext emptyList()
        val body = data.optJSONObject("body") ?: return@withContext emptyList()
        val items = body.optJSONArray("item_song") ?: JSONArray()
        buildList {
            for (index in 0 until items.length()) {
                val song = items.optJSONObject(index) ?: continue
                val singers = song.optJSONArray("singer")
                val artist = buildArtistName(singers)
                add(
                    SearchSongResult(
                        id = song.opt("id")?.toString().orEmpty(),
                        mid = song.optString("mid"),
                        title = song.optString("title"),
                        artist = artist.ifBlank { "Unknown Artist" },
                        album = song.optJSONObject("album")?.optString("name").orEmpty(),
                        durationMs = song.optLong("interval", 0L) * 1000L
                    )
                )
            }
        }
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
                val name = singer.optString("name")
                if (name.isNotBlank()) add(name)
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
