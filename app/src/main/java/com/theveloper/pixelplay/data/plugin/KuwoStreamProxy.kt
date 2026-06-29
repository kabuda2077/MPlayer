package com.theveloper.pixelplay.data.plugin

import android.content.Context
import android.net.Uri
import com.theveloper.pixelplay.data.download.DownloadRepository
import com.theveloper.pixelplay.data.stream.CloudStreamPlaybackInfoStore
import com.theveloper.pixelplay.data.stream.CloudStreamProxy
import com.theveloper.pixelplay.data.stream.CloudStreamSecurity
import com.theveloper.pixelplay.data.stream.OnlineAudioCacheManager
import com.theveloper.pixelplay.data.stream.ResolvedStream
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.OkHttpClient
import timber.log.Timber

@Singleton
class KuwoStreamProxy @Inject constructor(
    @ApplicationContext context: Context,
    private val pluginResolver: PluginAudioSourceResolver,
    private val playbackInfoStore: CloudStreamPlaybackInfoStore,
    onlineAudioCacheManager: OnlineAudioCacheManager,
    downloadRepository: DownloadRepository,
    okHttpClient: OkHttpClient
) : CloudStreamProxy<String>(okHttpClient, onlineAudioCacheManager, downloadRepository, context.contentResolver) {

    override val allowedHostSuffixes = emptySet<String>()
    override val cacheExpirationMs = 10L * 60 * 1000
    override val allowHttpWithoutHostAllowlist = true
    override val proxyTag = "KuwoStreamProxy"
    override val routePath = "/kuwo/{songId}"
    override val routeParamName = "songId"
    override val uriScheme = "kuwo"
    override val routePrefix = "/kuwo"

    override fun parseRouteParam(value: String): String? =
        value.removePrefix("MUSIC_").takeIf { it.isNotBlank() }

    override fun validateId(id: String): Boolean =
        CloudStreamSecurity.validateKuwoSongId(id)

    override fun formatIdForUrl(id: String): String = id.removePrefix("MUSIC_")

    override fun cacheKeyFor(id: String): String =
        pluginResolver.cacheKey(PluginSource.KUWO, id.removePrefix("MUSIC_"))

    override suspend fun resolveStream(id: String): ResolvedStream =
        pluginResolver.resolve(
            source = PluginSource.KUWO,
            platformSongId = id.removePrefix("MUSIC_")
        )

    override fun onStreamAvailable(id: String, stream: ResolvedStream) {
        playbackInfoStore.update("kuwo://${id.removePrefix("MUSIC_")}", stream)
    }

    override fun extractIdFromUri(uri: Uri): String? =
        uri.host ?: uri.path?.removePrefix("/")

    fun resolveKuwoUri(uriString: String): String? = resolveUri(uriString)

    suspend fun warmUpStreamUrl(uriString: String) {
        val uri = Uri.parse(uriString)
        if (uri.scheme != "kuwo") return
        val id = uri.host ?: uri.path?.removePrefix("/") ?: return
        if (!CloudStreamSecurity.validateKuwoSongId(id)) return
        try {
            getOrFetchStreamUrl(id.removePrefix("MUSIC_"))
        } catch (e: Exception) {
            Timber.w(e, "warmUpStreamUrl failed for Kuwo $id")
        }
    }
}
