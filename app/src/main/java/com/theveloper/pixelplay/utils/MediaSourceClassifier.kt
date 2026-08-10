package com.theveloper.pixelplay.utils

import com.theveloper.pixelplay.data.model.StorageFilter
import com.theveloper.pixelplay.data.plugin.PluginSource
import java.util.Locale

enum class MediaSourceKind(
    val uriScheme: String?,
    val isOnline: Boolean
) {
    LOCAL(uriScheme = null, isOnline = false),
    UNKNOWN(uriScheme = null, isOnline = false),
    NETEASE(uriScheme = "netease", isOnline = true),
    QQ_MUSIC(uriScheme = "qqmusic", isOnline = true),
    KUGOU(uriScheme = "kugou", isOnline = true),
    KUWO(uriScheme = "kuwo", isOnline = true)
}

object MediaSourceClassifier {
    private val PLAYBACK_RESOLVABLE_KINDS = setOf(
        MediaSourceKind.NETEASE,
        MediaSourceKind.QQ_MUSIC,
        MediaSourceKind.KUGOU,
        MediaSourceKind.KUWO
    )

    val playbackResolvableSchemes: Set<String> = PLAYBACK_RESOLVABLE_KINDS
        .mapNotNullTo(linkedSetOf()) { it.uriScheme }

    fun classify(uri: String?): MediaSourceKind {
        val scheme = uri.schemeOrNull() ?: return MediaSourceKind.LOCAL
        return when (scheme) {
            "file", "content" -> MediaSourceKind.LOCAL
            "netease" -> MediaSourceKind.NETEASE
            "qqmusic" -> MediaSourceKind.QQ_MUSIC
            "kugou" -> MediaSourceKind.KUGOU
            "kuwo" -> MediaSourceKind.KUWO
            else -> MediaSourceKind.UNKNOWN
        }
    }

    fun isLocal(uri: String?): Boolean = classify(uri) == MediaSourceKind.LOCAL

    fun isOnline(uri: String?): Boolean = classify(uri).isOnline

    fun requiresPlaybackResolution(uri: String?): Boolean =
        classify(uri) in PLAYBACK_RESOLVABLE_KINDS

    fun isLibraryPersistable(uri: String?): Boolean = when (classify(uri)) {
        MediaSourceKind.LOCAL,
        MediaSourceKind.NETEASE,
        MediaSourceKind.QQ_MUSIC -> true

        MediaSourceKind.KUGOU,
        MediaSourceKind.KUWO,
        MediaSourceKind.UNKNOWN -> false
    }

    fun matchesStorageFilter(uri: String?, filter: StorageFilter): Boolean = when (filter) {
        StorageFilter.ALL -> true
        StorageFilter.ONLINE -> isOnline(uri)
        StorageFilter.OFFLINE -> isLocal(uri)
    }

    fun pluginSource(uri: String?): PluginSource? = when (classify(uri)) {
        MediaSourceKind.NETEASE -> PluginSource.NETEASE
        MediaSourceKind.QQ_MUSIC -> PluginSource.QQ_MUSIC
        MediaSourceKind.KUGOU -> PluginSource.KUGOU
        MediaSourceKind.KUWO -> PluginSource.KUWO
        else -> null
    }

    fun isPluginOnline(uri: String?): Boolean = pluginSource(uri) != null

    fun isDownloadable(uri: String?): Boolean = pluginSource(uri) != null

    fun idPart(uri: String?, expectedSource: PluginSource? = null): String? {
        val trimmed = uri?.trim().orEmpty()
        if (trimmed.isBlank()) return null
        expectedSource?.let { source ->
            val scheme = source.uriScheme
            if (!trimmed.startsWith("$scheme://", ignoreCase = true) &&
                !trimmed.startsWith("$scheme:", ignoreCase = true)
            ) {
                return null
            }
        }
        val separatorIndex = trimmed.indexOf(':')
        if (separatorIndex < 0) return null
        return trimmed
            .substring(separatorIndex + 1)
            .removePrefix("//")
            .trimStart('/')
            .takeIf { it.isNotBlank() }
    }

    fun displayName(uri: String?): String = when (classify(uri)) {
        MediaSourceKind.LOCAL -> "Local"
        MediaSourceKind.UNKNOWN -> "Unknown"
        MediaSourceKind.NETEASE -> "Netease Music"
        MediaSourceKind.QQ_MUSIC -> "QQ Music"
        MediaSourceKind.KUGOU -> "Kugou"
        MediaSourceKind.KUWO -> "Kuwo"
    }
}

private fun String?.schemeOrNull(): String? {
    val trimmed = this?.trim().orEmpty()
    if (trimmed.isBlank()) return null
    val separatorIndex = trimmed.indexOf(':')
    if (separatorIndex <= 0) return null
    return trimmed.substring(0, separatorIndex).lowercase(Locale.ROOT)
}
