package com.theveloper.pixelplay.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface LyricsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(lyrics: LyricsEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(lyrics: List<LyricsEntity>)

    @Query("SELECT * FROM lyrics WHERE mediaKey = :mediaKey")
    suspend fun getLyrics(mediaKey: String): LyricsEntity?

    @Query("DELETE FROM lyrics WHERE mediaKey = :mediaKey")
    suspend fun deleteLyrics(mediaKey: String)

    @Query("DELETE FROM lyrics")
    suspend fun deleteAll()

    @Query("SELECT * FROM lyrics")
    suspend fun getAll(): List<LyricsEntity>

    @Query("SELECT mediaKey FROM lyrics WHERE mediaKey IN (:mediaKeys) AND content != ''")
    suspend fun getMediaKeysWithLyrics(mediaKeys: List<String>): List<String>

    @Transaction
    suspend fun replaceAll(lyrics: List<LyricsEntity>) {
        deleteAll()
        if (lyrics.isNotEmpty()) insertAll(lyrics)
    }
}
