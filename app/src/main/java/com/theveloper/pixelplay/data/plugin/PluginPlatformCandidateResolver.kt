package com.theveloper.pixelplay.data.plugin

import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.network.netease.NeteaseApiService
import com.theveloper.pixelplay.data.network.qqmusic.QqMusicApiService
import java.net.URLEncoder
import java.util.LinkedHashMap
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber

@Singleton
class PluginPlatformCandidateResolver @Inject constructor(
    private val neteaseApiService: NeteaseApiService,
    private val qqMusicApiService: QqMusicApiService,
    private val okHttpClient: OkHttpClient
) {
    private val cache = object : LinkedHashMap<String, List<PluginPlatformCandidate>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<PluginPlatformCandidate>>?): Boolean =
            size > MAX_CACHE_ENTRIES
    }

    suspend fun candidatesFor(song: Song, source: PluginSource): List<PluginPlatformCandidate> {
        val cacheKey = cacheKey(song, source)
        synchronized(cache) {
            cache[cacheKey]?.let { return it }
        }

        val candidates = runCatching {
            when (source) {
                PluginSource.NETEASE -> searchNetease(song)
                PluginSource.QQ_MUSIC -> searchQq(song)
                PluginSource.KUGOU -> searchKugou(song)
                PluginSource.KUWO -> searchKuwo(song)
            }
        }.onFailure { throwable ->
            Timber.w(throwable, "Plugin candidate search failed source=%s title=%s", source.lxSource, song.title)
        }.getOrDefault(emptyList())

        val unique = uniqueCandidates(candidates)
        synchronized(cache) {
            cache[cacheKey] = unique
        }
        if (unique.isEmpty()) {
            Timber.d("Plugin candidate search no match source=%s title=%s artist=%s", source.lxSource, song.title, song.displayArtist)
        } else {
            Timber.d(
                "Plugin candidate search hit source=%s title=%s count=%d first=%s score=%.3f",
                source.lxSource,
                song.title,
                unique.size,
                unique.first().id,
                unique.first().matchScore
            )
        }
        return unique
    }

    private fun searchKeywords(song: Song): List<String> =
        linkedSetOf(
            "${song.title} ${song.displayArtist}".trim(),
            song.title.trim()
        ).filter { it.isNotBlank() }

    private fun matchCandidate(
        song: Song,
        source: PluginSource,
        id: String,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        songMid: String? = null,
        hash: String? = null
    ): PluginPlatformCandidate? {
        if (hasExcludedVersionMismatch(song.title, title)) {
            Timber.d("Plugin candidate rejected source=%s id=%s reason=version title=%s", source.lxSource, id, title)
            return null
        }
        val normalizedTitle = normalizeName(title)
        val normalizedOriginal = normalizeName(song.title)
        if (normalizedTitle.isBlank() || normalizedOriginal.isBlank() || normalizedTitle != normalizedOriginal) {
            Timber.d("Plugin candidate rejected source=%s id=%s reason=title title=%s", source.lxSource, id, title)
            return null
        }

        val artistScore = artistMatchScore(artist, song.displayArtist)
        if (artistScore < MIN_ARTIST_SCORE) {
            Timber.d(
                "Plugin candidate rejected source=%s id=%s reason=artist artist=%s score=%.3f",
                source.lxSource,
                id,
                artist,
                artistScore
            )
            return null
        }

        val durationScore = durationMatchScore(durationMs, song.duration)
        if (durationScore <= 0.0) {
            Timber.d(
                "Plugin candidate rejected source=%s id=%s reason=duration candidateMs=%d expectedMs=%d",
                source.lxSource,
                id,
                durationMs,
                song.duration
            )
            return null
        }

        return PluginPlatformCandidate(
            source = source,
            id = id,
            songMid = songMid,
            hash = hash,
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            matchScore = artistScore * 0.8 + durationScore * 0.2,
            matchType = "fuzzy"
        )
    }

    private fun searchNetease(song: Song): List<PluginPlatformCandidate> {
        val candidates = mutableListOf<PluginPlatformCandidate>()
        for (keyword in searchKeywords(song)) {
            val root = JSONObject(neteaseApiService.searchSongs(keyword, limit = 10, offset = 0))
            val songs = root.optJSONObject("result")?.optJSONArray("songs") ?: continue
            for (index in 0 until songs.length()) {
                val item = songs.optJSONObject(index) ?: continue
                val id = normalizeCandidateId(item.opt("id")) ?: continue
                val title = item.optString("name")
                val artist = artistText(item.optJSONArray("ar") ?: item.optJSONArray("artists"))
                val album = item.optJSONObject("al")?.optString("name")
                    ?: item.optJSONObject("album")?.optString("name")
                    ?: ""
                val duration = item.optLong("dt", item.optLong("duration", song.duration))
                matchCandidate(song, PluginSource.NETEASE, id, title, artist, album, duration)?.let(candidates::add)
            }
        }
        return candidates
    }

    private suspend fun searchQq(song: Song): List<PluginPlatformCandidate> {
        val candidates = mutableListOf<PluginPlatformCandidate>()
        for (keyword in searchKeywords(song)) {
            for (item in qqMusicApiService.searchSongs(keyword, page = 1, pageSize = 10)) {
                val id = normalizeCandidateId(item.qqSongId) ?: item.mid.takeIf { it.isNotBlank() } ?: continue
                matchCandidate(
                    song = song,
                    source = PluginSource.QQ_MUSIC,
                    id = id,
                    title = item.title,
                    artist = item.artist,
                    album = item.album,
                    durationMs = item.durationMs.takeIf { it > 0L } ?: song.duration,
                    songMid = item.mid.ifBlank { id }
                )?.let(candidates::add)
            }
        }
        return candidates
    }

    private fun searchKugou(song: Song): List<PluginPlatformCandidate> {
        val candidates = mutableListOf<PluginPlatformCandidate>()
        for (keyword in searchKeywords(song)) {
            val encodedKeyword = URLEncoder.encode(keyword, "UTF-8")
            val url = "http://mobilecdn.kugou.com/api/v3/search/song?format=json&keyword=$encodedKeyword&page=1&pagesize=10&showtype=1"
            val root = JSONObject(executeTextRequest(url))
            val list = root.optJSONObject("data")?.optJSONArray("info") ?: continue
            for (index in 0 until list.length()) {
                val item = list.optJSONObject(index) ?: continue
                val hash = normalizeCandidateId(item.opt("hash") ?: item.opt("HASH") ?: item.opt("filehash")) ?: continue
                val title = item.optString("songname", item.optString("filename"))
                val artist = item.optString("singername")
                val album = item.optString("album_name")
                val duration = item.optLong("duration", 0L).let { if (it > 0L) it * 1000L else song.duration }
                matchCandidate(
                    song = song,
                    source = PluginSource.KUGOU,
                    id = hash,
                    title = title,
                    artist = artist,
                    album = album,
                    durationMs = duration,
                    hash = hash
                )?.let(candidates::add)
            }
        }
        return candidates
    }

    private fun searchKuwo(song: Song): List<PluginPlatformCandidate> {
        val candidates = mutableListOf<PluginPlatformCandidate>()
        for (keyword in searchKeywords(song)) {
            val encodedKeyword = URLEncoder.encode(keyword, "UTF-8")
            val url = "http://search.kuwo.cn/r.s?client=kt&all=$encodedKeyword&pn=0&rn=10&uid=794762570&ver=kwplayer_ar_9.2.2.1&vipver=1&show_copyright_off=1&newver=1&ft=music&cluster=0&strategy=2012&encoding=utf8&rformat=json&vermerge=1&mobi=1&issubtitle=1"
            val root = JSONObject(executeTextRequest(url))
            val list = root.optJSONArray("abslist") ?: continue
            for (index in 0 until list.length()) {
                val item = list.optJSONObject(index) ?: continue
                val id = normalizeCandidateId(item.opt("MUSICRID")) ?: continue
                val title = item.optString("SONGNAME")
                val artist = item.optString("ARTIST")
                val album = item.optString("ALBUM")
                val duration = item.optLong("DURATION", 0L).let { if (it > 0L) it * 1000L else song.duration }
                matchCandidate(
                    song = song,
                    source = PluginSource.KUWO,
                    id = id,
                    title = title,
                    artist = artist,
                    album = album,
                    durationMs = duration
                )?.let(candidates::add)
            }
        }
        return candidates
    }

    private fun executeTextRequest(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json,text/plain,*/*")
            .header("Referer", "https://www.kuwo.cn/")
            .build()
        return okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Timber.w("Plugin candidate request failed url=%s code=%d", url.substringBefore('?'), response.code)
            }
            response.body.string()
        }
    }

    private fun uniqueCandidates(candidates: List<PluginPlatformCandidate>): List<PluginPlatformCandidate> {
        val best = linkedMapOf<String, PluginPlatformCandidate>()
        candidates.forEach { candidate ->
            val key = "${candidate.source.lxSource}|${candidate.id}|${candidate.songMid.orEmpty()}|${candidate.hash.orEmpty()}"
            val current = best[key]
            if (current == null || candidate.matchScore > current.matchScore) {
                best[key] = candidate
            }
        }
        return best.values
            .sortedByDescending { it.matchScore }
            .take(MAX_PLATFORM_CANDIDATES)
    }

    private fun cacheKey(song: Song, source: PluginSource): String =
        listOf(
            "v1",
            source.lxSource,
            song.contentUriString,
            song.title,
            song.displayArtist,
            song.album,
            (song.duration / 1000L).toString()
        ).joinToString("|")

    private fun normalizeCandidateId(value: Any?): String? {
        val raw = value?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return raw.removePrefix("MUSIC_").takeIf { it.isNotBlank() }
    }

    private fun artistText(array: org.json.JSONArray?): String {
        if (array == null) return ""
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index)
                val name = item?.optString("name").orEmpty()
                if (name.isNotBlank()) add(name)
            }
        }.joinToString(" / ")
    }

    private fun normalizeName(name: String): String =
        name.lowercase(Locale.ROOT)
            .replace(Regex("""[（(][^）)]*[）)]"""), "")
            .trim()

    private fun normalizeArtist(artist: String): String =
        artist.lowercase(Locale.ROOT)
            .replace(Regex("""[&/、，,;；]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun normalizedArtistParts(artist: String): List<String> =
        artist.split(Regex("""\s*(?:/|、|,|，|&|;|；|\+)\s*|\s+(?:feat\.?|ft\.?|featuring|with|vs\.?|versus|x)\s+""", RegexOption.IGNORE_CASE))
            .map(::normalizeArtist)
            .filter { it.isNotBlank() }
            .distinct()

    private fun artistMatchScore(resultArtist: String, originalArtist: String): Double {
        val normalizedResult = normalizeArtist(resultArtist)
        val normalizedOriginal = normalizeArtist(originalArtist)
        if (normalizedResult.isBlank() || normalizedOriginal.isBlank()) return 0.0
        if (normalizedResult == normalizedOriginal) return 1.0

        val resultParts = normalizedArtistParts(resultArtist)
        val originalParts = normalizedArtistParts(originalArtist)
        if (resultParts.isEmpty() || originalParts.isEmpty()) return 0.0

        val originalInResult = originalParts.all { it in resultParts }
        val resultInOriginal = resultParts.all { it in originalParts }
        return when {
            originalInResult && resultInOriginal -> 0.95
            originalInResult -> 0.82
            resultInOriginal -> 0.76
            else -> 0.0
        }
    }

    private fun durationMatchScore(resultDuration: Long, originalDuration: Long): Double {
        if (resultDuration <= 0L || originalDuration <= 0L) return 0.75
        val maxDiff = maxOf(MAX_DURATION_DIFF_MS, (originalDuration * MAX_DURATION_DIFF_RATIO).toLong())
        val diff = abs(resultDuration - originalDuration)
        if (diff > maxDiff) return 0.0
        return 1.0 - (diff.toDouble() / maxDiff.toDouble()) * 0.3
    }

    private fun hasExcludedVersionMismatch(originalName: String, candidateName: String): Boolean {
        if (hasExcludedVersionKeyword(originalName)) return false
        return hasExcludedVersionKeyword(candidateName)
    }

    private fun hasExcludedVersionKeyword(name: String): Boolean {
        val normalized = name.lowercase(Locale.ROOT)
        return EXCLUDED_VERSION_KEYWORDS.any { normalized.contains(it) }
    }

    private companion object {
        const val MAX_CACHE_ENTRIES = 128
        const val MAX_PLATFORM_CANDIDATES = 8
        const val MAX_DURATION_DIFF_MS = 15_000L
        const val MAX_DURATION_DIFF_RATIO = 0.1
        const val MIN_ARTIST_SCORE = 0.76

        val EXCLUDED_VERSION_KEYWORDS = listOf(
            "伴奏",
            "纯音乐",
            "instrumental",
            "karaoke",
            "off vocal",
            "minus one",
            "backing track",
            "dj版",
            "dj version",
            "remix",
            "混音版",
            "伴唱"
        )
    }
}
