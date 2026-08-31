package com.theveloper.pixelplay.data.service

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class MusicServiceSecurityTest {
    @Test
    fun `privileged commands require the app package or a trusted controller`() {
        assertThat(canUsePrivilegedSessionCommands("com.example.other", "com.example.player", false))
            .isFalse()
        assertThat(canUsePrivilegedSessionCommands("com.example.player", "com.example.player", false))
            .isTrue()
        assertThat(canUsePrivilegedSessionCommands("com.android.systemui", "com.example.player", true))
            .isTrue()
    }
}
