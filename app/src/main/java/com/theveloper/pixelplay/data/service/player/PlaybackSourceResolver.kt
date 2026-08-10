package com.theveloper.pixelplay.data.service.player

import android.net.Uri
import com.theveloper.pixelplay.data.download.DownloadRepository
import com.theveloper.pixelplay.data.plugin.PluginAudioSourceResolver
import com.theveloper.pixelplay.data.plugin.PluginResolveException
import com.theveloper.pixelplay.data.plugin.PluginSource
import com.theveloper.pixelplay.data.stream.CloudStreamPlaybackInfoStore
import com.theveloper.pixelplay.data.stream.CloudStreamSecurity
import com.theveloper.pixelplay.data.stream.OnlineAudioCacheManager
import com.theveloper.pixelplay.data.stream.ResolvedStream
import com.theveloper.pixelplay.utils.MediaSourceClassifier
import com.theveloper.pixelplay.utils.PlaybackTrace
import com.theveloper.pixelplay.utils.PlaybackTraceContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

data class PlaybackSourceRequest(
    val uri: Uri,
    val traceContext: PlaybackTraceContext? = null
)

data class PlaybackSourceResolution(
    val originalUri: Uri,
    val canonicalUri: Uri,
    val resolvedUri: Uri,
    val requestHeaders: Map<String, String> = emptyMap(),
    val cacheKey: String? = null,
    val source: String? = null,
    val quality: String? = null,
    val mimeType: String? = null,
    val cacheHit: Boolean
) {
    val isResolved: Boolean
        get() = !MediaSourceClassifier.requiresPlaybackResolution(resolvedUri.toString())
}

/** Stable playback boundary for custom online media URIs. */
interface PlaybackSourceGateway {
    suspend fun resolve(request: PlaybackSourceRequest): PlaybackSourceResolution
    fun canonicalize(uri: Uri): Uri
    fun invalidate(uri: Uri): Boolean
}

/**
 * Recognizes proxy URLs persisted by older app builds and converts them back to
 * their canonical source URI. New playback never creates a loopback URL.
 */
internal fun canonicalLegacyProxySource(
    scheme: String?,
    host: String?,
    pathSegments: List<String>
): String? {
    if (scheme != "http" && scheme != "https") return null
    if (host?.lowercase() !in LEGACY_PROXY_HOSTS) return null
    if (pathSegments.size < 2) return null

    val route = pathSegments[0]
    val id = pathSegments[1]
    return when (route) {
        "netease" -> id.toLongOrNull()?.let { "netease://$it" }
        "qqmusic", "kugou", "kuwo" -> id.takeIf(String::isNotBlank)?.let { "$route://$it" }
        else -> null
    }
}

private val LEGACY_PROXY_HOSTS = setOf("127.0.0.1", "localhost", "0.0.0.0", "::1")

@Singleton
class PlaybackSourceResolver @Inject constructor(
    private val pluginResolver: PluginAudioSourceResolver,
    private val downloadRepository: DownloadRepository,
    private val onlineAudioCacheManager: OnlineAudioCacheManager,
    private val playbackInfoStore: CloudStreamPlaybackInfoStore
) : PlaybackSourceGateway {
    private data class CachedResolvedStream(
        val stream: ResolvedStream,
        val cachedAtMs: Long
    ) {
        fun isExpired(nowMs: Long): Boolean =
            stream.expireAt?.let { normalizeEpochMillis(it) <= nowMs } == true ||
                nowMs - cachedAtMs >= RESOLVED_STREAM_TTL_MS
    }

    private val resolvedStreams = ConcurrentHashMap<String, CachedResolvedStream>()
    private val resolveLocks = ConcurrentHashMap<String, Mutex>()
    private val canonicalToResolvedCacheKey = ConcurrentHashMap<String, String>()

    override suspend fun resolve(request: PlaybackSourceRequest): PlaybackSourceResolution {
        val canonicalUri = canonicalize(request.uri)
        val canonicalString = canonicalUri.toString()
        val source = MediaSourceClassifier.pluginSource(canonicalString)
            ?: return unresolved(request, canonicalUri)
        val platformSongId = MediaSourceClassifier.idPart(canonicalString, source)
            ?.takeIf { validatePlatformId(source, it) }
            ?: return unresolved(request, canonicalUri)

        downloadRepository.getCompletedAudio(canonicalString)?.publicUri
            ?.takeIf { it.isNotBlank() }
            ?.let(Uri::parse)
            ?.let { downloadedUri ->
                logResolution("download_hit", request, canonicalUri, downloadedUri)
                return PlaybackSourceResolution(
                    originalUri = request.uri,
                    canonicalUri = canonicalUri,
                    resolvedUri = downloadedUri,
                    source = source.lxSource,
                    cacheHit = true
                )
            }

        val initialCacheKey = pluginResolver.cacheKey(source, platformSongId)
        onlineAudioCacheManager.get(initialCacheKey)?.let { cached ->
            val cachedUri = Uri.fromFile(cached.file)
            logResolution("audio_cache_hit", request, canonicalUri, cachedUri)
            return PlaybackSourceResolution(
                originalUri = request.uri,
                canonicalUri = canonicalUri,
                resolvedUri = cachedUri,
                cacheKey = initialCacheKey,
                source = source.lxSource,
                quality = cached.quality,
                mimeType = cached.mimeType,
                cacheHit = true
            )
        }

        val stream = resolveRemote(
            canonicalUri = canonicalUri,
            source = source,
            platformSongId = platformSongId,
            initialCacheKey = initialCacheKey,
            traceContext = request.traceContext
        )
        val finalCacheKey = stream.cacheKeySuffix ?: initialCacheKey
        canonicalToResolvedCacheKey[canonicalString] = finalCacheKey

        onlineAudioCacheManager.get(finalCacheKey)?.let { cached ->
            val cachedUri = Uri.fromFile(cached.file)
            logResolution("audio_cache_hit_after_resolve", request, canonicalUri, cachedUri)
            return PlaybackSourceResolution(
                originalUri = request.uri,
                canonicalUri = canonicalUri,
                resolvedUri = cachedUri,
                cacheKey = finalCacheKey,
                source = stream.source ?: source.lxSource,
                quality = cached.quality ?: stream.quality,
                mimeType = cached.mimeType,
                cacheHit = true
            )
        }

        if (!CloudStreamSecurity.isSafeRemoteStreamUrl(
                url = stream.url,
                allowHttpWithoutHostAllowlist = true
            )) {
            throw PluginResolveException.InvalidAudioSource("插件返回了不安全的播放地址")
        }

        val resolvedUri = Uri.parse(stream.url)
        logResolution("resolved", request, canonicalUri, resolvedUri)
        return PlaybackSourceResolution(
            originalUri = request.uri,
            canonicalUri = canonicalUri,
            resolvedUri = resolvedUri,
            requestHeaders = stream.headers,
            cacheKey = finalCacheKey,
            source = stream.source ?: source.lxSource,
            quality = stream.quality,
            mimeType = stream.mimeType,
            cacheHit = false
        )
    }

    override fun canonicalize(uri: Uri): Uri =
        canonicalLegacyProxySource(uri.scheme, uri.host, uri.pathSegments)?.let(Uri::parse) ?: uri

    override fun invalidate(uri: Uri): Boolean {
        val canonical = canonicalize(uri).toString()
        val source = MediaSourceClassifier.pluginSource(canonical) ?: return false
        val id = MediaSourceClassifier.idPart(canonical, source) ?: return false
        val initialKey = pluginResolver.cacheKey(source, id)
        val finalKey = canonicalToResolvedCacheKey.remove(canonical)
        var removed = resolvedStreams.remove(initialKey) != null
        if (finalKey != null) removed = resolvedStreams.remove(finalKey) != null || removed
        return removed
    }

    private suspend fun resolveRemote(
        canonicalUri: Uri,
        source: PluginSource,
        platformSongId: String,
        initialCacheKey: String,
        traceContext: PlaybackTraceContext?
    ): ResolvedStream {
        val lock = resolveLocks.getOrPut(initialCacheKey) { Mutex() }
        return lock.withLock {
            val now = System.currentTimeMillis()
            resolvedStreams[initialCacheKey]
                ?.takeUnless { it.isExpired(now) }
                ?.stream
                ?.also { playbackInfoStore.update(canonicalUri.toString(), it) }
                ?: pluginResolver.resolve(
                    source = source,
                    platformSongId = platformSongId,
                    traceContext = traceContext
                ).also { stream ->
                    val cached = CachedResolvedStream(stream, now)
                    resolvedStreams[initialCacheKey] = cached
                    stream.cacheKeySuffix?.let { resolvedStreams[it] = cached }
                    playbackInfoStore.update(canonicalUri.toString(), stream)
                }
        }
    }

    private fun unresolved(
        request: PlaybackSourceRequest,
        canonicalUri: Uri
    ): PlaybackSourceResolution = PlaybackSourceResolution(
        originalUri = request.uri,
        canonicalUri = canonicalUri,
        resolvedUri = canonicalUri,
        cacheHit = false
    )

    private fun validatePlatformId(source: PluginSource, id: String): Boolean = when (source) {
        PluginSource.NETEASE -> id.toLongOrNull()?.let(CloudStreamSecurity::validateNeteaseSongId) == true
        PluginSource.QQ_MUSIC -> CloudStreamSecurity.validateQqMusicSongMid(id)
        PluginSource.KUGOU -> CloudStreamSecurity.validateKugouHash(id)
        PluginSource.KUWO -> CloudStreamSecurity.validateKuwoSongId(id)
    }

    private fun logResolution(
        stage: String,
        request: PlaybackSourceRequest,
        canonicalUri: Uri,
        resolvedUri: Uri
    ) {
        Timber.tag(PlaybackTrace.LOG_TAG).d(
            "stage=%s requestId=%s mediaId=%s source=%s target=%s",
            stage,
            request.traceContext?.requestId,
            request.traceContext?.mediaId,
            canonicalUri.scheme,
            resolvedUri.scheme
        )
    }

    private companion object {
        const val RESOLVED_STREAM_TTL_MS = 10L * 60L * 1000L

        fun normalizeEpochMillis(value: Long): Long =
            if (value in 1..9_999_999_999L) value * 1_000L else value
    }
}
