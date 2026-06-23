package com.theveloper.pixelplay.data.plugin

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PluginAudioSourceValidatorTest {
    @Test
    fun acceptsHttpsPublicUrlAndHeaders() {
        val result = PluginAudioResolveResult(
            url = "https://cdn.example.com/a.mp3",
            headers = mapOf("Referer" to "https://example.com"),
            expireAt = System.currentTimeMillis() + 60_000
        )

        assertThat(PluginAudioSourceValidator.validate(result)).isEqualTo(result)
    }

    @Test
    fun rejectsNonHttpUrl() {
        assertThrows(PluginResolveException.InvalidAudioSource::class.java) {
            PluginAudioSourceValidator.validateUrl("file:///sdcard/a.mp3")
        }
    }

    @Test
    fun rejectsLocalhostAndPrivateIp() {
        assertThrows(PluginResolveException.InvalidAudioSource::class.java) {
            PluginAudioSourceValidator.validateUrl("http://127.0.0.1/a.mp3")
        }
        assertThrows(PluginResolveException.InvalidAudioSource::class.java) {
            PluginAudioSourceValidator.validateUrl("http://192.168.1.2/a.mp3")
        }
    }

    @Test
    fun rejectsExpiredResult() {
        val result = PluginAudioResolveResult(
            url = "https://cdn.example.com/a.mp3",
            expireAt = 1000L
        )

        assertThrows(PluginResolveException.InvalidAudioSource::class.java) {
            PluginAudioSourceValidator.validate(result, now = 2000L)
        }
    }

    @Test
    fun rejectsInvalidHeaders() {
        assertThrows(PluginResolveException.InvalidAudioSource::class.java) {
            PluginAudioSourceValidator.validateHeaders(mapOf("Host" to "evil.example"))
        }
    }
}
