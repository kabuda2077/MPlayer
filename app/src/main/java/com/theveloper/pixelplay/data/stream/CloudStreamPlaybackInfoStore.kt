package com.theveloper.pixelplay.data.stream

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class CloudStreamPlaybackInfo(
    val contentUriString: String,
    val quality: String?,
    val source: String?,
    val mimeType: String?,
    val updatedAt: Long = System.currentTimeMillis()
)

@Singleton
class CloudStreamPlaybackInfoStore @Inject constructor() {
    private val _infos = MutableStateFlow<Map<String, CloudStreamPlaybackInfo>>(emptyMap())
    val infos: StateFlow<Map<String, CloudStreamPlaybackInfo>> = _infos.asStateFlow()

    fun update(contentUriString: String, stream: ResolvedStream) {
        val normalizedUri = contentUriString.trim().takeIf { it.isNotBlank() } ?: return
        val info = CloudStreamPlaybackInfo(
            contentUriString = normalizedUri,
            quality = stream.quality?.takeIf { it.isNotBlank() },
            source = stream.source?.takeIf { it.isNotBlank() },
            mimeType = stream.mimeType?.takeIf { it.isNotBlank() }
        )
        _infos.update { current ->
            (current + (normalizedUri to info))
                .entries
                .sortedByDescending { it.value.updatedAt }
                .take(MAX_ENTRIES)
                .associate { it.key to it.value }
        }
    }

    fun infoFor(contentUriString: String?): CloudStreamPlaybackInfo? {
        val normalizedUri = contentUriString?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return _infos.value[normalizedUri]
    }

    companion object {
        private const val MAX_ENTRIES = 128
    }
}
