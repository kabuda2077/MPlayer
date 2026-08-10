package com.theveloper.pixelplay.data.plugin

import com.google.common.truth.Truth.assertThat
import com.sun.net.httpserver.HttpServer
import com.theveloper.pixelplay.data.stream.ResolvedStream
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PluginStreamVerifierTest {
    private lateinit var server: HttpServer

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { it.start() }
    }

    @AfterEach
    fun stopServer() {
        server.stop(0)
    }

    @Test
    fun acceptsPartialAudioResponseAndUsesProbeRange() = runTest {
        val receivedRange = AtomicReference<String?>()
        server.createContext("/audio") { exchange ->
            receivedRange.set(exchange.requestHeaders.getFirst("Range"))
            exchange.responseHeaders.add("Content-Type", "audio/flac")
            exchange.responseHeaders.add("Content-Range", "bytes 0-0/100")
            exchange.sendResponseHeaders(206, 1)
            exchange.responseBody.use { it.write(byteArrayOf(0)) }
        }

        verifier().verify(ResolvedStream(url = url("/audio")))

        assertThat(receivedRange.get()).isEqualTo("bytes=0-0")
    }

    @Test
    fun rejectsHttpFailureSoResolverCanTryNextCandidate() = runTest {
        server.createContext("/missing") { exchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }

        val failure = runCatching {
            verifier().verify(ResolvedStream(url = url("/missing")))
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(PluginResolveException.InvalidAudioSource::class.java)
        assertThat(failure).hasMessageThat().contains("HTTP 404")
    }

    private fun verifier() = PluginStreamVerifier(OkHttpClient())

    private fun url(path: String): String = "http://127.0.0.1:${server.address.port}$path"
}
