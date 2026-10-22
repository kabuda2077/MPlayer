package com.theveloper.pixelplay.data.netease

import com.google.gson.JsonParser
import com.theveloper.pixelplay.data.model.Lyrics
import com.theveloper.pixelplay.data.model.SyncedLine
import com.theveloper.pixelplay.data.model.SyncedWord
import com.theveloper.pixelplay.utils.LyricsUtils

object NeteaseLyricsParser {
    private val NETEASE_YRC_LINE_REGEX = Regex("""^\[(\d+),(\d+)](.*)$""")
    private val NETEASE_YRC_WORD_REGEX = Regex("""(.*?)\((\d+),(\d+)\)""")

    private fun Lyrics.isValid(): Boolean = !synced.isNullOrEmpty() || !plain.isNullOrEmpty()

    fun looksLikeNeteaseOfficialLyricsPayload(rawLyrics: String): Boolean {
        val trimmed = rawLyrics.trimStart()
        if (!trimmed.startsWith("{")) return false
        if (!trimmed.contains("\"lyric\"")) return false
        return listOf("\"lrc\"", "\"tlyric\"", "\"romalrc\"", "\"yrc\"", "\"ytlrc\"", "\"yromalrc\"")
            .any(trimmed::contains)
    }

    fun parseNeteaseLyricsPayload(rawPayload: String): Lyrics? {
        val root = runCatching { JsonParser.parseString(rawPayload).asJsonObject }.getOrNull()
            ?: return null

        fun nestedLyric(key: String): String {
            val nested = root.get(key)
            if (nested == null || !nested.isJsonObject) return ""
            val lyricElement = nested.asJsonObject.get("lyric") ?: return ""
            return runCatching { lyricElement.asString }.getOrDefault("")
        }

        val lrc = nestedLyric("lrc")
        val tlyric = nestedLyric("tlyric")
        val romalrc = nestedLyric("romalrc")
        val yrc = nestedLyric("yrc")
        val ytlrc = nestedLyric("ytlrc")
        val yromalrc = nestedLyric("yromalrc")

        buildNeteaseWordLevelLyrics(
            yrc = yrc,
            translatedLyric = ytlrc,
            romanizedLyric = yromalrc
        )?.takeIf { it.isValid() }
            ?.takeUnless(LyricsUtils::hasNeteaseJsonLyricGarbage)
            ?.let { return it.copy(areFromRemote = true) }

        return buildNeteaseLineLyrics(
            lyric = lrc,
            translatedLyric = tlyric,
            romanizedLyric = romalrc
        )?.takeIf { it.isValid() }
            ?.takeUnless(LyricsUtils::hasNeteaseJsonLyricGarbage)
            ?.copy(areFromRemote = true)
    }

    fun buildNeteaseLineLyrics(
        lyric: String,
        translatedLyric: String,
        romanizedLyric: String
    ): Lyrics? {
        if (lyric.isBlank()) return null
        var parsed = LyricsUtils.parseLyrics(lyric)

        if (parsed.synced.isNullOrEmpty() && parsed.plain.isNullOrEmpty()) return null

        parsed = parsed.copy(
            synced = LyricsUtils.mergeSupplementalLines(
                baseLines = parsed.synced.orEmpty(),
                translationLines = LyricsUtils.parseSameTimestampBilingualLrc(translatedLyric).synced.orEmpty(),
                romanizationLines = LyricsUtils.parseLyrics(romanizedLyric).synced.orEmpty()
            )
        )

        return parsed.copy(
            plain = LyricsUtils.rebuildPlainFromSynced(parsed.synced.orEmpty()).ifEmpty { parsed.plain },
            areFromRemote = true
        )
    }

    fun buildNeteaseWordLevelLyrics(
        yrc: String,
        translatedLyric: String,
        romanizedLyric: String
    ): Lyrics? {
        if (yrc.isBlank()) return null
        val parsedYrc = parseNeteaseYrc(yrc) ?: return null
        val merged = LyricsUtils.mergeSupplementalLines(
            baseLines = parsedYrc.synced.orEmpty(),
            translationLines = LyricsUtils.parseSameTimestampBilingualLrc(translatedLyric).synced.orEmpty(),
            romanizationLines = LyricsUtils.parseLyrics(romanizedLyric).synced.orEmpty()
        )

        return parsedYrc.copy(
            synced = merged,
            plain = LyricsUtils.rebuildPlainFromSynced(merged),
            areFromRemote = true
        )
    }

    fun parseNeteaseYrc(raw: String): Lyrics? {
        val syncedLines = raw.lines().mapNotNull { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@mapNotNull null
            val match = NETEASE_YRC_LINE_REGEX.matchEntire(line) ?: return@mapNotNull null
            val lineTime = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val body = match.groupValues[3]
            val words = mutableListOf<SyncedWord>()
            val plainText = StringBuilder()
            var previousEndsWithSpace = true

            NETEASE_YRC_WORD_REGEX.findAll(body).forEach { wordMatch ->
                val text = wordMatch.groupValues[1]
                val wordOffset = wordMatch.groupValues[2].toIntOrNull() ?: 0
                val cleaned = text.trim()
                plainText.append(text)
                if (cleaned.isNotEmpty()) {
                    val startsNew = previousEndsWithSpace || text.firstOrNull()?.isWhitespace() == true || words.isEmpty()
                    words += SyncedWord(
                        time = lineTime + wordOffset,
                        word = cleaned,
                        startsNewWord = startsNew
                    )
                }
                previousEndsWithSpace = text.lastOrNull()?.isWhitespace() == true
            }

            val lineText = plainText.toString().trim()
            if (lineText.isEmpty() && words.isEmpty()) return@mapNotNull null
            SyncedLine(
                time = lineTime,
                line = lineText,
                words = words.takeIf { it.isNotEmpty() }
            )
        }

        if (syncedLines.isEmpty()) return null
        val plain = syncedLines.map { it.line }
        return Lyrics(synced = syncedLines, plain = plain, areFromRemote = true)
    }
}
