package com.theveloper.pixelplay.data.repository

import com.theveloper.pixelplay.data.model.Lyrics
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.utils.PlaybackTraceContext

interface LyricsRepository {
    /**
     * Returns already-persisted lyrics without performing any network request.
     */
    suspend fun getStoredLyrics(song: Song): Pair<Lyrics, String>?

    /**
     * Get lyrics for a song using the fixed source strategy.
     * 
     * @param song The song to get lyrics for
     * @param forceRefresh If true, bypasses in-memory cache
     * @return Lyrics object or null if not found
     */
    suspend fun getLyrics(
        song: Song,
        forceRefresh: Boolean = false,
        traceContext: PlaybackTraceContext? = null
    ): Lyrics?
    
    /**
     * Fetch lyrics from remote API and save to database.
     */
    suspend fun fetchFromRemote(song: Song): Result<Pair<Lyrics, String>>
    
    /**
     * Search for lyrics on remote API and return multiple results.
     */
    suspend fun searchRemote(song: Song): Result<Pair<String, List<LyricsSearchResult>>>
  
    /**
     * Search for lyrics on remote API using query title and artist, and return multiple results.
     */
    suspend fun searchRemoteByQuery(title: String, artist: String? = null): Result<Pair<String, List<LyricsSearchResult>>>
    
    /**
     * Update lyrics for a song in the database.
     */
    suspend fun updateLyrics(song: Song, lyricsContent: String)
    
    /**
     * Reset lyrics for a song (remove from database and cache).
     */
    suspend fun resetLyrics(song: Song)
    
    /**
     * Reset all lyrics (clear database and cache).
     */
    suspend fun resetAllLyrics()
    
    /**
     * Clear in-memory cache only.
     */
    fun clearCache()

    /**
     * Scans local .lrc files for the provided songs and updates the database if found.
     * 
     * @param songs List of songs to scan for
     * @param onProgress Callback for progress updates (current, total)
     * @return Number of songs updated
     */
    suspend fun scanAndAssignLocalLrcFiles(
        songs: List<Song>,
        onProgress: suspend (current: Int, total: Int) -> Unit
    ): Int
}
