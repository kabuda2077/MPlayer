package com.theveloper.pixelplay.data.plugin

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.utils.MediaSourceClassifier
import com.theveloper.pixelplay.utils.MediaSourceKind
import org.junit.jupiter.api.Test

class PluginModelsTest {
    @Test
    fun mapsPixelPlayerUrisToPluginSources() {
        assertThat(MediaSourceClassifier.pluginSource("netease://123")).isEqualTo(PluginSource.NETEASE)
        assertThat(MediaSourceClassifier.pluginSource("qqmusic://abc")).isEqualTo(PluginSource.QQ_MUSIC)
        assertThat(MediaSourceClassifier.pluginSource("kugou://hash")).isEqualTo(PluginSource.KUGOU)
        assertThat(MediaSourceClassifier.pluginSource("kuwo://123")).isEqualTo(PluginSource.KUWO)
        assertThat(MediaSourceClassifier.pluginSource("file:///music/a.mp3")).isNull()
        assertThat(MediaSourceClassifier.pluginSource("unsupported://123")).isNull()
        assertThat(MediaSourceClassifier.isDownloadable("unsupported://123")).isFalse()
    }

    @Test
    fun unknownCustomSchemeIsNotClassifiedAsLocal() {
        assertThat(MediaSourceClassifier.classify("future-source://123"))
            .isEqualTo(MediaSourceKind.UNKNOWN)
        assertThat(MediaSourceClassifier.isLocal("future-source://123")).isFalse()
        assertThat(MediaSourceClassifier.isLocal("file:///music/a.mp3")).isTrue()
        assertThat(MediaSourceClassifier.isLocal("content://media/external/audio/media/1")).isTrue()
        assertThat(MediaSourceClassifier.requiresPlaybackResolution("qqmusic://mid")).isTrue()
        assertThat(MediaSourceClassifier.requiresPlaybackResolution("unsupported://123")).isFalse()
        assertThat(MediaSourceClassifier.isLibraryPersistable("netease://123")).isTrue()
        assertThat(MediaSourceClassifier.isLibraryPersistable("kugou://hash")).isFalse()
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
