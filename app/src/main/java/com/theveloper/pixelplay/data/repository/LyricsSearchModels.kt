package com.theveloper.pixelplay.data.repository

import com.theveloper.pixelplay.data.model.Lyrics

data class LyricsSearchRecord(
    val id: String,
    val name: String,
    val artistName: String,
    val albumName: String = "",
    val durationMs: Long? = null,
    val sourceName: String = "QQ Music",
    val sourceUrl: String = "https://y.qq.com/",
    val syncedLyrics: String? = null
)

data class LyricsSearchResult(
    val record: LyricsSearchRecord,
    val lyrics: Lyrics,
    val rawLyrics: String
)
