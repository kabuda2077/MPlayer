package com.theveloper.pixelplay.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index
import com.google.gson.annotations.SerializedName

@Entity(tableName = "lyrics", indices = [Index(value = ["songId"])])
data class LyricsEntity(
    @PrimaryKey
    @SerializedName(value = "mediaKey", alternate = ["media_key"])
    val mediaKey: String = "",
    @SerializedName(value = "songId", alternate = ["song_id"])
    val songId: Long? = null,
    @SerializedName("content")
    val content: String,
    @SerializedName(value = "isSynced", alternate = ["is_synced"])
    val isSynced: Boolean = false,
    @SerializedName("source")
    val source: String? = null,
    @SerializedName(value = "schemaVersion", alternate = ["schema_version"])
    val schemaVersion: Int = 0,
    @SerializedName(value = "payloadJson", alternate = ["payload_json"])
    val payloadJson: String? = null,
    @SerializedName(value = "updatedAt", alternate = ["updated_at"])
    val updatedAt: Long = 0L
)
