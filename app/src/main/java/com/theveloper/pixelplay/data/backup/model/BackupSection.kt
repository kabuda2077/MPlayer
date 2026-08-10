package com.theveloper.pixelplay.data.backup.model

import com.theveloper.pixelplay.R

enum class BackupSection(
    val key: String,
    val label: String,
    val description: String,
    val iconRes: Int,
    val sinceVersion: Int = 1
) {
    PLAYLISTS(
        key = "playlists",
        label = "Playlists",
        description = "Your custom playlists and ordering preferences.",
        iconRes = R.drawable.rounded_playlist_play_24
    ),
    GLOBAL_SETTINGS(
        key = "global_settings",
        label = "Global Settings",
        description = "Themes, behavior, playback, and app preferences.",
        iconRes = R.drawable.rounded_settings_24
    ),
    FAVORITES(
        key = "favorites",
        label = "Favorites",
        description = "Songs marked as favorite.",
        iconRes = R.drawable.rounded_favorite_24
    ),
    LYRICS(
        key = "lyrics",
        label = "Saved Lyrics",
        description = "Lyrics you've saved or imported.",
        iconRes = R.drawable.rounded_lyrics_24
    ),
    TRANSITIONS(
        key = "transitions",
        label = "Transition Rules",
        description = "Custom transition settings between songs.",
        iconRes = R.drawable.rounded_align_justify_space_even_24
    ),
    ENGAGEMENT_STATS(
        key = "engagement_stats",
        label = "Engagement Stats",
        description = "Play count and listening duration per song.",
        iconRes = R.drawable.rounded_monitoring_24
    ),
    PLAYBACK_HISTORY(
        key = "playback_history",
        label = "Playback History",
        description = "Timeline-based listening history for stats.",
        iconRes = R.drawable.rounded_schedule_24
    ),
    ARTIST_IMAGES(
        key = "artist_images",
        label = "Artist Images",
        description = "Cached artist image URLs from Deezer.",
        iconRes = R.drawable.rounded_person_24,
        sinceVersion = 3
    ),
    EQUALIZER(
        key = "equalizer",
        label = "Equalizer",
        description = "Your custom equalizer presets and audio profiles.",
        iconRes = R.drawable.rounded_surround_sound_24,
        sinceVersion = 3
    ),
    AI_USAGE_LOGS(
        key = "ai_usage_logs",
        label = "AI Activity Logs",
        description = "History of AI requests and token consumption.",
        iconRes = R.drawable.rounded_monitoring_24,
        sinceVersion = 4
    ),
    AUDIO_SOURCE_PLUGINS(
        key = "audio_source_plugins",
        label = "Audio Source Plugins",
        description = "Installed audio source plugin scripts and enabled state.",
        iconRes = R.drawable.rounded_audio_file_24,
        sinceVersion = 4
    ),
    SENSITIVE_DATA(
        key = "sensitive_data",
        label = "Sensitive Data",
        description = "NetEase/QQ login cookies and AI provider API settings. Backup files are not encrypted.",
        iconRes = R.drawable.rounded_key_vertical_24,
        sinceVersion = 4
    );

    companion object {
        val defaultSelection: Set<BackupSection> = entries
            .filterNot { it == SENSITIVE_DATA }
            .toSet()

        fun fromKey(key: String): BackupSection? = entries.find { it.key == key }
    }
}
