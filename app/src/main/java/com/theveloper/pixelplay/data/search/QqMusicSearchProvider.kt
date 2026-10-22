package com.theveloper.pixelplay.data.search

import com.theveloper.pixelplay.data.model.OnlineSearchPlatform
import com.theveloper.pixelplay.data.model.OnlineSongMapper
import com.theveloper.pixelplay.data.model.TransientOnlineSong
import com.theveloper.pixelplay.data.network.qqmusic.QqMusicApiService
import com.theveloper.pixelplay.data.plugin.PluginSource
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class QqMusicSearchProvider @Inject constructor(
    private val qqMusicApiService: QqMusicApiService
) : OnlineSearchProvider {

    override val platform: OnlineSearchPlatform = OnlineSearchPlatform.QQ

    override suspend fun searchSongs(query: String, limit: Int): List<TransientOnlineSong> {
        return qqMusicApiService.searchSongs(query, page = 1, pageSize = limit)
            .mapNotNull { item ->
                val songMid = item.mid.ifBlank { item.qqSongId }.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                OnlineSongMapper.toTransient(
                    source = PluginSource.QQ_MUSIC,
                    sourceSongId = songMid,
                    title = item.title,
                    artist = item.artist,
                    album = item.album,
                    durationMs = item.durationMs,
                    artworkUrl = item.albumArtUrl
                )
            }
    }

    override suspend fun loadPopularSearchTerms(limit: Int): List<String> {
        return qqMusicApiService.getHotSearchTerms(limit)
    }
}
