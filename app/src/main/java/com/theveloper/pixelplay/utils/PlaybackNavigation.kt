package com.theveloper.pixelplay.utils

import androidx.media3.common.C
import androidx.media3.common.Player

fun Player.seekToPreviousItemOrStart() {
    val previousIndex = previousMediaItemIndex
    if (previousIndex != C.INDEX_UNSET) {
        seekToDefaultPosition(previousIndex)
    } else {
        seekTo(0)
    }
}
