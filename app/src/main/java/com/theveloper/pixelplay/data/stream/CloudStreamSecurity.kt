package com.theveloper.pixelplay.data.stream

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Lightweight validation utilities shared by plugin streaming and downloads.
 *
 * The checks are intentionally cheap (string/number validation only) so they
 * can run on every request without adding measurable overhead.
 */
object CloudStreamSecurity {
    const val MAX_STREAM_CONTENT_LENGTH_BYTES: Long = 2L * 1024L * 1024L * 1024L

    private val QQMUSIC_SONG_MID_REGEX = Regex("^[A-Za-z0-9_-]{6,50}$")
    private val KUGOU_HASH_REGEX = Regex("^[A-Za-z0-9]{16,64}$")
    private val KUWO_SONG_ID_REGEX = Regex("^[A-Za-z0-9_-]{1,64}$")
    private val FORBIDDEN_HOSTS = setOf("localhost", "127.0.0.1", "0.0.0.0", "::1", "[::1]")
    private val EXTRA_ALLOWED_AUDIO_TYPES = setOf(
        "application/octet-stream",
        "binary/octet-stream",
        "application/mp4",
        "video/mp4"
    )

    fun validateNeteaseSongId(songId: Long): Boolean = songId > 0L

    fun validateQqMusicSongMid(songMid: String): Boolean = QQMUSIC_SONG_MID_REGEX.matches(songMid)

    fun validateKugouHash(hash: String): Boolean = KUGOU_HASH_REGEX.matches(hash)

    fun validateKuwoSongId(songId: String): Boolean = KUWO_SONG_ID_REGEX.matches(songId.removePrefix("MUSIC_"))

    fun isSupportedAudioContentType(contentTypeHeader: String?): Boolean {
        if (contentTypeHeader.isNullOrBlank()) {
            return true
        }
        val normalized = contentTypeHeader.substringBefore(';').trim().lowercase()
        if (normalized.startsWith("audio/")) {
            return true
        }
        return normalized in EXTRA_ALLOWED_AUDIO_TYPES
    }

    fun isAcceptableContentLength(contentLengthHeader: String?): Boolean {
        if (contentLengthHeader.isNullOrBlank()) {
            return true
        }
        val parsed = contentLengthHeader.toLongOrNull() ?: return false
        return parsed in 0..MAX_STREAM_CONTENT_LENGTH_BYTES
    }

    fun isSafeRemoteStreamUrl(
        url: String,
        allowedHostSuffixes: Set<String> = emptySet(),
        allowHttpForAllowedHosts: Boolean = false,
        allowHttpWithoutHostAllowlist: Boolean = false
    ): Boolean {
        val httpUrl = url.toHttpUrlOrNull() ?: return false
        val host = httpUrl.host.lowercase()

        if (host in FORBIDDEN_HOSTS) return false
        if (host.endsWith(".local")) return false
        if (isPrivateIpv4Literal(host)) return false
        
        if (httpUrl.username.isNotEmpty() || httpUrl.password.isNotEmpty()) return false

        val hostAllowed = hostMatchesAllowedSuffix(host, allowedHostSuffixes)
        if (!hostAllowed) return false

        return when (httpUrl.scheme.lowercase()) {
            "https" -> true
            "http" -> (allowHttpForAllowedHosts && allowedHostSuffixes.isNotEmpty()) ||
                (allowHttpWithoutHostAllowlist && allowedHostSuffixes.isEmpty())
            else -> false
        }
    }

    private fun hostMatchesAllowedSuffix(host: String, allowedHostSuffixes: Set<String>): Boolean {
        if (allowedHostSuffixes.isEmpty()) return true
        return allowedHostSuffixes.any { suffix ->
            val normalized = suffix.lowercase()
            host == normalized || host.endsWith(".$normalized")
        }
    }

    internal fun isPrivateIpv4Literal(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false

        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false

        val first = octets[0]
        val second = octets[1]

        return first == 0 ||
            first == 10 ||
            first == 127 ||
            (first == 169 && second == 254) ||
            (first == 172 && second in 16..31) ||
            (first == 192 && second == 168)
    }

}
