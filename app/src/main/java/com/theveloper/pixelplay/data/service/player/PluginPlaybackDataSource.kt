package com.theveloper.pixelplay.data.service.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.theveloper.pixelplay.data.plugin.PluginResolveException
import com.theveloper.pixelplay.data.plugin.PluginStreamRequestHeaders
import com.theveloper.pixelplay.data.stream.OnlineAudioCacheManager
import com.theveloper.pixelplay.utils.MediaSourceClassifier
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import timber.log.Timber

/**
 * Resolves plugin URIs directly into Media3 data sources. It keeps the custom URI
 * as the playback identity while the active delegate reads a downloaded file,
 * the private full-file cache, or the resolved CDN URL with plugin headers.
 */
class PluginPlaybackDataSource internal constructor(
    private val upstreamFactory: DataSource.Factory,
    private val sourceResolver: PlaybackSourceGateway,
    private val onlineAudioCacheManager: OnlineAudioCacheManager,
    private val playbackWorkBudget: PlaybackWorkBudget
) : DataSource {
    private val transferListeners = mutableListOf<TransferListener>()
    private var delegate: DataSource? = null
    private var canonicalSourceUri: Uri? = null
    private var cacheWriter: OnlineAudioCacheManager.CacheWriter? = null
    private var cacheLease: PlaybackWorkBudget.Lease? = null
    private var reachedEnd = false

    override fun addTransferListener(transferListener: TransferListener) {
        transferListeners += transferListener
        delegate?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        closeCurrentDelegate(abortCache = true)
        canonicalSourceUri = sourceResolver.canonicalize(dataSpec.uri)
        return openResolved(dataSpec, allowRefresh = true)
    }

    private fun openResolved(dataSpec: DataSpec, allowRefresh: Boolean): Long {
        val canonical = canonicalSourceUri ?: dataSpec.uri
        val needsResolution = MediaSourceClassifier.requiresPlaybackResolution(canonical.toString())
        val resolution = if (needsResolution) {
            try {
                runBlocking(Dispatchers.IO) {
                    sourceResolver.resolve(PlaybackSourceRequest(canonical))
                }
            } catch (error: IOException) {
                throw error
            } catch (error: PluginResolveException) {
                throw IOException(error.message, error)
            } catch (error: Exception) {
                throw IOException("Unable to resolve online playback source", error)
            }
        } else {
            PlaybackSourceResolution(
                originalUri = dataSpec.uri,
                canonicalUri = canonical,
                resolvedUri = dataSpec.uri,
                cacheHit = false
            )
        }

        if (needsResolution && !resolution.isResolved) {
            throw IOException("No playable audio source resolved for $canonical")
        }

        val requestHeaders = linkedMapOf<String, String>().apply {
            putAll(dataSpec.httpRequestHeaders)
            resolution.requestHeaders.forEach { (name, value) ->
                if (!name.equals("Range", ignoreCase = true)) put(name, value)
            }
            if (keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                put("User-Agent", PluginStreamRequestHeaders.DEFAULT_USER_AGENT)
            }
        }
        val resolvedSpec = dataSpec.buildUpon()
            .setUri(resolution.resolvedUri)
            .setHttpRequestHeaders(requestHeaders)
            .build()

        val activeDelegate = upstreamFactory.createDataSource().also { dataSource ->
            transferListeners.forEach(dataSource::addTransferListener)
            delegate = dataSource
        }

        return try {
            val length = activeDelegate.open(resolvedSpec)
            beginCacheWriteIfEligible(dataSpec, resolution, length)
            length
        } catch (error: IOException) {
            activeDelegate.closeQuietly()
            delegate = null
            val responseCode = (error as? HttpDataSource.InvalidResponseCodeException)?.responseCode
            if (allowRefresh && responseCode in STALE_SOURCE_CODES && needsResolution) {
                Timber.w("Direct stream URL became stale (HTTP %s), resolving once more", responseCode)
                sourceResolver.invalidate(canonical)
                openResolved(dataSpec, allowRefresh = false)
            } else {
                throw error
            }
        }
    }

    private fun beginCacheWriteIfEligible(
        originalSpec: DataSpec,
        resolution: PlaybackSourceResolution,
        resolvedLength: Long
    ) {
        val cacheKey = resolution.cacheKey ?: return
        if (resolution.cacheHit || originalSpec.position != 0L) return
        if (originalSpec.length != C.LENGTH_UNSET.toLong() || resolvedLength <= 0L) return

        val lease = playbackWorkBudget.tryAcquire(PlaybackWorkSlot.CACHE_FILL, cacheKey) ?: return
        val writer = onlineAudioCacheManager.createWriter(
            cacheKey = cacheKey,
            contentLength = resolvedLength,
            mimeType = resolution.mimeType ?: "application/octet-stream",
            source = resolution.source.orEmpty(),
            quality = resolution.quality
        )
        if (writer == null) {
            lease.close()
            return
        }
        cacheLease = lease
        cacheWriter = writer
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val activeDelegate = delegate ?: throw IOException("Data source is not open")
        val bytesRead = activeDelegate.read(buffer, offset, length)
        if (bytesRead > 0) {
            val lease = cacheLease
            val writer = cacheWriter
            if (lease?.isValid == true && writer != null) {
                writer.write(buffer, offset, bytesRead)
            } else if (writer != null) {
                abortCacheWrite()
            }
        } else if (bytesRead == C.RESULT_END_OF_INPUT) {
            reachedEnd = true
            cacheWriter?.commit()
            cacheWriter = null
            cacheLease?.close()
            cacheLease = null
        }
        return bytesRead
    }

    override fun getUri(): Uri? = delegate?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        delegate?.responseHeaders ?: emptyMap()

    override fun close() {
        closeCurrentDelegate(abortCache = !reachedEnd)
        canonicalSourceUri = null
        reachedEnd = false
    }

    private fun closeCurrentDelegate(abortCache: Boolean) {
        delegate?.closeQuietly()
        delegate = null
        if (abortCache) abortCacheWrite()
    }

    private fun abortCacheWrite() {
        cacheWriter?.abort()
        cacheWriter = null
        cacheLease?.close()
        cacheLease = null
    }

    class Factory(
        context: Context,
        okHttpClient: OkHttpClient,
        private val sourceResolver: PlaybackSourceGateway,
        private val onlineAudioCacheManager: OnlineAudioCacheManager,
        private val playbackWorkBudget: PlaybackWorkBudget
    ) : DataSource.Factory {
        private val upstreamFactory = DefaultDataSource.Factory(
            context,
            OkHttpDataSource.Factory(okHttpClient)
        )

        override fun createDataSource(): DataSource = PluginPlaybackDataSource(
            upstreamFactory = upstreamFactory,
            sourceResolver = sourceResolver,
            onlineAudioCacheManager = onlineAudioCacheManager,
            playbackWorkBudget = playbackWorkBudget
        )
    }

    private companion object {
        val STALE_SOURCE_CODES = setOf(401, 403, 404, 410)
    }
}

private fun DataSource.closeQuietly() {
    runCatching { close() }
}
