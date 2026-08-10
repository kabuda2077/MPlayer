package com.theveloper.pixelplay.data.model

import androidx.compose.runtime.Immutable
import com.theveloper.pixelplay.data.plugin.PluginSource

enum class OnlineSearchPlatform(
    val source: PluginSource,
    val label: String
) {
    NETEASE(PluginSource.NETEASE, "网易云"),
    QQ(PluginSource.QQ_MUSIC, "QQ"),
    KUGOU(PluginSource.KUGOU, "酷狗"),
    KUWO(PluginSource.KUWO, "酷我")
}

@Immutable
data class OnlineSearchResultItem(
    val transientSong: TransientOnlineSong,
    val platform: OnlineSearchPlatform
) {
    val song: Song
        get() = transientSong.song

    constructor(song: Song, platform: OnlineSearchPlatform) : this(
        transientSong = TransientOnlineSong.from(song, platform),
        platform = platform
    )
}
