package com.theveloper.pixelplay.data.repository

import java.util.Locale
import kotlin.math.abs

internal object LyricsMetadataMatcher {
    private const val MAX_DURATION_DIFF_MS = 15_000L
    private const val MAX_DURATION_DIFF_RATIO = 0.1
    private const val MIN_ARTIST_SCORE = 0.76
    private val excludedVersionKeywords = listOf(
        "伴奏", "纯音乐", "instrumental", "karaoke", "off vocal", "minus one",
        "backing track", "dj版", "dj version", "remix", "混音版", "伴唱"
    )

    fun strictScore(
        originalTitle: String,
        originalArtist: String,
        originalDurationMs: Long,
        candidateTitle: String,
        candidateArtist: String,
        candidateDurationMs: Long
    ): Double? {
        if (hasExcludedVersionMismatch(originalTitle, candidateTitle)) return null
        val normalizedOriginalTitle = normalizeTitle(originalTitle)
        val normalizedCandidateTitle = normalizeTitle(candidateTitle)
        if (normalizedOriginalTitle.isBlank() || normalizedOriginalTitle != normalizedCandidateTitle) return null

        val artistScore = artistScore(candidateArtist, originalArtist)
        if (artistScore < MIN_ARTIST_SCORE) return null

        val durationScore = durationScore(candidateDurationMs, originalDurationMs)
        if (durationScore <= 0.0) return null
        return artistScore * 0.8 + durationScore * 0.2
    }

    private fun normalizeTitle(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("""[（(][^）)]*[）)]"""), "")
        .replace(Regex("""[\p{Punct}\p{IsPunctuation}]+"""), " ")
        .trim()
        .replace(Regex("""\s+"""), " ")

    private fun normalizeArtist(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("""[&/、，,;；]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun splitArtists(value: String): List<String> = value
        .split(Regex("""\s*(?:/|、|,|，|&|;|；|\+)\s*|\s+(?:feat\.?|ft\.?|featuring|with|vs\.?|versus|x)\s+""", RegexOption.IGNORE_CASE))
        .map(::normalizeArtist)
        .filter(String::isNotBlank)
        .distinct()

    private fun artistScore(candidateArtist: String, originalArtist: String): Double {
        val candidate = normalizeArtist(candidateArtist)
        val original = normalizeArtist(originalArtist)
        if (candidate.isBlank() || original.isBlank()) return 0.0
        if (candidate == original) return 1.0

        val candidateParts = splitArtists(candidateArtist)
        val originalParts = splitArtists(originalArtist)
        if (candidateParts.isEmpty() || originalParts.isEmpty()) return 0.0
        val originalInCandidate = originalParts.all { it in candidateParts }
        val candidateInOriginal = candidateParts.all { it in originalParts }
        return when {
            originalInCandidate && candidateInOriginal -> 0.95
            originalInCandidate -> 0.82
            candidateInOriginal -> 0.76
            else -> 0.0
        }
    }

    private fun durationScore(candidateMs: Long, originalMs: Long): Double {
        if (candidateMs <= 0L || originalMs <= 0L) return 0.75
        val maxDiff = maxOf(MAX_DURATION_DIFF_MS, (originalMs * MAX_DURATION_DIFF_RATIO).toLong())
        val diff = abs(candidateMs - originalMs)
        if (diff > maxDiff) return 0.0
        return 1.0 - (diff.toDouble() / maxDiff.toDouble()) * 0.3
    }

    private fun hasExcludedVersionMismatch(originalTitle: String, candidateTitle: String): Boolean {
        val originalHasExcludedVersion = containsExcludedVersion(originalTitle)
        return !originalHasExcludedVersion && containsExcludedVersion(candidateTitle)
    }

    private fun containsExcludedVersion(title: String): Boolean {
        val normalized = title.lowercase(Locale.ROOT)
        return excludedVersionKeywords.any(normalized::contains)
    }
}
