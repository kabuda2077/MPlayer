package com.theveloper.pixelplay.data.stream

data class ResolvedStream(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val expireAt: Long? = null,
    val isTrial: Boolean = false,
    val quality: String? = null,
    val mimeType: String? = null,
    val cacheKeySuffix: String? = null
) {
    companion object {
        fun fromUrl(url: String): ResolvedStream = ResolvedStream(url = url)
    }
}
