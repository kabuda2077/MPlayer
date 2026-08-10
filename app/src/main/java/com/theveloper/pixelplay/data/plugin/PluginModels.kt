package com.theveloper.pixelplay.data.plugin

import com.theveloper.pixelplay.data.model.Song

enum class PluginSource(
    val lxSource: String,
    val uriScheme: String,
    val displayName: String,
    val aliases: Set<String>
) {
    NETEASE("wy", "netease", "网易云", setOf("wy", "netease", "163", "163music", "neteasecloud")),
    KUGOU("kg", "kugou", "酷狗", setOf("kg", "kugou")),
    KUWO("kw", "kuwo", "酷我", setOf("kw", "kuwo")),
    QQ_MUSIC("tx", "qqmusic", "QQ音乐", setOf("tx", "qq", "qqmusic"));

    companion object {
        val fallbackPriority = listOf(NETEASE, KUGOU, KUWO, QQ_MUSIC)

        fun fromAlias(value: String?): PluginSource? {
            val normalized = value?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { source ->
                source.lxSource == normalized || source.aliases.any { it == normalized }
            }
        }
    }
}

enum class PluginQuality(
    val lxQuality: String,
    val displayName: String,
    private val aliases: Set<String>
) {
    LOW(
        lxQuality = "128k",
        displayName = "128k",
        aliases = setOf("128", "128k", "128kbps", "low", "standard", "normal", "mp3_128")
    ),
    HIGH(
        lxQuality = "320k",
        displayName = "320k",
        aliases = setOf("320", "320k", "320kbps", "high", "higher", "hq", "exhigh", "mp3_320")
    ),
    LOSSLESS(
        lxQuality = "flac",
        displayName = "FLAC",
        aliases = setOf("flac", "lossless", "lossless24", "sq", "ape", "wav")
    ),
    HI_RES(
        lxQuality = "flac24bit",
        displayName = "24bit",
        aliases = setOf("flac24bit", "flac24", "24bit", "24bits", "24bitflac")
    ),
    HIRES(
        lxQuality = "hires",
        displayName = "Hi-Res",
        aliases = setOf("hires", "hi_res", "hi-res", "hr", "highresolution", "hireslossless")
    ),
    ATMOS(
        lxQuality = "atmos",
        displayName = "Atmos",
        aliases = setOf("atmos", "dolby", "dolbyatmos", "sky", "viperatmos")
    ),
    ATMOS_PLUS(
        lxQuality = "atmos_plus",
        displayName = "Atmos+",
        aliases = setOf("atmosplus", "atmos+", "atmos_plus", "dolbyplus", "dolbyatmosplus")
    ),
    MASTER(
        lxQuality = "master",
        displayName = "Master",
        aliases = setOf("master", "jm", "jymaster", "viperclear", "viper_clear")
    );

    val rank: Int
        get() = when (this) {
            LOW -> 1
            HIGH -> 2
            LOSSLESS -> 3
            HI_RES -> 4
            HIRES -> 5
            ATMOS -> 6
            ATMOS_PLUS -> 7
            MASTER -> 8
        }

    companion object {
        val preferenceOrder = listOf(MASTER, ATMOS_PLUS, ATMOS, HIRES, HI_RES, LOSSLESS, HIGH, LOW)

        fun fromLxQuality(value: String): PluginQuality? =
            fromAlias(value)

        fun fromAlias(value: String?): PluginQuality? {
            val normalized = value?.normalizeQualityAlias() ?: return null
            return entries.firstOrNull { quality ->
                quality.lxQuality.normalizeQualityAlias() == normalized ||
                    quality.aliases.any { it.normalizeQualityAlias() == normalized }
            }
        }

        fun canonicalize(value: String?): String? =
            fromAlias(value)?.lxQuality ?: value?.trim()?.takeIf { it.isNotBlank() }

        fun displayFor(value: String?): String =
            fromAlias(value)?.displayName ?: value?.trim()?.takeIf { it.isNotBlank() } ?: "unknown"
    }
}

private fun String.normalizeQualityAlias(): String =
    trim()
        .lowercase()
        .replace(Regex("""[\s_\-./]"""), "")
        .removeSuffix("quality")

data class PluginMusicInfo(
    val id: String,
    val source: PluginSource,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val albumArtUrl: String?,
    val rawContentUri: String,
    val platformSongId: String,
    val sourceSongId: String = platformSongId,
    val sourceSongMid: String? = null,
    val sourceSongHash: String? = null
)

data class PluginAudioResolveResult(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val quality: String? = null,
    val source: String? = null,
    val expireAt: Long? = null,
    val isTrial: Boolean = false,
    val durationMs: Long? = null,
    val bitrate: Int? = null,
    val mimeType: String? = null
)

data class PluginPlatformCandidate(
    val source: PluginSource,
    val id: String,
    val songMid: String? = null,
    val hash: String? = null,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val matchScore: Double = 1.0,
    val matchType: String = "id"
)

data class PluginSourceCapability(
    val source: String,
    val actions: Set<String>,
    val qualities: Set<String>
) {
    fun supportsMusicUrl(): Boolean = "musicUrl" in actions

    fun normalizedQualities(): Set<PluginQuality> =
        qualities.mapNotNullTo(linkedSetOf()) { PluginQuality.fromAlias(it) }

    fun qualityDisplayText(): String {
        val normalized = normalizedQualities()
        return if (normalized.isNotEmpty()) {
            PluginQuality.preferenceOrder
                .filter { it in normalized }
                .joinToString { it.displayName }
        } else {
            qualities.joinToString().ifBlank { "musicUrl" }
        }
    }
}

data class PluginCapabilities(
    val sources: Map<String, PluginSourceCapability> = emptyMap()
) {
    fun supports(source: PluginSource): Boolean =
        sources[source.lxSource]?.supportsMusicUrl() == true

    fun qualitiesFor(source: PluginSource): Set<PluginQuality> =
        sources[source.lxSource]?.normalizedQualities().orEmpty()
}

data class PluginLogEntry(
    val timestamp: Long,
    val level: String,
    val message: String
)

data class PluginRuntimeState(
    val pluginId: String? = null,
    val pluginName: String? = null,
    val initialized: Boolean = false,
    val initializing: Boolean = false,
    val errorMessage: String? = null,
    val capabilities: PluginCapabilities = PluginCapabilities(),
    val logs: List<PluginLogEntry> = emptyList()
)

data class ImportedPluginMetadata(
    val name: String,
    val description: String,
    val author: String,
    val version: String,
    val homepage: String
)

sealed class PluginResolveException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NoEnabledPlugin : PluginResolveException("未启用音源插件")
    class InitFailed(message: String? = null) : PluginResolveException(message ?: "音源插件初始化失败")
    class UnsupportedSource : PluginResolveException("插件不支持该平台")
    class UnsupportedQuality : PluginResolveException("插件不支持该音质")
    class InvalidAudioSource(message: String = "插件返回了无效音源") : PluginResolveException(message)
    class RequestTimeout : PluginResolveException("音源插件请求超时")
    class RequestFailed(message: String) : PluginResolveException(message)
}

fun Song.toPluginMusicInfo(source: PluginSource, platformSongId: String): PluginMusicInfo =
    PluginMusicInfo(
        id = id,
        source = source,
        title = title,
        artist = displayArtist,
        album = album,
        durationMs = duration,
        albumArtUrl = albumArtUriString,
        rawContentUri = contentUriString,
        platformSongId = platformSongId,
        sourceSongId = platformSongId,
        sourceSongMid = if (source == PluginSource.QQ_MUSIC) platformSongId else null,
        sourceSongHash = if (source == PluginSource.KUGOU) platformSongId else null
    )
