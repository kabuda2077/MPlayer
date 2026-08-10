package com.theveloper.pixelplay.data.model

import androidx.compose.runtime.Immutable

/** Platforms that expose browse/recommendation pages in the app. */
enum class OnlineBrowsePlatform(val label: String) {
    NETEASE("网易云"),
    QQ("QQ")
}

enum class RecommendationAuthState {
    LOGGED_IN,
    LOGGED_OUT,
    LOGIN_REQUIRED,
    UNKNOWN
}

enum class RecommendationSectionKind {
    DAILY_SONGS,
    NEW_SONGS,
    RECOMMENDED_PLAYLISTS,
    CHART_ENTRY
}

@Immutable
sealed interface RecommendationItem {
    val key: String
    val title: String
    val subtitle: String?
    val artworkUri: String?
}

@Immutable
data class RecommendationSongItem(
    val transientSong: TransientOnlineSong
) : RecommendationItem {
    override val key: String = "song:${transientSong.source.name}:${transientSong.sourceSongId}"
    override val title: String = transientSong.song.title
    override val subtitle: String = transientSong.song.displayArtist
    override val artworkUri: String? = transientSong.song.albumArtUriString
}

@Immutable
data class RemotePlaylistReference(
    val platform: OnlineBrowsePlatform,
    val remoteId: String,
    override val title: String,
    override val subtitle: String? = null,
    override val artworkUri: String? = null,
    val songCount: Int = 0
) : RecommendationItem {
    override val key: String = "playlist:${platform.name}:$remoteId"
}

@Immutable
data class RemoteChartReference(
    val platform: OnlineBrowsePlatform,
    val remoteId: String,
    override val title: String,
    override val subtitle: String? = null,
    override val artworkUri: String? = null,
    /** A small preview assembled from the chart's first few tracks. */
    val previewArtworkUris: List<String> = emptyList()
) : RecommendationItem {
    override val key: String = "chart:${platform.name}:$remoteId"
}

@Immutable
data class RecommendationSection(
    val id: String,
    val title: String,
    val kind: RecommendationSectionKind,
    val items: List<RecommendationItem>,
    val hasMore: Boolean = false
)

@Immutable
data class RecommendationPage(
    val platform: OnlineBrowsePlatform,
    val authState: RecommendationAuthState,
    val sections: List<RecommendationSection>,
    val fetchedAt: Long,
    val isStale: Boolean = false
)

@Immutable
data class RemotePlaylistDetail(
    val reference: RemotePlaylistReference,
    val songs: List<TransientOnlineSong>,
    val description: String? = null
)

@Immutable
data class RemoteChartDetail(
    val reference: RemoteChartReference,
    val songs: List<TransientOnlineSong>
)
