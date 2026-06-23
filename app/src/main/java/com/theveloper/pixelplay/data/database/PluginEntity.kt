package com.theveloper.pixelplay.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "audio_source_plugins",
    indices = [
        Index(value = ["enabled"]),
        Index(value = ["updated_at"])
    ]
)
data class PluginEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val author: String,
    val version: String,
    val homepage: String,
    val enabled: Boolean,
    val script: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)
