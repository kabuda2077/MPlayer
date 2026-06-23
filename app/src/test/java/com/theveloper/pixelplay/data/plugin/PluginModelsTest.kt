package com.theveloper.pixelplay.data.plugin

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PluginModelsTest {
    @Test
    fun mapsPixelPlayerUrisToPluginSources() {
        assertThat(PluginSource.fromUri("netease://123")).isEqualTo(PluginSource.NETEASE)
        assertThat(PluginSource.fromUri("qqmusic://abc")).isEqualTo(PluginSource.QQ_MUSIC)
        assertThat(PluginSource.fromUri("file:///music/a.mp3")).isNull()
    }

    @Test
    fun qualityValuesMatchLxProtocol() {
        assertThat(PluginQuality.LOW.lxQuality).isEqualTo("128k")
        assertThat(PluginQuality.HIGH.lxQuality).isEqualTo("320k")
        assertThat(PluginQuality.LOSSLESS.lxQuality).isEqualTo("flac")
        assertThat(PluginQuality.HI_RES.lxQuality).isEqualTo("flac24bit")
        assertThat(PluginQuality.HIRES.lxQuality).isEqualTo("hires")
        assertThat(PluginQuality.ATMOS.lxQuality).isEqualTo("atmos")
        assertThat(PluginQuality.ATMOS_PLUS.lxQuality).isEqualTo("atmos_plus")
        assertThat(PluginQuality.MASTER.lxQuality).isEqualTo("master")
    }

    @Test
    fun normalizesExpandedQualityAliases() {
        assertThat(PluginQuality.fromAlias("sq")).isEqualTo(PluginQuality.LOSSLESS)
        assertThat(PluginQuality.fromAlias("ape")).isEqualTo(PluginQuality.LOSSLESS)
        assertThat(PluginQuality.fromAlias("hi-res")).isEqualTo(PluginQuality.HIRES)
        assertThat(PluginQuality.fromAlias("dolby")).isEqualTo(PluginQuality.ATMOS)
        assertThat(PluginQuality.fromAlias("atmos+")).isEqualTo(PluginQuality.ATMOS_PLUS)
        assertThat(PluginQuality.fromAlias("jymaster")).isEqualTo(PluginQuality.MASTER)
    }

    @Test
    fun capabilityDisplayIncludesExpandedQualitiesInPreferenceOrder() {
        val capability = PluginSourceCapability(
            source = "wy",
            actions = setOf("musicUrl"),
            qualities = setOf("128k", "320k", "flac", "flac24bit", "hires", "atmos", "master")
        )

        assertThat(capability.qualityDisplayText())
            .isEqualTo("Master, Atmos, Hi-Res, 24bit, FLAC, 320k, 128k")
    }
}
