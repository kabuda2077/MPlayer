package com.theveloper.pixelplay.data.stream

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CloudStreamSecurityTest {

    @Test
    fun `isSafeRemoteStreamUrl blocks localhost`() {
        assertFalse(
            CloudStreamSecurity.isSafeRemoteStreamUrl(
                "http://127.0.0.1:8000/audio.mp3",
                allowedHostSuffixes = setOf("127.0.0.1"),
                allowHttpForAllowedHosts = true
            )
        )
    }

    @Test
    fun `isSafeRemoteStreamUrl enforces host allowlist and scheme rules`() {
        assertTrue(
            CloudStreamSecurity.isSafeRemoteStreamUrl(
                "https://m7.music.126.net/file.mp3",
                allowedHostSuffixes = setOf("music.126.net")
            )
        )
        assertTrue(
            CloudStreamSecurity.isSafeRemoteStreamUrl(
                "http://m7.music.126.net/file.mp3",
                allowedHostSuffixes = setOf("music.126.net"),
                allowHttpForAllowedHosts = true
            )
        )
        assertFalse(
            CloudStreamSecurity.isSafeRemoteStreamUrl(
                "http://evil.example.com/file.mp3",
                allowedHostSuffixes = setOf("music.126.net"),
                allowHttpForAllowedHosts = true
            )
        )
    }

    @Test
    fun `isSupportedAudioContentType only accepts audio-safe types`() {
        assertTrue(CloudStreamSecurity.isSupportedAudioContentType("audio/mpeg"))
        assertTrue(CloudStreamSecurity.isSupportedAudioContentType("application/octet-stream"))
        assertFalse(CloudStreamSecurity.isSupportedAudioContentType("text/html"))
    }
}
