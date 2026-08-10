package com.theveloper.pixelplay.data.plugin

import com.google.common.truth.Truth.assertThat
import okhttp3.Request
import org.junit.jupiter.api.Test

class PluginStreamRequestHeadersTest {
    @Test
    fun addsLxUserAgentWhenPluginDoesNotProvideOne() {
        val request = PluginStreamRequestHeaders.applyTo(
            builder = Request.Builder().url("https://cdn.example.com/audio.mp3"),
            headers = mapOf("Referer" to "https://example.com/")
        ).build()

        assertThat(request.header("User-Agent")).isEqualTo(PluginStreamRequestHeaders.DEFAULT_USER_AGENT)
        assertThat(request.header("Referer")).isEqualTo("https://example.com/")
    }

    @Test
    fun preservesPluginUserAgent() {
        val request = PluginStreamRequestHeaders.applyTo(
            builder = Request.Builder().url("https://cdn.example.com/audio.mp3"),
            headers = mapOf("user-agent" to "PluginAgent/2.0")
        ).build()

        assertThat(request.header("User-Agent")).isEqualTo("PluginAgent/2.0")
    }
}
