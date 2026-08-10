package com.theveloper.pixelplay.data.service.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.stream.OnlineAudioCacheManager
import java.io.IOException
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PluginPlaybackDataSourceTest {

    @Test
    fun directResolutionPreservesSeekRangeAndAppliesPluginHeaders() {
        val gateway = RecordingGateway(
            resolution = resolution(
                canonical = "qqmusic://MID123456",
                target = "https://cdn.example.com/audio.flac",
                headers = mapOf("Referer" to "https://y.qq.com/")
            )
        )
        val upstream = RecordingDataSourceFactory()
        val source = createDataSource(upstream, gateway)
        val request = DataSpec.Builder()
            .setUri("qqmusic://MID123456")
            .setPosition(8_192L)
            .setLength(4_096L)
            .build()

        source.open(request)

        val delegated = upstream.openedSpecs.single()
        assertThat(delegated.uri).isEqualTo(Uri.parse("https://cdn.example.com/audio.flac"))
        assertThat(delegated.position).isEqualTo(8_192L)
        assertThat(delegated.length).isEqualTo(4_096L)
        assertThat(delegated.httpRequestHeaders["Referer"]).isEqualTo("https://y.qq.com/")
        assertThat(delegated.httpRequestHeaders.keys.any { it.equals("User-Agent", true) }).isTrue()
        source.close()
    }

    @Test
    fun downloadedOrCachedResolutionUsesLocalTargetWithoutAnotherNetworkLayer() {
        val localUri = Uri.parse("content://media/external/audio/media/42")
        val gateway = RecordingGateway(
            resolution = PlaybackSourceResolution(
                originalUri = Uri.parse("netease://42"),
                canonicalUri = Uri.parse("netease://42"),
                resolvedUri = localUri,
                cacheHit = true
            )
        )
        val upstream = RecordingDataSourceFactory()
        val source = createDataSource(upstream, gateway)

        source.open(DataSpec(Uri.parse("netease://42")))

        assertThat(upstream.openedSpecs.single().uri).isEqualTo(localUri)
        assertThat(gateway.resolveCount).isEqualTo(1)
        source.close()
    }

    @Test
    fun staleCdnResponseInvalidatesAndResolvesExactlyOnce() {
        val staleSpec = DataSpec(Uri.parse("https://cdn.example.com/stale.mp3"))
        val stale = HttpDataSource.InvalidResponseCodeException(
            404,
            "Not Found",
            null,
            emptyMap(),
            staleSpec,
            byteArrayOf()
        )
        val gateway = RecordingGateway(
            resolution = resolution(
                canonical = "kugou://0123456789ABCDEF",
                target = "https://cdn.example.com/fresh.mp3"
            )
        )
        val upstream = RecordingDataSourceFactory(firstOpenFailure = stale)
        val source = createDataSource(upstream, gateway)

        source.open(DataSpec(Uri.parse("kugou://0123456789ABCDEF")))

        assertThat(gateway.resolveCount).isEqualTo(2)
        assertThat(gateway.invalidateCount).isEqualTo(1)
        assertThat(upstream.createCount).isEqualTo(2)
        source.close()
    }

    private fun createDataSource(
        upstream: DataSource.Factory,
        gateway: PlaybackSourceGateway
    ): PluginPlaybackDataSource {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cache = OnlineAudioCacheManager(context).apply { configuredCacheLimitMb = 0 }
        return PluginPlaybackDataSource(upstream, gateway, cache, PlaybackWorkBudget())
    }

    private fun resolution(
        canonical: String,
        target: String,
        headers: Map<String, String> = emptyMap()
    ) = PlaybackSourceResolution(
        originalUri = Uri.parse(canonical),
        canonicalUri = Uri.parse(canonical),
        resolvedUri = Uri.parse(target),
        requestHeaders = headers,
        cacheKey = "cache-key",
        source = "tx",
        mimeType = "audio/mpeg",
        cacheHit = false
    )

    private class RecordingGateway(
        var resolution: PlaybackSourceResolution
    ) : PlaybackSourceGateway {
        var resolveCount = 0
        var invalidateCount = 0

        override suspend fun resolve(request: PlaybackSourceRequest): PlaybackSourceResolution {
            resolveCount++
            return resolution.copy(originalUri = request.uri, canonicalUri = request.uri)
        }

        override fun canonicalize(uri: Uri): Uri = uri

        override fun invalidate(uri: Uri): Boolean {
            invalidateCount++
            return true
        }
    }

    private class RecordingDataSourceFactory(
        private val firstOpenFailure: IOException? = null
    ) : DataSource.Factory {
        val openedSpecs = mutableListOf<DataSpec>()
        var createCount = 0

        override fun createDataSource(): DataSource {
            createCount++
            val failure = if (createCount == 1) firstOpenFailure else null
            return object : DataSource {
                override fun addTransferListener(transferListener: TransferListener) = Unit

                override fun open(dataSpec: DataSpec): Long {
                    openedSpecs += dataSpec
                    failure?.let { throw it }
                    return if (dataSpec.length == C.LENGTH_UNSET.toLong()) 16_384L else dataSpec.length
                }

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    C.RESULT_END_OF_INPUT

                override fun getUri(): Uri? = openedSpecs.lastOrNull()?.uri

                override fun close() = Unit
            }
        }
    }
}
