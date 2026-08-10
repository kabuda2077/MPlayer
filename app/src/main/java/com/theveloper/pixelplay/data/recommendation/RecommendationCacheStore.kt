package com.theveloper.pixelplay.data.recommendation

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.theveloper.pixelplay.data.model.ArtistRef
import com.theveloper.pixelplay.data.model.OnlineBrowsePlatform
import com.theveloper.pixelplay.data.model.RecommendationAuthState
import com.theveloper.pixelplay.data.model.RecommendationItem
import com.theveloper.pixelplay.data.model.RecommendationPage
import com.theveloper.pixelplay.data.model.RecommendationSection
import com.theveloper.pixelplay.data.model.RecommendationSectionKind
import com.theveloper.pixelplay.data.model.RecommendationSongItem
import com.theveloper.pixelplay.data.model.RemoteChartReference
import com.theveloper.pixelplay.data.model.RemotePlaylistReference
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.model.TransientOnlineSong
import com.theveloper.pixelplay.data.plugin.PluginSource
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** App-private recommendation cache. It contains DTOs only and never auth cookies. */
@Singleton
class RecommendationCacheStore @Inject constructor(
    @ApplicationContext context: Context,
    private val gson: Gson
) {
    private companion object {
        const val CACHE_SCHEMA_VERSION = 8
    }

    private val directory = File(context.filesDir, "recommendation_cache").apply { mkdirs() }
    private val type = object : TypeToken<CacheEnvelope>() {}.type

    fun read(platform: OnlineBrowsePlatform): CachedRecommendation? {
        val file = File(directory, "${platform.name.lowercase()}.json")
        if (!file.exists()) return null
        return runCatching {
            gson.fromJson<CacheEnvelope>(file.readText(), type)
                .toPage()
                .let { CachedRecommendation(it, file.lastModified()) }
        }.getOrNull()
    }

    fun write(page: RecommendationPage) {
        val file = File(directory, "${page.platform.name.lowercase()}.json")
        runCatching {
            file.writeText(gson.toJson(CacheEnvelope.from(page)))
        }
    }

    data class CachedRecommendation(val page: RecommendationPage, val storedAt: Long)

    private data class CacheEnvelope(
        val schemaVersion: Int = 0,
        val platform: String,
        val authState: String,
        val fetchedAt: Long,
        val sections: List<SectionDto>
    ) {
        fun toPage(): RecommendationPage {
            check(schemaVersion == CACHE_SCHEMA_VERSION) { "Unsupported recommendation cache schema" }
            val browsePlatform = OnlineBrowsePlatform.valueOf(platform)
            return RecommendationPage(
                platform = browsePlatform,
                authState = runCatching { RecommendationAuthState.valueOf(authState) }
                    .getOrDefault(RecommendationAuthState.UNKNOWN),
                sections = sections.map { it.toModel(browsePlatform) },
                fetchedAt = fetchedAt
            )
        }

        companion object {
            fun from(page: RecommendationPage) = CacheEnvelope(
                schemaVersion = CACHE_SCHEMA_VERSION,
                platform = page.platform.name,
                authState = page.authState.name,
                fetchedAt = page.fetchedAt,
                sections = page.sections.map(SectionDto::from)
            )
        }
    }

    private data class SectionDto(
        val id: String,
        val title: String,
        val kind: String,
        val hasMore: Boolean,
        val items: List<ItemDto>
    ) {
        fun toModel(platform: OnlineBrowsePlatform) = RecommendationSection(
            id = id,
            title = title,
            kind = runCatching { RecommendationSectionKind.valueOf(kind) }
                .getOrDefault(RecommendationSectionKind.DAILY_SONGS),
            items = items.mapNotNull { it.toModel(platform) },
            hasMore = hasMore
        )

        companion object {
            fun from(section: RecommendationSection) = SectionDto(
                id = section.id,
                title = section.title,
                kind = section.kind.name,
                hasMore = section.hasMore,
                items = section.items.map(ItemDto::from)
            )
        }
    }

    private data class ItemDto(
        val type: String,
        val remoteId: String? = null,
        val title: String,
        val subtitle: String? = null,
        val artworkUri: String? = null,
        val songSource: String? = null,
        val songSourceId: String? = null,
        val song: SongDto? = null,
        val songCount: Int = 0,
        val previewArtworkUris: List<String> = emptyList()
    ) {
        fun toModel(platform: OnlineBrowsePlatform): RecommendationItem? = when (type) {
            "song" -> {
                val source = runCatching { PluginSource.valueOf(songSource.orEmpty()) }.getOrNull()
                    ?: return null
                val songValue = song?.toModel() ?: return null
                RecommendationSongItem(TransientOnlineSong.from(songValue))
            }
            "playlist" -> RemotePlaylistReference(
                platform = platform,
                remoteId = remoteId.orEmpty(),
                title = title,
                subtitle = subtitle,
                artworkUri = artworkUri,
                songCount = songCount
            )
            "chart" -> RemoteChartReference(
                platform = platform,
                remoteId = remoteId.orEmpty(),
                title = title,
                subtitle = subtitle,
                artworkUri = artworkUri,
                previewArtworkUris = previewArtworkUris
            )
            else -> null
        }

        companion object {
            fun from(item: RecommendationItem) = when (item) {
                is RecommendationSongItem -> ItemDto(
                    type = "song",
                    title = item.title,
                    subtitle = item.subtitle,
                    artworkUri = item.artworkUri,
                    songSource = item.transientSong.source.name,
                    songSourceId = item.transientSong.sourceSongId,
                    song = SongDto.from(item.transientSong.song)
                )
                is RemotePlaylistReference -> ItemDto(
                    type = "playlist",
                    remoteId = item.remoteId,
                    title = item.title,
                    subtitle = item.subtitle,
                    artworkUri = item.artworkUri,
                    songCount = item.songCount
                )
                is RemoteChartReference -> ItemDto(
                    type = "chart",
                    remoteId = item.remoteId,
                    title = item.title,
                    subtitle = item.subtitle,
                    artworkUri = item.artworkUri,
                    previewArtworkUris = item.previewArtworkUris
                )
            }
        }
    }

    private data class SongDto(
        val id: String,
        val title: String,
        val artist: String,
        val artistId: Long,
        val artists: List<ArtistRef>,
        val album: String,
        val albumId: Long,
        val albumArtist: String?,
        val contentUriString: String,
        val albumArtUriString: String?,
        val duration: Long,
        val genre: String?,
        val trackNumber: Int,
        val discNumber: Int?,
        val year: Int,
        val dateAdded: Long,
        val mimeType: String?,
        val bitrate: Int?,
        val sampleRate: Int?,
        val neteaseId: Long?,
        val qqMusicMid: String?
    ) {
        fun toModel() = Song(
            id, null, title, artist, artistId, artists, album, albumId, albumArtist,
            "", contentUriString, albumArtUriString, duration, genre, null, false,
            trackNumber, discNumber, year, dateAdded, 0L, mimeType, bitrate, sampleRate,
            neteaseId, qqMusicMid
        )

        companion object {
            fun from(song: Song) = SongDto(
                song.id, song.title, song.artist, song.artistId, song.artists,
                song.album, song.albumId, song.albumArtist, song.contentUriString,
                song.albumArtUriString, song.duration, song.genre, song.trackNumber,
                song.discNumber, song.year, song.dateAdded, song.mimeType, song.bitrate,
                song.sampleRate, song.neteaseId, song.qqMusicMid
            )
        }
    }
}
