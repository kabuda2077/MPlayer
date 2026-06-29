package com.theveloper.pixelplay.data.netease

import android.content.Context
import com.theveloper.pixelplay.data.stream.CloudStreamProxy
import com.theveloper.pixelplay.data.stream.CloudStreamSecurity
import com.theveloper.pixelplay.data.plugin.PluginAudioSourceResolver
import com.theveloper.pixelplay.data.plugin.PluginSource
import com.theveloper.pixelplay.data.stream.CloudStreamPlaybackInfoStore
import com.theveloper.pixelplay.data.stream.ResolvedStream
import com.theveloper.pixelplay.data.stream.OnlineAudioCacheManager
import com.theveloper.pixelplay.data.download.DownloadRepository
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext

/**
 * Local HTTP proxy server for streaming Netease Cloud Music audio.
 *
 * Resolves `netease://{songId}` URIs by fetching temporary streaming URLs
 * from the Netease API and proxying the audio data to ExoPlayer.
 */
@Singleton
class NeteaseStreamProxy @Inject constructor(
    @ApplicationContext context: Context,
    private val pluginResolver: PluginAudioSourceResolver,
    private val playbackInfoStore: CloudStreamPlaybackInfoStore,
    onlineAudioCacheManager: OnlineAudioCacheManager,
    downloadRepository: DownloadRepository,
    okHttpClient: OkHttpClient
) : CloudStreamProxy<Long>(okHttpClient, onlineAudioCacheManager, downloadRepository, context.contentResolver) {

    override val allowedHostSuffixes = emptySet<String>()
    override val cacheExpirationMs = 10L * 60 * 1000
    override val allowHttpWithoutHostAllowlist = true
    override val proxyTag = "NeteaseStreamProxy"
    override val routePath = "/netease/{songId}"
    override val routeParamName = "songId"
    override val uriScheme = "netease"
    override val routePrefix = "/netease"

    override fun parseRouteParam(value: String): Long? = value.toLongOrNull()

    override fun validateId(id: Long): Boolean =
        CloudStreamSecurity.validateNeteaseSongId(id)

    override fun formatIdForUrl(id: Long): String = id.toString()

    override fun cacheKeyFor(id: Long): String =
        pluginResolver.cacheKey(PluginSource.NETEASE, id.toString())

    override suspend fun resolveStream(id: Long): ResolvedStream =
        pluginResolver.resolve(
            source = PluginSource.NETEASE,
            platformSongId = id.toString()
        )

    override fun onStreamAvailable(id: Long, stream: ResolvedStream) {
        playbackInfoStore.update("netease://$id", stream)
    }

    fun resolveNeteaseUri(uriString: String): String? = resolveUri(uriString)
}
