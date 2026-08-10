package com.theveloper.pixelplay.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface PluginDao {
    @Query("SELECT * FROM audio_source_plugins ORDER BY updated_at DESC")
    fun observePlugins(): Flow<List<PluginEntity>>

    @Query("SELECT * FROM audio_source_plugins ORDER BY updated_at DESC")
    suspend fun getPlugins(): List<PluginEntity>

    @Query("SELECT * FROM audio_source_plugins WHERE id = :id LIMIT 1")
    suspend fun getPlugin(id: String): PluginEntity?

    @Query("SELECT * FROM audio_source_plugins WHERE enabled = 1 LIMIT 1")
    suspend fun getEnabledPlugin(): PluginEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(plugin: PluginEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(plugins: List<PluginEntity>)

    @Query("DELETE FROM audio_source_plugins")
    suspend fun clearAll()

    @Transaction
    suspend fun replaceAll(plugins: List<PluginEntity>) {
        clearAll()
        if (plugins.isNotEmpty()) {
            upsertAll(plugins)
        }
    }

    @Query("UPDATE audio_source_plugins SET enabled = 0, updated_at = :updatedAt")
    suspend fun disableAll(updatedAt: Long)

    @Query("UPDATE audio_source_plugins SET enabled = 1, updated_at = :updatedAt WHERE id = :id")
    suspend fun enable(id: String, updatedAt: Long)

    @Query("UPDATE audio_source_plugins SET enabled = 0, updated_at = :updatedAt WHERE id = :id")
    suspend fun disable(id: String, updatedAt: Long)

    @Query("DELETE FROM audio_source_plugins WHERE id = :id")
    suspend fun delete(id: String)
}
