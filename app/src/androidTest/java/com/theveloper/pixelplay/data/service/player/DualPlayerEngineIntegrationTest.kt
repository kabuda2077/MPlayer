package com.theveloper.pixelplay.data.service.player

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.model.TransitionSettings
import com.theveloper.pixelplay.data.stream.OnlineAudioCacheManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class DualPlayerEngineIntegrationTest {
    @Test
    fun readyAuxiliaryPlayerBecomesMasterAndPreservesAbsoluteQueuePosition() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = createEngine(context, identityGateway())

        try {
            val first = silentWaveItem("first")
            val second = silentWaveItem("second")
            val originalMaster = withContext(Dispatchers.Main) {
                engine.masterPlayer.also { player ->
                    player.setMediaItems(listOf(first, second), 0, 0L)
                    player.prepare()
                }
            }
            awaitReady(originalMaster)

            withContext(Dispatchers.Main) {
                engine.prepareNext(second)
            }

            val displayedPlayer = CompletableDeferred<Player>()
            val swappedPlayer = CompletableDeferred<Player>()
            val transitionFinished = CompletableDeferred<Unit>()
            withContext(Dispatchers.Main) {
                engine.addTransitionDisplayPlayerListener { player ->
                    displayedPlayer.complete(player)
                }
                engine.addPlayerSwapListener { player ->
                    swappedPlayer.complete(player)
                }
                engine.addTransitionFinishedListener {
                    transitionFinished.complete(Unit)
                }
                engine.performTransition(TransitionSettings(durationMs = 500))
            }

            val displayed = withTimeout(5_000L) { displayedPlayer.await() }
            val swapped = withTimeout(5_000L) { swappedPlayer.await() }
            withTimeout(5_000L) { transitionFinished.await() }

            withContext(Dispatchers.Main) {
                assertThat(swapped).isNotSameInstanceAs(originalMaster)
                assertThat(displayed).isSameInstanceAs(swapped)
                assertThat(engine.masterPlayer).isSameInstanceAs(swapped)
                assertThat(engine.masterPlayer.currentMediaItem?.mediaId).isEqualTo("second")
                assertThat(engine.getCurrentAbsoluteIndex()).isEqualTo(1)
            }
        } finally {
            withContext(Dispatchers.Main) { engine.release() }
        }
    }

    @Test
    fun auxiliarySourceFailureDoesNotSwapMasterPlayer() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = createEngine(context, identityGateway())

        try {
            val first = silentWaveItem("first")
            val missing = MediaItem.Builder()
                .setMediaId("missing")
                .setUri(Uri.fromFile(File(context.cacheDir, "definitely-missing-audio.wav")))
                .build()
            val originalMaster = withContext(Dispatchers.Main) {
                engine.masterPlayer.also { player ->
                    player.setMediaItems(listOf(first, missing), 0, 0L)
                    player.prepare()
                }
            }
            awaitReady(originalMaster)
            withContext(Dispatchers.Main) { engine.prepareNext(missing) }

            val swapped = AtomicBoolean(false)
            val transitionFinished = CompletableDeferred<Unit>()
            withContext(Dispatchers.Main) {
                engine.addPlayerSwapListener { swapped.set(true) }
                engine.addTransitionFinishedListener { transitionFinished.complete(Unit) }
                engine.performTransition(TransitionSettings(durationMs = 500))
            }
            withTimeout(5_000L) { transitionFinished.await() }

            withContext(Dispatchers.Main) {
                assertThat(swapped.get()).isFalse()
                assertThat(engine.masterPlayer).isSameInstanceAs(originalMaster)
                assertThat(engine.masterPlayer.currentMediaItem?.mediaId).isEqualTo("first")
            }
        } finally {
            withContext(Dispatchers.Main) { engine.release() }
        }
    }

    @Test
    fun newerAuxiliaryPrepareCancelsOlderResolutionAndIsTheOnlyItemSwappedIn() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val playableUri = silentWaveItem("resolved").localConfiguration!!.uri
        val gateway = object : PlaybackSourceGateway by identityGateway() {
            override suspend fun resolve(request: PlaybackSourceRequest): PlaybackSourceResolution {
                delay(if (request.uri.host == "slow") 1_000L else 10L)
                return PlaybackSourceResolution(
                    originalUri = request.uri,
                    canonicalUri = request.uri,
                    resolvedUri = playableUri,
                    cacheHit = false
                )
            }
        }
        val engine = createEngine(context, gateway)

        try {
            val first = silentWaveItem("first")
            val slow = MediaItem.Builder().setMediaId("slow").setUri("netease://slow").build()
            val latest = MediaItem.Builder().setMediaId("latest").setUri("qqmusic://latest").build()
            val originalMaster = withContext(Dispatchers.Main) {
                engine.masterPlayer.also { player ->
                    player.setMediaItems(listOf(first, slow, latest), 0, 0L)
                    player.prepare()
                }
            }
            awaitReady(originalMaster)

            val stalePrepare = async(Dispatchers.Main) { engine.prepareNext(slow) }
            delay(100L)
            withContext(Dispatchers.Main) { engine.prepareNext(latest) }
            assertThat(stalePrepare.isCancelled).isTrue()

            val swappedPlayer = CompletableDeferred<Player>()
            val transitionFinished = CompletableDeferred<Unit>()
            withContext(Dispatchers.Main) {
                engine.addPlayerSwapListener { swappedPlayer.complete(it) }
                engine.addTransitionFinishedListener { transitionFinished.complete(Unit) }
                engine.performTransition(TransitionSettings(durationMs = 500))
            }
            withTimeout(5_000L) { swappedPlayer.await() }
            withTimeout(5_000L) { transitionFinished.await() }

            withContext(Dispatchers.Main) {
                assertThat(engine.masterPlayer.currentMediaItem?.mediaId).isEqualTo("latest")
                assertThat(engine.getCurrentAbsoluteIndex()).isEqualTo(2)
            }
        } finally {
            withContext(Dispatchers.Main) { engine.release() }
        }
    }

    private suspend fun awaitReady(player: Player) = withContext(Dispatchers.Main) {
        withTimeout(5_000L) {
            while (player.playbackState != Player.STATE_READY) {
                check(player.playerError == null) { "Player failed to prepare: ${player.playerError}" }
                delay(20L)
            }
        }
    }

    private fun createEngine(context: Context, gateway: PlaybackSourceGateway) = DualPlayerEngine(
        context = context,
        playbackSourceResolver = gateway,
        playbackPrefetchController = PlaybackPrefetchController(PlaybackWorkBudget()),
        onlineAudioCacheManager = OnlineAudioCacheManager(context),
        playbackWorkBudget = PlaybackWorkBudget(),
        okHttpClient = OkHttpClient()
    )

    private fun identityGateway(): PlaybackSourceGateway = object : PlaybackSourceGateway {
        override suspend fun resolve(request: PlaybackSourceRequest) = PlaybackSourceResolution(
            originalUri = request.uri,
            canonicalUri = request.uri,
            resolvedUri = request.uri,
            cacheHit = false
        )

        override fun canonicalize(uri: Uri): Uri = uri
        override fun invalidate(uri: Uri): Boolean = false
    }

    private fun silentWaveItem(mediaId: String): MediaItem {
        val sampleRate = 8_000
        val durationSeconds = 3
        val dataSize = sampleRate * durationSeconds
        val bytes = ByteBuffer.allocate(44 + dataSize)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt(36 + dataSize)
                put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16)
                putShort(1)
                putShort(1)
                putInt(sampleRate)
                putInt(sampleRate)
                putShort(1)
                putShort(8)
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(dataSize)
                repeat(dataSize) { put(0x80.toByte()) }
            }
            .array()
        val dataUri = "data:audio/wav;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(dataUri)
            .build()
    }
}
