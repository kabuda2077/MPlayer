package com.theveloper.pixelplay.data.qqmusic

import com.theveloper.pixelplay.data.model.Lyrics
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.model.SyncedLine
import com.theveloper.pixelplay.data.model.SyncedWord
import com.theveloper.pixelplay.data.database.QqMusicDao
import com.theveloper.pixelplay.data.network.qqmusic.QqMusicApiService
import com.theveloper.pixelplay.data.plugin.PluginSource
import com.theveloper.pixelplay.data.repository.LyricsSearchRecord
import com.theveloper.pixelplay.data.repository.LyricsSearchResult
import com.theveloper.pixelplay.data.repository.LyricsMetadataMatcher
import com.theveloper.pixelplay.utils.LyricsUtils
import com.theveloper.pixelplay.utils.MediaSourceClassifier
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import timber.log.Timber

@Singleton
class QqLyricsFetcher @Inject constructor(
    private val qqMusicApiService: QqMusicApiService,
    private val qqMusicDao: QqMusicDao
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
        findExactQqMusicCandidate(song)?.let { exactCandidate ->
            fetchCandidate(exactCandidate, expectedDurationMs = song.duration)?.let { result ->
                return result.copy(
                    metadata = result.metadata.copy(
                        matchType = "id",
                        durationDiffMs = 0L,
                        exactPlatformMatch = true
                    )
                )
            }
        }

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
                        candidateId = candidate.qqSongId,
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
                            id = candidate.qqSongId,
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
        val accepted = candidates
            .mapNotNull { result ->
                LyricsMetadataMatcher.strictScore(
                    originalTitle = song.title,
                    originalArtist = song.displayArtist,
                    originalDurationMs = song.duration,
                    candidateTitle = result.title,
                    candidateArtist = result.artist,
                    candidateDurationMs = result.durationMs
                )?.let { score -> result to score }
            }
            .maxByOrNull { it.second }
            ?.first
        candidates.forEach { result ->
            if (accepted != null && result.qqSongId == accepted.qqSongId) return@forEach
            val diff = result.durationMs.takeIf { it > 0L }?.let { abs(it - song.duration) }
            rejections += CandidateRejection(
                query = query,
                title = song.title,
                artist = song.displayArtist,
                candidateId = result.qqSongId,
                candidateTitle = result.title,
                candidateArtist = result.artist,
                expectedDurationMs = song.duration,
                candidateDurationMs = result.durationMs,
                durationDiffMs = diff,
                reason = "strict_metadata_mismatch"
            )
        }
        rejections.forEach(::logCandidateRejection)
        return accepted
    }

    private suspend fun findExactQqMusicCandidate(song: Song): QqMusicApiService.SearchSongResult? {
        val mid = song.qqMusicMid
            ?: MediaSourceClassifier.idPart(song.contentUriString, PluginSource.QQ_MUSIC)
                ?.substringBefore("/")
                ?.substringBefore("?")
        if (mid.isNullOrBlank()) return null

        val stored = qqMusicDao.getSongWithNumericIdByMid(mid) ?: return null
        val numericSongId = stored.songId?.takeIf { it > 0L } ?: return null
        Timber.d(
            "Lyrics[QQ] exact QQ song id hit: mid=%s songId=%s for %s - %s",
            mid,
            numericSongId,
            song.displayArtist,
            song.title
        )
        return QqMusicApiService.SearchSongResult(
            qqSongId = numericSongId.toString(),
            mid = stored.songMid,
            title = stored.title.ifBlank { song.title },
            artist = stored.artist.ifBlank { song.displayArtist },
            album = stored.album.ifBlank { song.album },
            albumArtUrl = stored.albumArtUrl ?: song.albumArtUriString,
            durationMs = stored.duration.takeIf { it > 0L } ?: song.duration
        )
    }

    private suspend fun fetchCandidate(
        candidate: QqMusicApiService.SearchSongResult,
        expectedDurationMs: Long? = null
    ): FetchResult? {
        val songId = candidate.qqSongId.toLongOrNull() ?: return null
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

        val qrc = decodeLyricPayload(payload.optString("lyric"), "qrc", candidate)
        val trans = decodeLyricPayload(payload.optString("trans"), "trans", candidate)
        val roma = decodeLyricPayload(payload.optString("roma"), "roma", candidate)
        val qrcLyrics = qrc
            ?.let { parseQrcLyrics(it, trans, roma) }
            ?.takeIf(::hasValidLyrics)

        if (qrcLyrics != null) {
            return FetchResult(
                lyrics = qrcLyrics,
                rawLyrics = encodeStructuredPayload(
                    qrc = qrc,
                    trans = trans,
                    roma = roma,
                    lrc = null
                ),
                metadata = FetchMetadata(
                    sourceTag = "qq_qrc",
                    hasWordLevel = qrcLyrics.synced.orEmpty().any { !it.words.isNullOrEmpty() },
                    hasTranslation = qrcLyrics.synced.orEmpty().any { !it.translation.isNullOrBlank() },
                    hasRomanization = qrcLyrics.synced.orEmpty().any { !it.romanization.isNullOrBlank() },
                    matchType = "fuzzy",
                    durationDiffMs = durationDiffMs,
                    exactPlatformMatch = false
                )
            )
        }

        val lrc = if (payload.optInt("qrc_t", -1) == 0 && !payload.optString("lyric").isNullOrBlank()) {
            decodeLyricPayload(payload.optString("lyric"), "lrc_from_qrc", candidate)
        } else {
            val lrcPayload = qqMusicApiService.getLyricPayload(
                songId = songId,
                songName = candidate.title,
                singerName = candidate.artist,
                albumName = candidate.album,
                durationSeconds = durationSeconds,
                includeQrc = false
            )
            decodeLyricPayload(lrcPayload.optString("lyric"), "lrc", candidate)
        }

        val lrcLyrics = lrc
            ?.let { LyricsUtils.parseLyrics(it).copy(areFromRemote = true) }
            ?.takeIf(::hasValidLyrics)
            ?.let { mergeLrcExtras(it, trans, roma) }
            ?: return null

        return FetchResult(
            lyrics = lrcLyrics,
            rawLyrics = encodeStructuredPayload(qrc = null, trans = trans, roma = roma, lrc = lrc),
            metadata = FetchMetadata(
                sourceTag = "qq_lrc",
                hasWordLevel = lrcLyrics.synced.orEmpty().any { !it.words.isNullOrEmpty() },
                hasTranslation = lrcLyrics.synced.orEmpty().any { !it.translation.isNullOrBlank() },
                hasRomanization = lrcLyrics.synced.orEmpty().any { !it.romanization.isNullOrBlank() },
                matchType = "fuzzy",
                durationDiffMs = durationDiffMs,
                exactPlatformMatch = false
            )
        )
    }

    fun parsePersistedRaw(rawLyrics: String): Lyrics? {
        if (!rawLyrics.startsWith(STRUCTURED_PREFIX)) {
            val parsed = LyricsUtils.parseLyrics(rawLyrics).copy(areFromRemote = true)
            return parsed.takeIf(::hasValidLyrics)
        }

        val payload = runCatching {
            JsonParser.parseString(rawLyrics.removePrefix(STRUCTURED_PREFIX)).asJsonObject
        }.getOrNull() ?: return null

        fun textField(name: String): String? = payload.get(name)
            ?.takeUnless { it.isJsonNull }
            ?.asString
            ?.takeIf { it.isNotBlank() }
        val qrc = textField("qrc")
        val trans = textField("trans")
        val roma = textField("roma")
        val lrc = textField("lrc")

        val qrcLyrics = qrc
            ?.takeIf(::isReadableLyricText)
            ?.let { parseQrcLyrics(it, trans, roma) }
            ?.takeIf(::hasValidLyrics)

        if (qrcLyrics != null) return qrcLyrics
        if (!lrc.isNullOrBlank() && !isReadableLyricText(lrc)) return null
        return lrc
            ?.let { LyricsUtils.parseLyrics(it).copy(areFromRemote = true) }
            ?.takeIf(::hasValidLyrics)
            ?.let { mergeLrcExtras(it, trans, roma) }
    }

    private fun decodeLyricPayload(
        encrypted: String?,
        label: String,
        candidate: QqMusicApiService.SearchSongResult
    ): String? {
        if (encrypted.isNullOrBlank()) return null
        val decoded = runCatching { QqQrcDecoder.decrypt(encrypted) }
            .onFailure {
                Timber.d(
                    "Lyrics[QQ][Reject] reason=decode_failed field=%s candidate=%s/%s id=%s error=%s",
                    label,
                    candidate.artist,
                    candidate.title,
                    candidate.qqSongId,
                    it.message
                )
            }
            .getOrNull()
            ?: decodePlainPayloadCandidate(encrypted)
            ?: return null

        if (!isDecodedPayloadSafe(decoded) || !looksLikeLyricPayload(decoded)) {
            decodePlainPayloadCandidate(encrypted)?.let { return it }
            Timber.d(
                "Lyrics[QQ][Reject] reason=garbled_payload field=%s candidate=%s/%s id=%s",
                label,
                candidate.artist,
                candidate.title,
                candidate.qqSongId
            )
            return null
        }
        return decoded
    }

    private fun parseQrcLyrics(qrc: String, trans: String?, roma: String?): Lyrics? {
        val content = extractLyricContent(qrc)
        val baseLines = content.lines()
            .mapNotNull(::parseQrcLine)
            .sortedBy { it.time }
        if (baseLines.isEmpty()) return null

        baseLines.firstOrNull { !it.words.isNullOrEmpty() }?.let { firstLine ->
            val firstWordTime = firstLine.words.orEmpty().firstOrNull()?.time
            Timber.d(
                "Lyrics[QQ][QRC] firstLineMs=%s firstWordMs=%s deltaMs=%s",
                firstLine.time,
                firstWordTime,
                firstWordTime?.minus(firstLine.time)
            )
        }

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
        val lineDuration = match.groupValues[2].toIntOrNull() ?: 0
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
                val absoluteWordTime = if (isAbsoluteQrcWordTime(wordStart, lineStart, lineDuration)) {
                    wordStart
                } else {
                    lineStart + wordStart
                }
                words += SyncedWord(
                    time = absoluteWordTime,
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

    private fun isAbsoluteQrcWordTime(wordTime: Int, lineStart: Int, lineDuration: Int): Boolean {
        if (wordTime >= lineStart - 1000) return true
        return lineDuration > 0 && wordTime > lineDuration + 1000
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
        STRUCTURED_PREFIX + JsonObject().apply {
            qrc?.let { addProperty("qrc", it) }
            trans?.let { addProperty("trans", it) }
            roma?.let { addProperty("roma", it) }
            lrc?.let { addProperty("lrc", it) }
        }

    private fun hasValidLyrics(lyrics: Lyrics): Boolean {
        if (!lyrics.synced.isNullOrEmpty()) {
            return lyrics.synced.orEmpty().any { isReadableLyricText(it.line) }
        }
        val plain = lyrics.plain.orEmpty().joinToString("\n")
        return isReadableLyricText(plain)
    }

    companion object {
        private const val MAX_DURATION_DIFF_MS = 15_000L
        private const val SOURCE_NAME = "QQ Music"
        private const val SOURCE_URL = "https://y.qq.com/"
        private const val STRUCTURED_PREFIX = "qqlyrics://"
        private val META_TAG_REGEX = Regex("""^\[[a-z]+:.*]$""", RegexOption.IGNORE_CASE)
        private val QRC_LINE_REGEX = Regex("""^\[(\d+),(\d+)](.*)$""")
        private val QRC_WORD_REGEX = Regex("""(.*?)\((\d+),(\d+)\)""")

        private fun isReadableLyricText(value: String): Boolean {
            val text = value.trim()
            if (text.isBlank()) return false
            if (text.contains('\uFFFD')) return false

            val chars = text.asSequence().filterNot { it.isWhitespace() }.toList()
            if (chars.isEmpty()) return false

            val controlCount = chars.count { Character.isISOControl(it) }
            if (controlCount > 0) return false

            val readableCount = chars.count { char ->
                char.isLetterOrDigit() ||
                    char in '\u4E00'..'\u9FFF' ||
                    char in '\u3040'..'\u30FF' ||
                    char in '\uAC00'..'\uD7AF' ||
                    char in setOf('[', ']', ':', '.', ',', '-', '_', '(', ')', '<', '>', '/', '"', '\'', '&', ';', '!', '?')
            }
            return readableCount.toFloat() / chars.size >= 0.85f
        }

        private fun isDecodedPayloadSafe(value: String): Boolean {
            val text = value.trim()
            if (text.isBlank()) return false
            if (text.contains('\uFFFD')) return false

            val invalidControls = text.count { char ->
                Character.isISOControl(char) && char !in listOf('\n', '\r', '\t')
            }
            return invalidControls == 0
        }

        private fun decodePlainPayloadCandidate(payload: String?): String? {
            val raw = payload?.trim().orEmpty()
            if (raw.isBlank()) return null

            val candidates = mutableListOf<String>()
            fun addCandidate(value: String?) {
                val normalized = value?.trim('\uFEFF', '\u0000', ' ', '\n', '\r', '\t')
                if (!normalized.isNullOrBlank()) {
                    candidates += normalized
                }
            }

            addCandidate(raw)
            addCandidate(runCatching { java.net.URLDecoder.decode(raw, Charsets.UTF_8.name()) }.getOrNull())
            addCandidate(decodeBase64Text(raw, android.util.Base64.DEFAULT))
            addCandidate(decodeBase64Text(raw, android.util.Base64.URL_SAFE))

            return candidates
                .asSequence()
                .distinct()
                .firstOrNull { isDecodedPayloadSafe(it) && looksLikeLyricPayload(it) }
        }

        private fun decodeBase64Text(value: String, flags: Int): String? =
            runCatching {
                String(android.util.Base64.decode(value, flags), Charsets.UTF_8)
            }.getOrNull()

        private fun looksLikeLyricPayload(value: String): Boolean {
            val text = value.trim()
            return (text.contains('[') && text.contains(']')) ||
                text.contains("<Lyric", ignoreCase = true) ||
                text.contains("<Qrc", ignoreCase = true) ||
                text.contains("LyricContent", ignoreCase = true) ||
                text.contains("[offset", ignoreCase = true)
        }
    }
}
