package com.theveloper.pixelplay.data.model

import androidx.compose.runtime.Immutable

@Immutable
enum class SongListViewMode(val storageKey: String) {
    LIST("list"),
    GROUP_BY_ARTIST("group_by_artist"),
    GROUP_BY_ALBUM("group_by_album");

    companion object {
        fun fromStorageKey(rawValue: String?): SongListViewMode {
            return entries.firstOrNull { it.storageKey == rawValue } ?: LIST
        }
    }
}
