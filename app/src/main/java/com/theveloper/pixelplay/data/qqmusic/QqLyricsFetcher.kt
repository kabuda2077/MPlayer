package com.theveloper.pixelplay.data.qqmusic

import com.theveloper.pixelplay.data.model.Lyrics
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.model.SyncedLine
import com.theveloper.pixelplay.data.model.SyncedWord
import com.theveloper.pixelplay.data.network.qqmusic.QqMusicApiService
import com.theveloper.pixelplay.data.repository.LyricsSearchRecord
import com.theveloper.pixelplay.data.repository.LyricsSearchResult
import com.theveloper.pixelplay.utils.LyricsUtils
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import org.json.JSONObject
import timber.log.Timber

@Singleton
class QqLyricsFetcher @Inject constructor(
    private val qqMusicApiService: QqMusicApiService
) {

    data class CandidateRejection(
        val query: String,
        val title: String,
        val artist: String,
        val candidateId: String,
        val candidateTitle: String,
        val candidateArtist: String,
        val expectedDurationMs: Long?,
        val candidateDurationMs: Long,
        val durationDiffMs: Long?,
        val reason: String
    )

    data class FetchMetadata(
        val sourceTag: String,
        val hasWordLevel: Boolean,
        val hasTranslation: Boolean,
        val hasRomanization: Boolean,
        val matchType: String,
        val durationDiffMs: Long?,
        val exactPlatformMatch: Boolean
    )

    data class FetchResult(
        val lyrics: Lyrics,
        val rawLyrics: String,
        val metadata: FetchMetadata
    )

    suspend fun fetch(song: Song): FetchResult? {
        val candidate = findBestCandidate(song) ?: return null
        return fetchCandidate(candidate, expectedDurationMs = song.duration)
    }

    suspend fun search(song: Song): List<LyricsSearchResult> {
        return searchByQuery(song.title, song.displayArtist, expectedDurationMs = song.duration)
    }

    suspend fun searchByQuery(title: String, artist: String?, expectedDurationMs: Long? = null): List<LyricsSearchResult> {
        val query = buildQuery(title, artist)
        if (query.isBlank()) return emptyList()

        val rejections = mutableListOf<CandidateRejection>()
        val matchedCandidates = qqMusicApiService.searchSongs(query)
            .filter { candidate ->
                val accepted = expectedDurationMs == null ||
                    candidate.durationMs <= 0L ||
                    abs(candidate.durationMs - expectedDurationMs) <= MAX_DURATION_DIFF_MS
                if (!accepted) {
                    rejections += CandidateRejection(
                        query = query,
                        title = title,
                        artist = artist.orEmpty(),
                        candidateId = candidate.id,
                        candidateTitle = candidate.title,
                        candidateArtist = candidate.artist,
                        expectedDurationMs = expectedDurationMs,
                        candidateDurationMs = candidate.durationMs,
                        durationDiffMs = abs(candidate.durationMs - expectedDurationMs),
                        reason = "duration_mismatch"
                    )
                }
                accepted
            }

        rejections.forEach(::logCandidateRejection)

        val results = buildList {
            for (candidate in matchedCandidates) {
                val result = fetchCandidate(candidate, expectedDurationMs = expectedDurationMs) ?: continue
                add(
                    LyricsSearchResult(
                        record = LyricsSearchRecord(
                            id = candidate.id,
                            name = candidate.title,
                            artistName = candidate.artist,
                            albumName = candidate.album,
                            durationMs = candidate.durationMs.takeIf { it > 0L } ?: expectedDurationMs,
                            sourceName = SOURCE_NAME,
                            sourceUrl = SOURCE_URL,
                            syncedLyrics = result.lyrics.synced?.takeIf { it.isNotEmpty() }?.let { result.rawLyrics }
                        ),
                        lyrics = result.lyrics,
                        rawLyrics = result.rawLyrics
                    )
                )
            }
        }

        return results
            .map {
                LyricsSearchResult(
                    record = it.record,
                    lyrics = it.lyrics,
                    rawLyrics = it.rawLyrics
                )
            }
            .distinctBy { it.rawLyrics }
    }

    private suspend fun findBestCandidate(song: Song): QqMusicApiService.SearchSongResult? {
        val query = buildQuery(song.title, song.displayArtist)
        if (query.isBlank()) return null

        val rejections = mutableListOf<CandidateRejection>()
        val candidates = qqMusicApiService.searchSongs(query)
        val accepted = candidates.firstOrNull { result ->
            result.durationMs <= 0L || abs(result.durationMs - song.duration) <= MAX_DURATION_DIFF_MS
        }
        candidates.forEach { result ->
            if (accepted != null && result.id == accepted.id) return@forEach
            if (result.durationMs > 0L) {
                val diff = abs(result.durationMs - song.duration)
                if (diff > MAX_DURATION_DIFF_MS) {
                    rejections += CandidateRejection(
                        query = query,
                        title = song.title,
                        artist = song.displayArtist,
                        candidateId = result.id,
                        candidateTitle = result.title,
                        candidateArtist = result.artist,
                        expectedDurationMs = song.duration,
                        candidateDurationMs = result.durationMs,
                        durationDiffMs = diff,
                        reason = "duration_mismatch"
                    )
                }
            }
        }
        rejections.forEach(::logCandidateRejection)
        return accepted
    }

    private suspend fun fetchCandidate(
        candidate: QqMusicApiService.SearchSongResult,
        expectedDurationMs: Long? = null
    ): FetchResult? {
        val songId = candidate.id.toLongOrNull() ?: return null
        val durationSeconds = (candidate.durationMs / 1000L).toInt()
        val durationDiffMs = expectedDurationMs?.takeIf { candidate.durationMs > 0L }?.let {
            abs(candidate.durationMs - it)
        }
        val payload = qqMusicApiService.getLyricPayload(
            songId = songId,
            songName = candidate.title,
            singerName = candidate.artist,
            albumName = candidate.album,
            durationSeconds = durationSeconds,
            includeQrc = true
        )

        val qrc = payload.optString("lyric").takeIf { it.isNotBlank() }?.let { runCatching { QqQrcDecoder.decrypt(it) }.getOrNull() }
        val trans = payload.optString("trans").takeIf { it.isNotBlank() }?.let { runCatching { QqQrcDecoder.decrypt(it) }.getOrNull() }
        val roma = payload.optString("roma").takeIf { it.isNotBlank() }?.let { runCatching { QqQrcDecoder.decrypt(it) }.getOrNull() }

        if (!qrc.isNullOrBlank()) {
            val lyrics = parseQrcLyrics(qrc, trans, roma)
            if (lyrics != null) {
                return FetchResult(
                    lyrics = lyrics,
                    rawLyrics = encodeStructuredPayload(
                        qrc = qrc,
                        trans = trans,
                        roma = roma,
                        lrc = null
                    ),
                    metadata = FetchMetadata(
                        sourceTag = "qq_qrc",
                        hasWordLevel = lyrics.synced.orEmpty().any { !it.words.isNullOrEmpty() },
                        hasTranslation = lyrics.synced.orEmpty().any { !it.translation.isNullOrBlank() },
                        hasRomanization = lyrics.synced.orEmpty().any { !it.romanization.isNullOrBlank() },
                        matchType = "fuzzy",
                        durationDiffMs = durationDiffMs,
                        exactPlatformMatch = false
                    )
                )
            }
        }

        val lrc = when {
            payload.optInt("qrc_t", -1) == 0 && !payload.optString("lyric").isNullOrBlank() ->
                runCatching { QqQrcDecoder.decrypt(payload.optString("lyric")) }.getOrNull()
            else -> {
                val lrcPayload = qqMusicApiService.getLyricPayload(
                    songId = songId,
                    songName = candidate.title,
                    singerName = candidate.artist,
                    albumName = candidate.album,
                    durationSeconds = durationSeconds,
                    includeQrc = false
                )
                lrcPayload.optString("lyric").takeIf { it.isNotBlank() }?.let { runCatching { QqQrcDecoder.decrypt(it) }.getOrNull() }
            }
        }

        val parsed = lrc?.let { LyricsUtils.parseLyrics(it).copy(areFromRemote = true) }
        if (parsed != null && hasValidLyrics(parsed)) {
            val mergedLyrics = mergeLrcExtras(parsed, trans, roma)
            return FetchResult(
                lyrics = mergedLyrics,
                rawLyrics = encodeStructuredPayload(
                    qrc = null,
                    trans = trans,
                    roma = roma,
                    lrc = lrc
                ),
                metadata = FetchMetadata(
                    sourceTag = "qq_lrc",
                    hasWordLevel = mergedLyrics.synced.orEmpty().any { !it.words.isNullOrEmpty() },
                    hasTranslation = mergedLyrics.synced.orEmpty().any { !it.translation.isNullOrBlank() },
                    hasRomanization = mergedLyrics.synced.orEmpty().any { !it.romanization.isNullOrBlank() },
                    matchType = "fuzzy",
                    durationDiffMs = durationDiffMs,
                    exactPlatformMatch = false
                )
            )
        }

        return null
    }

    fun parsePersistedRaw(rawLyrics: String): Lyrics? {
        if (!rawLyrics.startsWith(STRUCTURED_PREFIX)) {
            val parsed = LyricsUtils.parseLyrics(rawLyrics).copy(areFromRemote = true)
            return parsed.takeIf(::hasValidLyrics)
        }

        val payload = runCatching {
            JSONObject(rawLyrics.removePrefix(STRUCTURED_PREFIX))
        }.getOrNull() ?: return null

        val qrc = payload.optString("qrc", "").takeIf { it.isNotBlank() }
        val trans = payload.optString("trans", "").takeIf { it.isNotBlank() }
        val roma = payload.optString("roma", "").takeIf { it.isNotBlank() }
        val lrc = payload.optString("lrc", "").takeIf { it.isNotBlank() }

        if (!qrc.isNullOrBlank()) {
            return parseQrcLyrics(qrc, trans, roma)
        }

        val parsed = lrc?.let { LyricsUtils.parseLyrics(it).copy(areFromRemote = true) } ?: return null
        return mergeLrcExtras(parsed, trans, roma).takeIf(::hasValidLyrics)
    }

    private fun parseQrcLyrics(qrc: String, trans: String?, roma: String?): Lyrics? {
        val content = extractLyricContent(qrc)
        val baseLines = content.lines()
            .mapNotNull(::parseQrcLine)
            .sortedBy { it.time }
        if (baseLines.isEmpty()) return null

        val merged = LyricsUtils.mergeSupplementalLines(
            baseLines = baseLines,
            translationLines = parseTranslationLines(trans),
            romanizationLines = parseRomanizationLines(roma)
        )
        return Lyrics(
            synced = merged,
            plain = LyricsUtils.rebuildPlainFromSynced(merged),
            areFromRemote = true
        )
    }

    private fun parseTranslationLines(translationLrc: String?): List<SyncedLine> {
        if (translationLrc.isNullOrBlank()) return emptyList()
        return LyricsUtils.parseSameTimestampBilingualLrc(translationLrc).synced.orEmpty()
            .filterNot { line ->
                line.line.contains("//") || line.line.contains("作品的著作权")
            }
    }

    private fun parseRomanizationLines(romaQrc: String?): List<SyncedLine> {
        if (romaQrc.isNullOrBlank()) return emptyList()
        return extractLyricContent(romaQrc).lines()
            .mapNotNull(::parseQrcLine)
            .map { line ->
                line.copy(
                    words = listOf(
                        SyncedWord(
                            time = line.time,
                            word = line.line,
                            startsNewWord = true
                        )
                    )
                )
            }
    }

    private fun mergeLrcExtras(base: Lyrics, trans: String?, roma: String?): Lyrics {
        val lines = base.synced.orEmpty()
        if (lines.isNotEmpty()) {
            val merged = LyricsUtils.mergeSupplementalLines(
                baseLines = lines,
                translationLines = parseTranslationLines(trans),
                romanizationLines = parseRomanizationLines(roma)
            )
            if (merged.isNotEmpty()) {
                return base.copy(
                    synced = merged,
                    plain = LyricsUtils.rebuildPlainFromSynced(merged),
                    areFromRemote = true
                )
            }
        }
        return base.copy(areFromRemote = true)
    }

    private fun parseQrcLine(rawLine: String): SyncedLine? {
        val line = rawLine.trim()
        if (line.isEmpty()) return null
        if (META_TAG_REGEX.containsMatchIn(line)) return null

        val match = QRC_LINE_REGEX.matchEntire(line) ?: return null
        val lineStart = match.groupValues[1].toIntOrNull() ?: return null
        val lineContent = match.groupValues[3]
        val words = mutableListOf<SyncedWord>()
        val plain = StringBuilder()
        var previousEndsWithSpace = true

        QRC_WORD_REGEX.findAll(lineContent).forEach { wordMatch ->
            val rawWord = wordMatch.groupValues[1]
            val wordStart = wordMatch.groupValues[2].toIntOrNull() ?: 0
            val wordText = rawWord
            plain.append(wordText)
            val trimmed = wordText.trim()
            if (trimmed.isNotEmpty()) {
                val startsNew = previousEndsWithSpace || wordText.firstOrNull()?.isWhitespace() == true || words.isEmpty()
                words += SyncedWord(
                    time = lineStart + wordStart,
                    word = trimmed,
                    startsNewWord = startsNew
                )
            }
            previousEndsWithSpace = wordText.lastOrNull()?.isWhitespace() == true
        }

        val plainText = plain.toString().trim()
        if (plainText.isEmpty() && words.isEmpty()) return null
        return SyncedLine(
            time = lineStart,
            line = plainText,
            words = words.takeIf { it.isNotEmpty() }
        )
    }

    private fun logCandidateRejection(rejection: CandidateRejection) {
        Timber.d(
            "Lyrics[QQ][Reject] reason=%s query=%s candidate=%s/%s id=%s expectedDurationMs=%s candidateDurationMs=%s durationDiffMs=%s",
            rejection.reason,
            rejection.query,
            rejection.candidateArtist,
            rejection.candidateTitle,
            rejection.candidateId,
            rejection.expectedDurationMs,
            rejection.candidateDurationMs,
            rejection.durationDiffMs
        )
    }

    private fun extractLyricContent(rawContent: String): String {
        val greedy = Regex("""LyricContent\s*=\s*"([\s\S]*)"\s*/?>""").find(rawContent)
        if (greedy != null) {
            val content = greedy.groupValues[1]
            if (!Regex("""\s+\w+\s*=\s*\"""").containsMatchIn(content)) {
                return decodeXml(content)
            }
        }
        val standard = Regex("""LyricContent\s*=\s*"([^"]*)"""").find(rawContent)
        return decodeXml(standard?.groupValues?.getOrNull(1) ?: rawContent)
    }

    private fun decodeXml(value: String): String =
        value
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")

    private fun buildQuery(title: String, artist: String?): String =
        listOfNotNull(
            title.trim().takeIf { it.isNotBlank() },
            artist?.trim()?.takeIf { it.isNotBlank() }
        ).joinToString("-")

    private fun encodeStructuredPayload(qrc: String?, trans: String?, roma: String?, lrc: String?): String =
        STRUCTURED_PREFIX + JSONObject().apply {
            qrc?.let { put("qrc", it) }
            trans?.let { put("trans", it) }
            roma?.let { put("roma", it) }
            lrc?.let { put("lrc", it) }
        }.toString()

    private fun hasValidLyrics(lyrics: Lyrics): Boolean =
        !lyrics.synced.isNullOrEmpty() || !lyrics.plain.isNullOrEmpty()

    companion object {
        private const val MAX_DURATION_DIFF_MS = 5_000L
        private const val SOURCE_NAME = "QQ Music"
        private const val SOURCE_URL = "https://y.qq.com/"
        private const val STRUCTURED_PREFIX = "qqlyrics://"
        private val META_TAG_REGEX = Regex("""^\[[a-z]+:.*]$""", RegexOption.IGNORE_CASE)
        private val QRC_LINE_REGEX = Regex("""^\[(\d+),(\d+)](.*)$""")
        private val QRC_WORD_REGEX = Regex("""(.*?)\((\d+),(\d+)\)""")
    }
}
