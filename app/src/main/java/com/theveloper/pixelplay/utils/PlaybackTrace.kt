package com.theveloper.pixelplay.utils

import android.os.Bundle
import androidx.media3.common.MediaItem
import java.util.UUID

data class PlaybackTraceContext(
    val requestId: String,
    val mediaId: String? = null,
    val sourceUri: String? = null,
    val origin: PlaybackTraceOrigin = PlaybackTraceOrigin.UNKNOWN
) {
    init {
        require(requestId.isNotBlank()) { "Playback trace requestId must not be blank" }
    }
}

enum class PlaybackTraceOrigin {
    UI,
    RESTORE,
    PRELOAD,
    DATA_SOURCE,
    DOWNLOAD,
    LYRICS,
    UNKNOWN;

    companion object {
        fun fromWireValue(value: String?): PlaybackTraceOrigin = entries.firstOrNull {
            it.name.equals(value, ignoreCase = true)
        } ?: UNKNOWN
    }
}

object PlaybackTrace {
    const val LOG_TAG = "PlaybackTrace"
    const val EXTRA_REQUEST_ID = "com.theveloper.pixelplay.playback.REQUEST_ID"
    fun newRequestId(): String = UUID.randomUUID().toString()

    fun requestId(mediaItem: MediaItem?): String? = mediaItem
        ?.mediaMetadata
        ?.extras
        ?.getString(EXTRA_REQUEST_ID)
        ?.takeIf { it.isNotBlank() }

    fun context(
        mediaItem: MediaItem?,
        origin: PlaybackTraceOrigin = PlaybackTraceOrigin.UNKNOWN
    ): PlaybackTraceContext? {
        val requestId = requestId(mediaItem) ?: return null
        return PlaybackTraceContext(
            requestId = requestId,
            mediaId = mediaItem?.mediaId,
            sourceUri = mediaItem?.localConfiguration?.uri?.toString(),
            origin = origin
        )
    }

    fun newContext(
        mediaId: String? = null,
        sourceUri: String? = null,
        origin: PlaybackTraceOrigin = PlaybackTraceOrigin.UNKNOWN
    ): PlaybackTraceContext = PlaybackTraceContext(
        requestId = newRequestId(),
        mediaId = mediaId,
        sourceUri = sourceUri,
        origin = origin
    )

    fun ensureRequestId(mediaItem: MediaItem): MediaItem {
        if (requestId(mediaItem) != null) return mediaItem
        return withRequestId(mediaItem, newRequestId())
    }

    fun withRequestId(mediaItem: MediaItem, requestId: String): MediaItem {
        val normalizedRequestId = requestId.takeIf { it.isNotBlank() } ?: newRequestId()
        val extras = Bundle(mediaItem.mediaMetadata.extras ?: Bundle()).apply {
            putString(EXTRA_REQUEST_ID, normalizedRequestId)
        }
        return mediaItem.buildUpon()
            .setMediaMetadata(
                mediaItem.mediaMetadata.buildUpon()
                    .setExtras(extras)
                    .build()
            )
            .build()
    }
}
