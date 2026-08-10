package com.theveloper.pixelplay.data.service.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackSourceResolverTest {

    @Test
    fun canonicalLegacyProxySource_restoresPluginSourceUri() {
        val resolved = canonicalLegacyProxySource(
            scheme = "http",
            host = "127.0.0.1",
            pathSegments = listOf("qqmusic", "003abc")
        )

        assertThat(resolved).isEqualTo("qqmusic://003abc")
    }

    @Test
    fun canonicalLegacyProxySource_rejectsInvalidNeteaseId() {
        val resolved = canonicalLegacyProxySource(
            scheme = "http",
            host = "localhost",
            pathSegments = listOf("netease", "not-a-number")
        )

        assertThat(resolved).isNull()
    }

    @Test
    fun canonicalLegacyProxySource_doesNotRewriteRemoteServers() {
        val resolved = canonicalLegacyProxySource(
            scheme = "https",
            host = "music.example.com",
            pathSegments = listOf("kuwo", "123")
        )

        assertThat(resolved).isNull()
    }
}
