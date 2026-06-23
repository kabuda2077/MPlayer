package com.theveloper.pixelplay.data.qqmusic

import android.net.Uri
import com.theveloper.pixelplay.data.plugin.PluginAudioSourceResolver
import com.theveloper.pixelplay.data.plugin.PluginSource
import com.theveloper.pixelplay.data.stream.CloudStreamPlaybackInfoStore
import com.theveloper.pixelplay.data.stream.CloudStreamProxy
import com.theveloper.pixelplay.data.stream.CloudStreamSecurity
import com.theveloper.pixelplay.data.stream.ResolvedStream
import okhttp3.OkHttpClient
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class QqMusicStreamProxy @Inject constructor(
    private val pluginResolver: PluginAudioSourceResolver,
    private val playbackInfoStore: CloudStreamPlaybackInfoStore,
    okHttpClient: OkHttpClient
) : CloudStreamProxy<String>(okHttpClient) {

    override val allowedHostSuffixes = emptySet<String>()
    override val cacheExpirationMs = 10L * 60 * 1000
    override val allowHttpWithoutHostAllowlist = true
    override val proxyTag = "QqMusicStreamProxy"
    override val routePath = "/qqmusic/{songMid}"
    override val routeParamName = "songMid"
    override val uriScheme = "qqmusic"
    override val routePrefix = "/qqmusic"

    override fun parseRouteParam(value: String): String? =
        value.takeIf { it.isNotBlank() }

    override fun validateId(id: String): Boolean =
        CloudStreamSecurity.validateQqMusicSongMid(id)

    override fun formatIdForUrl(id: String): String = id

    override fun cacheKeyFor(id: String): String =
        pluginResolver.cacheKey(PluginSource.QQ_MUSIC, id)

    override suspend fun resolveStream(id: String): ResolvedStream =
        pluginResolver.resolve(
            source = PluginSource.QQ_MUSIC,
            platformSongId = id
        )

    override fun onStreamAvailable(id: String, stream: ResolvedStream) {
        playbackInfoStore.update("qqmusic://$id", stream)
    }

    // QQ Music URIs may use host or path: qqmusic://songMid or qqmusic:///songMid
    override fun extractIdFromUri(uri: Uri): String? =
        uri.host ?: uri.path?.removePrefix("/")

    fun resolveQqMusicUri(uriString: String): String? = resolveUri(uriString)

    /**
     * Pre-fetches and caches the real stream URL for a song so the proxy can
     * serve it instantly when ExoPlayer makes its HTTP request.
     */
    suspend fun warmUpStreamUrl(uriString: String) {
        val uri = Uri.parse(uriString)
        if (uri.scheme != "qqmusic") return
        val songMid = uri.host ?: uri.path?.removePrefix("/") ?: return
        if (!CloudStreamSecurity.validateQqMusicSongMid(songMid)) return
        try {
            getOrFetchStreamUrl(songMid)
        } catch (e: Exception) {
            Timber.w(e, "warmUpStreamUrl failed for $songMid")
        }
    }
}
