package com.theveloper.pixelplay.utils

import android.provider.MediaStore

/**
 * Builds the baseline MediaStore selection for user-facing local audio.
 *
 * We intentionally do not rely on [MediaStore.Audio.Media.IS_MUSIC] here because some devices
 * and scanners leave valid songs flagged as non-music, which makes library sync and folder
 * browsing appear to "cap out" below the real file count for specific users.
 *
 * Some Android MediaStore implementations index valid FLAC/MP3/etc. entries with null/zero
 * duration. Those entries are allowed through the first query and their real duration is recovered
 * during sync before they are inserted into the app database.
 */
fun buildLocalAudioSelection(minDurationMs: Int): Pair<String, Array<String>> {
    val clampedMinDurationMs = minDurationMs.coerceAtLeast(0)
    val selection = buildString {
        append("(")
        append("${MediaStore.Audio.Media.DURATION} >= ?")
        append(" OR ${MediaStore.Audio.Media.DURATION} IS NULL")
        append(" OR ${MediaStore.Audio.Media.DURATION} <= 0")
        append(")")
        append(" AND COALESCE(${MediaStore.Audio.Media.TITLE}, '') != ''")
        append(" AND ${MediaStore.Audio.Media.DATA} IS NOT NULL")
    }
    return selection to arrayOf(clampedMinDurationMs.toString())
}
