package com.theveloper.pixelplay.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName

@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    @SerializedName("id")
    val id: Long = 0,
    @ColumnInfo(name = "query")
    @SerializedName("query")
    val query: String,
    @ColumnInfo(name = "timestamp")
    @SerializedName("timestamp")
    val timestamp: Long
)
