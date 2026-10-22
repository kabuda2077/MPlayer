package com.theveloper.pixelplay.data.plugin

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

class PluginScriptDownloaderTest {
    @Test
    fun acceptsHttpLinksAndRemovesSurroundingWhitespaceAndFragment() {
        assertThat(PluginScriptContent.normalizeUrl(" https://example.com/a.js?token=abc#section "))
            .isEqualTo("https://example.com/a.js?token=abc")
        assertThat(PluginScriptContent.normalizeUrl("http://example.com/a.js"))
            .isEqualTo("http://example.com/a.js")
    }

    @Test
    fun rejectsInvalidUrlsAndEmbeddedCredentials() {
        listOf("file:///test.js", "javascript:alert(1)", "not a link", "https://user:pass@example.com/a.js").forEach {
            assertThat(runCatching { PluginScriptContent.normalizeUrl(it) }.isFailure).isTrue()
        }
    }

    @Test
    fun rejectsEmptyResponsesHtmlAndOversizedStreams() {
        listOf("", "  ", "<!DOCTYPE html><html>login</html>").forEach {
            assertThat(runCatching { PluginScriptContent.validate(it) }.isFailure).isTrue()
        }
        val input = ByteArrayInputStream(ByteArray(PluginScriptContent.MAX_BYTES.toInt() + 1) { 65 })
        assertThat(runCatching { PluginScriptContent.read(input) }.isFailure).isTrue()
    }

    @Test
    fun stripsUtf8BomBeforeMetadataParsing() {
        val script = "\uFEFF/**\n * @name Online source\n */\nlx.on('request', () => {});"
        val result = PluginScriptContent.read(ByteArrayInputStream(script.toByteArray()))
        assertThat(PluginMetadataParser.parse(result).name).isEqualTo("Online source")
    }

    @Test
    fun downloaderRequiresSuccessfulStatusAndFetchesFreshText() = runBlocking {
        var status = 200
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertThat(chain.request().header("Cache-Control")).isEqualTo("no-cache")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(status).message("test").body("// script".toResponseBody()).build()
        }.build()
        try {
            val downloader = PluginScriptDownloader(client)
            assertThat(downloader.download("https://example.com/source.js")).isEqualTo("// script")
            status = 404
            assertThat(runCatching { downloader.download("https://example.com/source.js") }.isFailure).isTrue()
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
