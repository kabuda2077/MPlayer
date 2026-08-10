package com.theveloper.pixelplay.presentation.viewmodel

import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.media.AudioMetadataReader
import com.theveloper.pixelplay.data.media.CoverArtUpdate
import com.theveloper.pixelplay.data.media.SongMetadataEditor
import com.theveloper.pixelplay.data.model.Lyrics
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.model.LyricsMediaKey
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.data.repository.LyricsSearchResult
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.data.repository.NoLyricsFoundException
import com.theveloper.pixelplay.utils.LyricsImportSecurity
import com.theveloper.pixelplay.utils.LyricsImportValidationResult
import com.theveloper.pixelplay.utils.ValidatedLyricsImport
import com.theveloper.pixelplay.utils.PlaybackTraceContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Callback interface for lyrics loading results.
 * Used to update StablePlayerState in PlayerViewModel.
 */
interface LyricsLoadCallback {
    fun onLoadingStarted(mediaKey: String)
    fun onLyricsLoaded(mediaKey: String, lyrics: Lyrics?)
}

/**
 * Callbacks supplied by [PlayerViewModel] so the AI-translation flow can reach the AI layer and
 * resolve localized strings without [LyricsStateHolder] depending on AiStateHolder or a Context.
 * Mirrors the callback-lambda pattern used elsewhere (e.g. [LyricsStateHolder.fetchLyricsForSong]).
 *
 * @param translate Delegates the raw lyrics to the AI translator (AiStateHolder.translateLyrics).
 * @param getString Resolves a no-arg string resource.
 * @param getErrorString Resolves the generic AI error string (R.string.ai_error_generic) with a detail.
 */
class LyricsTranslationCallbacks(
    val translate: suspend (String) -> Result<String>,
    val getString: (Int) -> String,
    val getErrorString: (String) -> String
)

/**
 * Manages lyrics loading, search state, and sync offset.
 * Extracted from PlayerViewModel to improve modularity.
 */
@Singleton
class LyricsStateHolder @Inject constructor(
    private val musicRepository: MusicRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val songMetadataEditor: SongMetadataEditor
) {
    private var scope: CoroutineScope? = null
    private var loadingJob: Job? = null
    private var loadCallback: LyricsLoadCallback? = null

    // Sync offset per song in milliseconds
    private val _currentSongSyncOffset = MutableStateFlow(0)
    val currentSongSyncOffset: StateFlow<Int> = _currentSongSyncOffset.asStateFlow()

    // Lyrics search UI state
    private val _searchUiState = MutableStateFlow<LyricsSearchUiState>(LyricsSearchUiState.Idle)
    val searchUiState: StateFlow<LyricsSearchUiState> = _searchUiState.asStateFlow()

    // Event to notify ViewModel of song updates (e.g. lyrics added)
    private val _songUpdates = kotlinx.coroutines.flow.MutableSharedFlow<Pair<Song, Lyrics?>>(
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val songUpdates = _songUpdates.asSharedFlow()

    // Event for Toasts
    private val _messageEvents = kotlinx.coroutines.flow.MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val messageEvents = _messageEvents.asSharedFlow()

    /**
     * Initialize with coroutine scope and callback from ViewModel.
     */
    fun initialize(
        coroutineScope: CoroutineScope,
        callback: LyricsLoadCallback,
        stablePlayerState: StateFlow<com.theveloper.pixelplay.presentation.viewmodel.StablePlayerState>
    ) {
        scope = coroutineScope
        loadCallback = callback

        coroutineScope.launch {
            stablePlayerState
                .map { state -> state.currentSong?.let(LyricsMediaKey::fromSong)?.value }
                .distinctUntilChanged()
                .collect { mediaKey ->
                    if (mediaKey != null) {
                        updateSyncOffsetForMedia(mediaKey)
                    } else {
                        _currentSongSyncOffset.value = 0
                    }
                }
        }
    }

    /**
     * Load lyrics for a song.
     * @param song The song to load lyrics for
     */
    fun loadLyricsForSong(song: Song, traceContext: PlaybackTraceContext? = null) {
        loadingJob?.cancel()
        val targetMediaKey = LyricsMediaKey.fromSong(song).value

        loadingJob = scope?.launch {
            loadCallback?.onLoadingStarted(targetMediaKey)

            val fetchedLyrics = try {
                withContext(Dispatchers.IO) {
                    musicRepository.getLyrics(song = song, traceContext = traceContext)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            }

            loadCallback?.onLyricsLoaded(targetMediaKey, fetchedLyrics)
        }
    }

    /**
     * Cancel any ongoing lyrics loading.
     */
    fun cancelLoading() {
        loadingJob?.cancel()
    }

    /**
     * Set sync offset for a song.
     */
    fun setSyncOffset(song: Song, offsetMs: Int) {
        val mediaKey = LyricsMediaKey.fromSong(song).value
        scope?.launch {
            userPreferencesRepository.setLyricsSyncOffset(mediaKey, offsetMs)
            _currentSongSyncOffset.value = offsetMs
        }
    }

    /**
     * Update sync offset from song ID (called when song changes).
     */
    suspend fun updateSyncOffsetForMedia(mediaKey: String) {
        val offset = userPreferencesRepository.getLyricsSyncOffset(mediaKey)
        _currentSongSyncOffset.value = offset
    }

    /**
     * Set the lyrics search UI state.
     */
    fun setSearchState(state: LyricsSearchUiState) {
        _searchUiState.value = state
    }

    /**
     * Reset the lyrics search state to idle.
     */
    fun resetSearchState() {
        _searchUiState.value = LyricsSearchUiState.Idle
    }

    /**
     * Fetch lyrics for the given song using the repository's fixed source strategy.
     */
    fun fetchLyricsForSong(
        song: Song,
        forcePickResults: Boolean,
        contextHelper: (Int) -> String
    ) {
        loadingJob?.cancel()
        loadingJob = scope?.launch {
            _searchUiState.value = LyricsSearchUiState.Loading

            if (forcePickResults) {
                musicRepository.searchRemoteLyrics(song)
                    .onSuccess { (query, results) ->
                        _searchUiState.value = LyricsSearchUiState.PickResult(query, results)
                    }
                    .onFailure { error ->
                        handleError(error)
                    }
            } else {
                musicRepository.getLyricsFromRemote(song)
                    .onSuccess { (lyrics, rawLyrics) ->
                        _searchUiState.value = LyricsSearchUiState.Success(lyrics)
                        val refreshedAlbumArtUri = persistLyricsToFileMetadataIfPossible(song, rawLyrics)
                        val updatedSong = song.withPersistedLyrics(rawLyrics, refreshedAlbumArtUri)
                        _songUpdates.emit(updatedSong to lyrics)
                    }
                    .onFailure { error ->
                        if (error is NoLyricsFoundException) {
                            // Fallback to search
                            musicRepository.searchRemoteLyrics(song)
                                .onSuccess { (query, results) ->
                                    _searchUiState.value = LyricsSearchUiState.PickResult(query, results)
                                }
                                .onFailure { searchError -> handleError(searchError) }
                        } else {
                            handleError(error)
                        }
                    }
            }
        }
    }

    /**
     * Manual search by query.
     */
    fun searchLyricsManually(title: String, artist: String?) {
        if (title.isBlank()) return
        loadingJob?.cancel()
        loadingJob = scope?.launch {
            _searchUiState.value = LyricsSearchUiState.Loading
            musicRepository.searchRemoteLyricsByQuery(title, artist)
                .onSuccess { (q, results) ->
                    _searchUiState.value = LyricsSearchUiState.PickResult(q, results)
                }
                .onFailure { error -> handleError(error) }
        }
    }

    /**
     * Accept a search result.
     */
    fun acceptLyricsSearchResult(result: LyricsSearchResult, currentSong: Song) {
        scope?.launch {
            _searchUiState.value = LyricsSearchUiState.Success(result.lyrics)

            // 1. Update DB cache
            musicRepository.updateLyrics(currentSong, result.rawLyrics)

            // 2. Attempt metadata write-back to the audio file
            val refreshedAlbumArtUri = persistLyricsToFileMetadataIfPossible(currentSong, result.rawLyrics)
            val updatedSong = currentSong.withPersistedLyrics(result.rawLyrics, refreshedAlbumArtUri)

            // 3. Notify
            _songUpdates.emit(updatedSong to result.lyrics)
        }
    }

    /**
     * Import from file.
     */
    fun importLyricsFromFile(currentSong: Song, validatedImport: ValidatedLyricsImport) {
        scope?.launch {
            val sanitizedContent = validatedImport.sanitizedContent
            val parsedLyrics = validatedImport.parsedLyrics

            musicRepository.updateLyrics(currentSong, sanitizedContent)

            val refreshedAlbumArtUri = persistLyricsToFileMetadataIfPossible(currentSong, sanitizedContent)
            val updatedSong = currentSong.withPersistedLyrics(sanitizedContent, refreshedAlbumArtUri)
            _songUpdates.emit(updatedSong to parsedLyrics.takeIf(::hasValidLyrics))

            _messageEvents.emit("Lyrics imported successfully!")
        }
    }

    /**
     * Translate the current song's lyrics via AI and import the result.
     * The actual inference is delegated through [LyricsTranslationCallbacks.translate] so this holder
     * stays decoupled from the AI layer. Toasts are surfaced through [messageEvents] as usual.
     */
    fun translateLyricsViaAi(currentSong: Song, lyricsObj: Lyrics?, cb: LyricsTranslationCallbacks) {
        if (lyricsObj?.synced != null) {
            val hasValidTranslation = lyricsObj.synced.any { !it.translation.isNullOrBlank() }
            if (hasValidTranslation) {
                _messageEvents.tryEmit(cb.getString(R.string.lyrics_translate_already_translated))
                return
            }
        }

        scope?.launch {
            _messageEvents.emit(cb.getString(R.string.lyrics_translate_progress))

            val rawLyrics = withContext(Dispatchers.IO) {
                musicRepository.getStoredLyrics(currentSong)?.second
                    ?: readLocalLyricsFile(currentSong)
                    ?: readEmbeddedLyricsFromFile(currentSong)
            }

            if (rawLyrics.isNullOrBlank()) {
                _messageEvents.emit(cb.getString(R.string.lyrics_not_found))
                return@launch
            }

            val result = cb.translate(rawLyrics)
            result.onSuccess { translatedText ->
                if (translatedText.trim() == "ALREADY_IN_TARGET_LANGUAGE") {
                    _messageEvents.emit(cb.getString(R.string.lyrics_translate_already_in_target_language))
                    return@onSuccess
                }

                if (translatedText.isNotBlank()) {
                    val validation = LyricsImportSecurity.validateImportedLrcContent(translatedText)
                    if (validation is LyricsImportValidationResult.Valid) {
                        importLyricsFromFile(currentSong, validation.value)
                        _messageEvents.emit(cb.getString(R.string.lyrics_translate_success))
                    } else {
                        val reason = (validation as LyricsImportValidationResult.Invalid).reason
                        val errorMsg = LyricsImportSecurity.messageFor(reason)
                        _messageEvents.emit(cb.getErrorString(errorMsg))
                    }
                } else {
                    _messageEvents.emit(cb.getErrorString("Empty response"))
                }
            }.onFailure {
                if (it.message?.contains("key", ignoreCase = true) == true ||
                    it.message?.contains("config", ignoreCase = true) == true
                ) {
                    _messageEvents.emit(cb.getString(R.string.ai_state_error_api_key))
                } else {
                    _messageEvents.emit(cb.getErrorString(it.message ?: ""))
                }
            }
        }
    }

    fun resetLyrics(song: Song) {
        resetSearchState()
        scope?.launch {
            musicRepository.resetLyrics(song)
            _songUpdates.emit(song.copy(lyrics = null) to null)
        }
    }

    fun resetAllLyrics() {
        resetSearchState()
        scope?.launch {
            musicRepository.resetAllLyrics()
        }
    }

    private fun handleError(error: Throwable) {
        _searchUiState.value = if (error is NoLyricsFoundException) {
            LyricsSearchUiState.NotFound("Lyrics not found")
        } else {
            LyricsSearchUiState.Error(error.message ?: "Unknown error")
        }
    }

    private fun hasValidLyrics(lyrics: Lyrics?): Boolean {
        if (lyrics == null) return false
        return !lyrics.synced.isNullOrEmpty() || !lyrics.plain.isNullOrEmpty()
    }

    private fun readEmbeddedLyricsFromFile(song: Song): String? {
        return runCatching {
            AudioMetadataReader.read(File(song.path))
                ?.lyrics
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun readLocalLyricsFile(song: Song): String? {
        return runCatching {
            val songFile = File(song.path)
            val directory = songFile.parentFile ?: return@runCatching null
            for (extension in LyricsImportSecurity.supportedFileExtensions()) {
                val lyricsFile = File(directory, "${songFile.nameWithoutExtension}.$extension")
                if (!lyricsFile.exists() || !lyricsFile.canRead()) continue

                when (val validation = LyricsImportSecurity.validateLocalLyricsFile(lyricsFile)) {
                    is LyricsImportValidationResult.Valid -> return@runCatching validation.value.sanitizedContent
                    is LyricsImportValidationResult.Invalid -> continue
                }
            }
            null
        }.getOrNull()
    }

    private suspend fun persistLyricsToFileMetadataIfPossible(song: Song, rawLyrics: String): String? {
        val songId = song.libraryId ?: return null
        val normalizedLyrics = rawLyrics.trim()
        if (normalizedLyrics.isBlank()) return null

        return withContext(Dispatchers.IO) {
            val existingArtwork = runCatching {
                AudioMetadataReader.read(File(song.path))?.artwork
            }.getOrNull()

            val coverArtUpdate = existingArtwork?.let { artwork ->
                CoverArtUpdate(
                    bytes = artwork.bytes,
                    mimeType = artwork.mimeType ?: "image/jpeg"
                )
            }

            runCatching {
                songMetadataEditor.editSongMetadata(
                    songId = songId,
                    newTitle = song.title,
                    newArtist = song.artist,
                    newAlbum = song.album,
                    newGenre = song.genre ?: "",
                    newLyrics = normalizedLyrics,
                    newTrackNumber = song.trackNumber,
                    newDiscNumber = song.discNumber,
                    coverArtUpdate = coverArtUpdate
                )
            }.getOrNull()?.updatedAlbumArtUri
        }
    }

    fun onCleared() {
        loadingJob?.cancel()
        scope = null
        loadCallback = null
    }
}

internal fun Song.withPersistedLyrics(rawLyrics: String, refreshedAlbumArtUri: String?): Song {
    return copy(
        lyrics = rawLyrics,
        // Lyrics writes can refresh the cached cover-art file path. Carry it forward immediately
        // so the full player doesn't keep rendering a deleted image URI until the next app reload.
        albumArtUriString = refreshedAlbumArtUri ?: albumArtUriString
    )
}
