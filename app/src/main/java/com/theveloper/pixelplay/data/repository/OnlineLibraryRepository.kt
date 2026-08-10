package com.theveloper.pixelplay.data.repository

import com.theveloper.pixelplay.data.model.LibrarySong
import com.theveloper.pixelplay.data.model.Playlist
import com.theveloper.pixelplay.data.model.RemotePlaylistDetail
import com.theveloper.pixelplay.data.model.TransientOnlineSong

/** Explicit persistence boundary for temporary online browse/search results. */
interface OnlineLibraryRepository {
    suspend fun saveSong(song: TransientOnlineSong): Result<LibrarySong>

    suspend fun savePlaylist(playlist: RemotePlaylistDetail): Result<Playlist>
}
