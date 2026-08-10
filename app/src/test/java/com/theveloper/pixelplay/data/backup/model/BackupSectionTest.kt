package com.theveloper.pixelplay.data.backup.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BackupSectionTest {

    @Test
    fun `all sections have unique keys`() {
        val keys = BackupSection.entries.map { it.key }
        assertEquals(keys.size, keys.distinct().size, "BackupSection keys must be unique")
    }

    @Test
    fun `all sections have unique labels`() {
        val labels = BackupSection.entries.map { it.label }
        assertEquals(labels.size, labels.distinct().size, "BackupSection labels must be unique")
    }

    @Test
    fun `fromKey returns correct section for known keys`() {
        assertEquals(BackupSection.PLAYLISTS, BackupSection.fromKey("playlists"))
        assertEquals(BackupSection.GLOBAL_SETTINGS, BackupSection.fromKey("global_settings"))
        assertEquals(BackupSection.FAVORITES, BackupSection.fromKey("favorites"))
        assertEquals(BackupSection.LYRICS, BackupSection.fromKey("lyrics"))
        assertEquals(BackupSection.TRANSITIONS, BackupSection.fromKey("transitions"))
        assertEquals(BackupSection.ENGAGEMENT_STATS, BackupSection.fromKey("engagement_stats"))
        assertEquals(BackupSection.PLAYBACK_HISTORY, BackupSection.fromKey("playback_history"))
        assertEquals(BackupSection.ARTIST_IMAGES, BackupSection.fromKey("artist_images"))
        assertEquals(BackupSection.EQUALIZER, BackupSection.fromKey("equalizer"))
        assertEquals(BackupSection.AI_USAGE_LOGS, BackupSection.fromKey("ai_usage_logs"))
        assertEquals(BackupSection.AUDIO_SOURCE_PLUGINS, BackupSection.fromKey("audio_source_plugins"))
        assertEquals(BackupSection.SENSITIVE_DATA, BackupSection.fromKey("sensitive_data"))
    }

    @Test
    fun `fromKey returns null for unknown key`() {
        assertNull(BackupSection.fromKey("nonexistent"))
        assertNull(BackupSection.fromKey(""))
    }

    @Test
    fun `defaultSelection excludes sensitive data`() {
        assertEquals(
            BackupSection.entries.filterNot { it == BackupSection.SENSITIVE_DATA }.toSet(),
            BackupSection.defaultSelection
        )
    }

    @Test
    fun `there are exactly 12 backup sections`() {
        assertEquals(12, BackupSection.entries.size)
    }

    @Test
    fun `new sections have sinceVersion 3`() {
        assertEquals(3, BackupSection.ARTIST_IMAGES.sinceVersion)
        assertEquals(3, BackupSection.EQUALIZER.sinceVersion)
    }

    @Test
    fun `backup additions have sinceVersion 4`() {
        assertEquals(4, BackupSection.AI_USAGE_LOGS.sinceVersion)
        assertEquals(4, BackupSection.AUDIO_SOURCE_PLUGINS.sinceVersion)
        assertEquals(4, BackupSection.SENSITIVE_DATA.sinceVersion)
    }

    @Test
    fun `original sections have sinceVersion 1`() {
        val originalSections = listOf(
            BackupSection.PLAYLISTS,
            BackupSection.GLOBAL_SETTINGS,
            BackupSection.FAVORITES,
            BackupSection.LYRICS,
            BackupSection.TRANSITIONS,
            BackupSection.ENGAGEMENT_STATS,
            BackupSection.PLAYBACK_HISTORY
        )
        originalSections.forEach { section ->
            assertEquals(1, section.sinceVersion, "${section.label} should have sinceVersion 1")
        }
    }

    @Test
    fun `all sections have non-empty labels and descriptions`() {
        BackupSection.entries.forEach { section ->
            assertTrue(section.label.isNotBlank(), "${section.key} should have a non-empty label")
            assertTrue(section.description.isNotBlank(), "${section.key} should have a non-empty description")
        }
    }
}
