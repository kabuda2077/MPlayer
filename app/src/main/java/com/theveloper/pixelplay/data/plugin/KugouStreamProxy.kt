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
class KugouStreamProxy @Inject constructor(
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
    override val proxyTag = "KugouStreamProxy"
    override val routePath = "/kugou/{hash}"
    override val routeParamName = "hash"
    override val uriScheme = "kugou"
    override val routePrefix = "/kugou"

    override fun parseRouteParam(value: String): String? =
        value.takeIf { it.isNotBlank() }

    override fun validateId(id: String): Boolean =
        CloudStreamSecurity.validateKugouHash(id)

    override fun formatIdForUrl(id: String): String = id

    override fun cacheKeyFor(id: String): String =
        pluginResolver.cacheKey(PluginSource.KUGOU, id)

    override suspend fun resolveStream(id: String): ResolvedStream =
        pluginResolver.resolve(
            source = PluginSource.KUGOU,
            platformSongId = id
        )

    override fun onStreamAvailable(id: String, stream: ResolvedStream) {
        playbackInfoStore.update("kugou://$id", stream)
    }

    override fun extractIdFromUri(uri: Uri): String? =
        uri.host ?: uri.path?.removePrefix("/")

    fun resolveKugouUri(uriString: String): String? = resolveUri(uriString)

    suspend fun warmUpStreamUrl(uriString: String) {
        val uri = Uri.parse(uriString)
        if (uri.scheme != "kugou") return
        val hash = uri.host ?: uri.path?.removePrefix("/") ?: return
        if (!CloudStreamSecurity.validateKugouHash(hash)) return
        try {
            getOrFetchStreamUrl(hash)
        } catch (e: Exception) {
            Timber.w(e, "warmUpStreamUrl failed for Kugou $hash")
        }
    }
}
