package com.theveloper.pixelplay.data.search

import com.theveloper.pixelplay.data.model.ArtistRef
import com.theveloper.pixelplay.data.model.OnlineSearchPlatform
import com.theveloper.pixelplay.data.model.OnlineSearchResultItem
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.netease.NeteaseRepository
import com.theveloper.pixelplay.data.network.qqmusic.QqMusicApiService
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
            }.map { song -> OnlineSearchResultItem(song = song, platform = platform) }
        }.onFailure { throwable ->
            Timber.w(
                throwable,
                "Online song search failed platform=%s query=%s",
                platform.source.lxSource,
                query
            )
        }
    }

    private suspend fun searchNetease(query: String, limit: Int): List<Song> =
        neteaseRepository.searchOnline(query, limit).getOrThrow()

    private suspend fun searchQq(query: String, limit: Int): List<Song> =
        qqMusicApiService.searchSongs(query, page = 1, pageSize = limit)
            .mapNotNull { item ->
                val songMid = item.mid.ifBlank { item.id }.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                onlineSong(
                    id = "qqmusic_$songMid",
                    title = item.title,
                    artist = item.artist,
                    album = item.album,
                    contentUri = "qqmusic://$songMid",
                    durationMs = item.durationMs,
                    albumArtUri = normalizeArtworkUrl(item.albumArtUrl),
                    mimeType = "audio/mpeg",
                    qqMusicMid = songMid
                )
            }

    private fun searchKugou(query: String, limit: Int): List<Song> {
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
                    onlineSong(
                        id = "kugou_$hash",
                        title = title,
                        artist = artist,
                        album = album,
                        contentUri = "kugou://$hash",
                        durationMs = durationMs,
                        albumArtUri = artwork,
                        mimeType = "audio/mpeg"
                    )
                )
            }
        }
    }

    private fun searchKuwo(query: String, limit: Int): List<Song> {
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
                    onlineSong(
                        id = "kuwo_$id",
                        title = title,
                        artist = artist,
                        album = album,
                        contentUri = "kuwo://$id",
                        durationMs = durationMs,
                        albumArtUri = normalizeArtworkUrl(artwork),
                        mimeType = "audio/mpeg"
                    )
                )
            }
        }
    }

    private fun onlineSong(
        id: String,
        title: String,
        artist: String,
        album: String,
        contentUri: String,
        durationMs: Long,
        albumArtUri: String? = null,
        mimeType: String?,
        qqMusicMid: String? = null
    ): Song {
        val artistName = artist.ifBlank { UNKNOWN_ARTIST }
        return Song(
            id = id,
            title = title.ifBlank { UNKNOWN_TITLE },
            artist = artistName,
            artistId = stableNegativeId(artistName),
            artists = listOf(ArtistRef(id = stableNegativeId(artistName), name = artistName, isPrimary = true)),
            album = album.ifBlank { UNKNOWN_ALBUM },
            albumId = stableNegativeId(album.ifBlank { UNKNOWN_ALBUM }),
            path = "",
            contentUriString = contentUri,
            albumArtUriString = normalizeArtworkUrl(albumArtUri),
            duration = durationMs,
            mimeType = mimeType,
            bitrate = null,
            sampleRate = null,
            isFavorite = false,
            qqMusicMid = qqMusicMid
        )
    }

    private fun executeTextRequest(url: String, referer: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json,text/plain,*/*")
            .header("Referer", referer)
            .build()
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

    private fun normalizeArtworkUrl(value: String?): String? {
        val trimmed = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return if (trimmed.startsWith("http://", ignoreCase = true)) {
            "https://${trimmed.removePrefix("http://")}"
        } else {
            trimmed
        }
    }

    private fun stableNegativeId(value: String): Long =
        -kotlin.math.abs(value.hashCode().toLong()).coerceAtLeast(1L)

    private companion object {
        const val DEFAULT_LIMIT = 30
        const val UNKNOWN_TITLE = "Unknown Title"
        const val UNKNOWN_ARTIST = "Unknown Artist"
        const val UNKNOWN_ALBUM = "Unknown Album"
    }
}
