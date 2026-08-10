package com.theveloper.pixelplay.data.model

data class LyricsDocument(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val lyrics: Lyrics,
    val rawContent: String,
    val sourceTag: String,
    val capabilities: LyricsDocumentCapabilities = LyricsDocumentCapabilities.from(lyrics)
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

data class LyricsDocumentCapabilities(
    val hasWordLevel: Boolean,
    val hasTranslation: Boolean,
    val hasRomanization: Boolean
) {
    companion object {
        fun from(lyrics: Lyrics): LyricsDocumentCapabilities = LyricsDocumentCapabilities(
            hasWordLevel = lyrics.synced.orEmpty().any { !it.words.isNullOrEmpty() },
            hasTranslation = lyrics.synced.orEmpty().any { !it.translation.isNullOrBlank() },
            hasRomanization = lyrics.synced.orEmpty().any { !it.romanization.isNullOrBlank() }
        )
    }
}
