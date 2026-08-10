package com.theveloper.pixelplay.presentation.viewmodel

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.model.PlaybackItem
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.data.service.player.DualPlayerEngine
import com.theveloper.pixelplay.data.service.player.PlaybackFailureClassifier
import com.theveloper.pixelplay.data.service.player.PlaybackFailureKind
import com.theveloper.pixelplay.data.service.player.QueueLoadPlan
import com.theveloper.pixelplay.data.service.player.PlaybackRequestCoordinator
import com.theveloper.pixelplay.data.service.player.PlaybackRequestLane
import com.theveloper.pixelplay.data.service.player.PlaybackRequestToken
import com.theveloper.pixelplay.data.service.player.PlaybackTransportCommand
import com.theveloper.pixelplay.data.worker.SyncManager
import com.theveloper.pixelplay.utils.AppShortcutManager
import com.theveloper.pixelplay.utils.MediaSourceClassifier
import com.theveloper.pixelplay.utils.MediaSourceKind
import com.theveloper.pixelplay.utils.PlaybackTrace
import com.theveloper.pixelplay.utils.MediaItemBuilder
import com.theveloper.pixelplay.utils.QueueUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ViewModelScoped
import javax.inject.Inject
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import timber.log.Timber

private const val SONG_ID_QUERY_CHUNK_SIZE = 900
private val LOCAL_PLAYBACK_SCHEMES = setOf("content", "file", "android.resource")

/**
 * Callbacks supplied by [PlayerViewModel] so the playback dispatch core can reach
 * ViewModel-owned state (the media controller, the UI state, the player sheet,
 * toasts/dialog events, the crossfade transition job, listening stats, and
 * predictive back) without [PlaybackDispatchStateHolder] depending on the
 * ViewModel. Stored once via [PlaybackDispatchStateHolder.initialize], mirroring
 * the pattern used by the other state holders.
 */
class PlaybackDispatchCallbacks(
    val scope: CoroutineScope,
    val getController: () -> MediaController?,
    val getUiState: () -> PlayerUiState,
    val updateUiState: ((PlayerUiState) -> PlayerUiState) -> Unit,
    val showSheet: () -> Unit,
    val collapseSheetState: () -> Unit,
    val showPlayer: () -> Unit,
    val sendToast: (String) -> Unit,
    val emitToast: suspend (String) -> Unit,
    val showNoInternetDialog: () -> Unit,
    val cancelTransitionScheduler: () -> Unit,
    val incrementSongScore: (Song) -> Unit,
    val resetPredictiveBackState: () -> Unit,
)

/**
 * Owns the deep playback dispatch core extracted from [PlayerViewModel]: turning a
 * song selection into a player queue and starting playback. Covers the
 * full-queue (library/favorites) and direct request token machinery, queue-context
 * reuse, hydration, queue-segment batching, external-URI playback, the shuffle-all
 * tile entry point, and the "preparing playback" pill state.
 */
@ViewModelScoped
class PlaybackDispatchStateHolder @Inject constructor(
    private val musicRepository: MusicRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val dualPlayerEngine: DualPlayerEngine,
    private val playbackRequestCoordinator: PlaybackRequestCoordinator,
    private val appShortcutManager: AppShortcutManager,
    private val syncManager: SyncManager,
    private val externalMediaStateHolder: ExternalMediaStateHolder,
    private val playbackStateHolder: PlaybackStateHolder,
    private val queueStateHolder: QueueStateHolder,
    private val libraryStateHolder: LibraryStateHolder,
    private val connectivityStateHolder: ConnectivityStateHolder,
    private val themeStateHolder: ThemeStateHolder,
    @param:ApplicationContext private val context: Context,
) {

    private lateinit var cb: PlaybackDispatchCallbacks

    fun initialize(callbacks: PlaybackDispatchCallbacks) {
        cb = callbacks
    }

    /** Invoked by the ViewModel when the MediaController connects. */
    fun flushPendingPlaybackAction() {
        playbackRequestCoordinator.flushPlayerReadyAction()
    }

    fun onCleared() = Unit

    fun showAndPlaySongFromLibrary(
        song: Song,
        queueName: String = "Library",
        isVoluntaryPlay: Boolean = true
    ) {
        launchLatestFullQueuePlayback(
            song = song,
            queueName = queueName,
            isVoluntaryPlay = isVoluntaryPlay,
            failureMessage = "Failed to build full library queue for songId=%s"
        ) {
            val sortOption = cb.getUiState().currentSongSortOption
            val storageFilter = cb.getUiState().currentStorageFilter
            musicRepository.getSongIdsSorted(sortOption, storageFilter)
        }
    }

    fun showAndPlaySongFromFavorites(
        song: Song,
        queueName: String = "Liked Songs",
        isVoluntaryPlay: Boolean = true
    ) {
        launchLatestFullQueuePlayback(
            song = song,
            queueName = queueName,
            isVoluntaryPlay = isVoluntaryPlay,
            failureMessage = "Failed to build favorites queue for songId=%s"
        ) {
            val sortOption = cb.getUiState().currentFavoriteSortOption
            val storageFilter = cb.getUiState().currentStorageFilter
            musicRepository.getFavoriteSongIdsSorted(sortOption, storageFilter)
        }
    }

    suspend fun getSongsForCurrentLibrarySelection(): List<Song> {
        val sortOption = cb.getUiState().currentSongSortOption
        val storageFilter = cb.getUiState().currentStorageFilter
        val sortedIds = musicRepository.getSongIdsSorted(sortOption, storageFilter)
        return resolvePlaybackQueueFromSortedIds(sortedIds)
    }

    suspend fun getSongsForCurrentFavoriteSelection(): List<Song> {
        val sortOption = cb.getUiState().currentFavoriteSortOption
        val storageFilter = cb.getUiState().currentStorageFilter
        val sortedIds = musicRepository.getFavoriteSongIdsSorted(sortOption, storageFilter)
        return resolvePlaybackQueueFromSortedIds(sortedIds)
    }

    private fun launchLatestFullQueuePlayback(
        song: Song,
        queueName: String,
        isVoluntaryPlay: Boolean,
        failureMessage: String,
        sortedIdsProvider: suspend () -> List<Long>
    ) {
        cancelPendingDirectPlayback()
        playbackRequestCoordinator.launchLatest(cb.scope, PlaybackRequestLane.FULL_QUEUE) {
            try {
                val sortedIds = sortedIdsProvider()
                ensureCurrent()

                val fullQueue = resolvePlaybackQueueFromSortedIds(sortedIds)
                ensureCurrent()

                if (isVoluntaryPlay) cb.incrementSongScore(song)
                internalPlaySongs(
                    songsToPlay = fullQueue.ifEmpty { listOf(song) },
                    startSong = song,
                    queueName = queueName,
                    requestToken = this
                )
                cb.resetPredictiveBackState()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!isCurrent) {
                    return@launchLatest
                }

                Timber.e(error, failureMessage, song.id)
                val fallbackQueue = libraryStateHolder.allSongs.value.takeIf { songs ->
                    songs.isNotEmpty() && songs.any { it.id == song.id }
                } ?: listOf(song)
                if (isVoluntaryPlay) cb.incrementSongScore(song)
                internalPlaySongs(
                    songsToPlay = fallbackQueue,
                    startSong = song,
                    queueName = queueName,
                    requestToken = this
                )
                cb.resetPredictiveBackState()
            }
        }
    }

    fun cancelPendingFullQueuePlayback() {
        playbackRequestCoordinator.cancel(PlaybackRequestLane.FULL_QUEUE)
    }

    private fun cancelPendingDirectPlayback() {
        cancelPendingDirectPlaybackBuild()
    }

    private fun cancelPendingDirectPlaybackBuild() {
        playbackRequestCoordinator.cancel(PlaybackRequestLane.DIRECT)
    }

    private suspend fun resolvePlaybackQueueFromSortedIds(sortedIds: List<Long>): List<Song> {
        if (sortedIds.isEmpty()) return emptyList()

        val orderedIds = sortedIds.map(Long::toString)
        val cachedSongsById = libraryStateHolder.allSongsById.value
        val missingIds = ArrayList<String>()
        val cachedQueue = ArrayList<Song>(orderedIds.size)

        withContext(Dispatchers.Default) {
            orderedIds.forEach { songId ->
                val cachedSong = cachedSongsById[songId]
                if (cachedSong != null) {
                    cachedQueue.add(cachedSong)
                } else {
                    missingIds.add(songId)
                }
            }
        }

        if (missingIds.isEmpty()) {
            return cachedQueue
        }

        val missingSongsById = getSongsByIdsChunked(missingIds).associateBy { it.id }
        return withContext(Dispatchers.Default) {
            val finalQueue = ArrayList<Song>(orderedIds.size)
            orderedIds.forEach { songId ->
                val resolvedSong = cachedSongsById[songId] ?: missingSongsById[songId]
                if (resolvedSong != null) {
                    finalQueue.add(resolvedSong)
                }
            }
            finalQueue
        }
    }

    private suspend fun getSongsByIdsChunked(songIds: List<String>): List<Song> {
        if (songIds.isEmpty()) return emptyList()
        if (songIds.size <= SONG_ID_QUERY_CHUNK_SIZE) {
            return musicRepository.getSongsByIds(songIds).first()
        }

        return withContext(Dispatchers.IO) {
            buildList(songIds.size) {
                songIds.chunked(SONG_ID_QUERY_CHUNK_SIZE).forEach { chunk ->
                    addAll(musicRepository.getSongsByIds(chunk).first())
                }
            }
        }
    }

    fun showAndPlaySong(
        song: Song,
        contextSongs: List<Song>,
        queueName: String = "Current Context",
        isVoluntaryPlay: Boolean = true,
        cancelPendingQueueBuild: Boolean = true,
        playlistId: String? = null,
        indexInQueue: Int? = null
    ) {
        if (cancelPendingQueueBuild) {
            cancelPendingFullQueuePlayback()
        }
        val playbackContext =
            if (contextSongs.any { it.id == song.id }) contextSongs else listOf(song)
        val controller = cb.getController()
        val currentQueue = cb.getUiState().currentPlaybackQueue
        val songIndexInQueue = indexInQueue ?: currentQueue.indexOfFirst { it.id == song.id }
        val queueMatchesContext = currentQueue.matchesSongOrder(playbackContext)
        val reusableTargetIndex = if (
            controller != null &&
            controller.isConnected &&
            !dualPlayerEngine.isTransitionRunning() &&
            songIndexInQueue != -1 &&
            queueMatchesContext
        ) {
            controller.resolveReusablePlaybackTargetIndex(
                songIndexInQueue = songIndexInQueue,
                songId = song.id,
                isExplicitQueueTarget = indexInQueue != null
            )
        } else {
            null
        }

        if (controller != null && reusableTargetIndex != null) {
            cancelPendingDirectPlaybackBuild()
            playLoadedControllerItem(controller, reusableTargetIndex)
            if (isVoluntaryPlay) {
                cb.incrementSongScore(song)
                if (playlistId != null && queueName != "None") {
                    appShortcutManager.updateLastPlaylistShortcut(playlistId, queueName)
                }
            }
        } else {
            if (isVoluntaryPlay) cb.incrementSongScore(song)
            playSongs(playbackContext, song, queueName, playlistId)
        }
        cb.resetPredictiveBackState()
    }

    fun showAndPlaySong(song: Song) {
        Timber.tag("ShuffleDebug").d("showAndPlaySong (single song overload) called for '${song.title}'")
        showAndPlaySong(song, listOf(song), "Library")
    }

    private fun List<Song>.matchesSongOrder(contextSongs: List<Song>): Boolean {
        if (size != contextSongs.size) return false
        return indices.all { this[it].id == contextSongs[it].id }
    }

    private fun MediaController.resolveReusablePlaybackTargetIndex(
        songIndexInQueue: Int,
        songId: String,
        isExplicitQueueTarget: Boolean = false
    ): Int? {
        if (!isExplicitQueueTarget) {
            currentMediaItem?.takeIf { it.mediaId == songId }?.let {
                return currentMediaItemIndex.takeIf { index -> index != C.INDEX_UNSET } ?: 0
            }
        }

        if (songIndexInQueue !in 0 until mediaItemCount) return null

        val mediaIdAtTarget = runCatching { getMediaItemAt(songIndexInQueue).mediaId }.getOrNull()
        return songIndexInQueue.takeIf { mediaIdAtTarget == songId }
    }

    private fun playLoadedControllerItem(controller: MediaController, targetIndex: Int) {
        playbackRequestCoordinator.playLoadedItem(controller, targetIndex)
    }

    fun songRequiresHydration(song: Song): Boolean = song.requiresHydration()

    private fun Song.requiresHydration(): Boolean {
        return contentUriString.isBlank()
    }

    suspend fun hydrateSongsIfNeeded(songs: List<Song>): List<Song> {
        if (songs.isEmpty() || songs.none { it.requiresHydration() }) return songs
        val hydratedSongs = getSongsByIdsChunked(songs.map { it.id })
        if (hydratedSongs.isEmpty()) return songs
        val hydratedById = hydratedSongs.associateBy { it.id }
        return songs.mapNotNull { original ->
            hydratedById[original.id] ?: original.takeIf { !original.requiresHydration() }
        }
    }

    fun playSongs(songsToPlay: List<Song>, startSong: Song, queueName: String = "None", playlistId: String? = null) {
        playbackRequestCoordinator.launchLatest(cb.scope, PlaybackRequestLane.DIRECT) {
            cb.cancelTransitionScheduler()

            val validSongs = hydrateSongsIfNeeded(songsToPlay)
            ensureCurrent()

            if (validSongs.isEmpty()) {
                cb.emitToast(context.getString(R.string.player_view_model_no_valid_songs))
                return@launchLatest
            }

            // Adjust startSong if it was filtered out
            val validStartSong =
                validSongs.firstOrNull { it.id == startSong.id } ?: validSongs.first()

            // Store the original order so we can "unshuffle" later if the user turns shuffle off
            queueStateHolder.setOriginalQueueOrder(validSongs)
            queueStateHolder.saveOriginalQueueState(validSongs, queueName)

            // Check if the user wants shuffle to be persistent across different albums
            val isPersistent = userPreferencesRepository.persistentShuffleEnabledFlow.first()
            ensureCurrent()
            // Check if shuffle is currently active in the player
            val isShuffleOn = playbackStateHolder.stablePlayerState.value.isShuffleEnabled

            // If Persistent Shuffle is OFF, we reset shuffle to "false" every time a new album starts
            if (!isPersistent) {
                playbackStateHolder.setManualShuffleEnabled(false)
            }

            // If shuffle is persistent and currently ON, we shuffle the new songs immediately
            val finalSongsToPlay = if (isPersistent && isShuffleOn) {
                // Shuffle the list but make sure the song you clicked stays at its current index or starts first
                withContext(Dispatchers.Default) {
                    QueueUtils.buildAnchoredShuffleQueueSuspending(
                        validSongs,
                        validSongs.indexOfFirst { it.id == validStartSong.id }.coerceAtLeast(0)
                    )
                }
            } else {
                // Otherwise, just use the normal sequential order
                validSongs
            }
            ensureCurrent()

            // Send the final list (shuffled or not) to the player engine
            internalPlaySongs(finalSongsToPlay, validStartSong, queueName, playlistId, this)
        }
    }

    // Start playback with shuffle enabled in one coroutine to avoid racing queue updates
    fun playSongsShuffled(
        songsToPlay: List<Song>,
        queueName: String = "None",
        playlistId: String? = null,
        startAtZero: Boolean = false
    ) {
        playbackRequestCoordinator.launchLatest(cb.scope, PlaybackRequestLane.DIRECT) {
            val result = queueStateHolder.prepareShuffledQueueSuspending(songsToPlay, queueName, startAtZero)
            ensureCurrent()
            if (result == null) {
                cb.sendToast(context.getString(R.string.player_view_model_no_songs_to_shuffle))
                return@launchLatest
            }

            val (shuffledQueue, startSong) = result
            cb.cancelTransitionScheduler()

            playbackStateHolder.setManualShuffleEnabled(true)
            cb.scope.launch { userPreferencesRepository.setShuffleOn(true) }

            internalPlaySongs(shuffledQueue, startSong, queueName, playlistId, this)
        }
    }

    fun playExternalUri(uri: Uri) {
        playbackRequestCoordinator.launchLatest(cb.scope, PlaybackRequestLane.DIRECT) {
            val externalResult = externalMediaStateHolder.buildExternalSongFromUri(uri)
            if (externalResult == null) {
                cb.sendToast(context.getString(R.string.external_playback_error))
                return@launchLatest
            }
            ensureCurrent()

            cb.cancelTransitionScheduler()

            val queueSongs = externalMediaStateHolder.buildExternalQueue(externalResult, uri)
            val immutableQueue = queueSongs.toPlaybackQueue()

            cb.updateUiState { state ->
                state.copy(
                    currentPlaybackQueue = immutableQueue,
                    currentQueueSourceName = context.getString(R.string.external_queue_label),
                    showDismissUndoBar = false,
                    dismissedSong = null,
                    dismissedQueue = persistentListOf(),
                    dismissedQueueName = "",
                    dismissedPosition = 0L
                )
            }
            cb.collapseSheetState()
            cb.showSheet()

            internalPlaySongs(
                queueSongs,
                externalResult.song,
                context.getString(R.string.external_queue_label),
                requestToken = this
            )
            cb.showPlayer()
        }
    }

    fun triggerShuffleAllFromTile() {
        Timber.d("[TileDebug] triggerShuffleAllFromTile called. mediaController=${cb.getController() != null}")
        val action: () -> Unit = {
            Timber.d("[TileDebug] action() invoked")
            cb.scope.launch {
                var songs = musicRepository.getRandomSongs(limit = 500)
                Timber.d("[TileDebug] Repository returned ${songs.size} random songs immediately")

                if (songs.isEmpty()) {
                    // Cold start or stale DB state: trigger a sync and retry the bounded query.
                    Timber.d("[TileDebug] No songs available yet, triggering sync and retrying repository sample")
                    syncManager.sync()
                    songs = withTimeoutOrNull(30_000L) {
                        var refreshedSongs = emptyList<Song>()
                        while (refreshedSongs.isEmpty()) {
                            refreshedSongs = musicRepository.getRandomSongs(limit = 500)
                            if (refreshedSongs.isEmpty()) {
                                delay(500L)
                            }
                        }
                        refreshedSongs
                    }
                        ?: emptyList()
                    Timber.d("[TileDebug] After retry, repository returned ${songs.size} songs")
                }

                if (songs.isNotEmpty()) {
                    Timber.d("[TileDebug] Calling playSongsShuffled with ${songs.size} songs")
                    playSongsShuffled(songs, "All Songs (Shuffled)", startAtZero = true)
                } else {
                    Timber.w("[TileDebug] No songs found even after sync - library may be empty")
                    cb.sendToast(context.getString(R.string.player_view_model_no_songs_in_library_toast))
                }
            }
        }

        if (cb.getController() == null) {
            Timber.d("[TileDebug] mediaController null, queuing as pendingPlaybackAction")
            playbackRequestCoordinator.deferUntilPlayerReady(action = action)
        } else {
            Timber.d("[TileDebug] mediaController ready, calling action immediately")
            action()
        }
    }

    private fun setPreparingSong(songId: String?) {
        cb.updateUiState { state ->
            if (state.preparingSongId == songId) state else state.copy(preparingSongId = songId)
        }
    }

    private fun beginPreparingSong(song: Song) {
        // Skip the "Preparing playback…" pill for local files: they reach STATE_READY
        // in milliseconds, and transient STATE_BUFFERING from audio HAL/offload init
        // (or a re-tap of an already-loaded song) can otherwise leave the pill stuck.
        // Always write the new value (null for local, song.id for remote) so a stale
        // preparingSongId from a previous remote song cannot outlive a local track switch.
        if (!isLocalPlaybackSong(song)) {
            setPreparingSong(song.id)
        } else {
            setPreparingSong(null)
        }
        cb.scope.launch(Dispatchers.IO) {
            val albumArtUri = song.albumArtUriString
            if (albumArtUri.isNullOrBlank()) {
                themeStateHolder.extractAndGenerateColorScheme(
                    albumArtUriAsUri = null,
                    currentSongUriString = null,
                    isPreload = false
                )
            } else {
                themeStateHolder.extractAndGenerateColorScheme(
                    albumArtUriAsUri = albumArtUri.toUri(),
                    currentSongUriString = albumArtUri,
                    isPreload = false
                )
            }
        }
    }

    private fun isLocalPlaybackSong(song: Song): Boolean {
        val scheme = MediaItemBuilder.playbackUri(song).scheme?.lowercase()
        return scheme == null || scheme in LOCAL_PLAYBACK_SCHEMES
    }

    fun clearPreparingSongIfMatching(mediaId: String? = null) {
        val preparingSongId = cb.getUiState().preparingSongId ?: return
        if (mediaId == null || preparingSongId == mediaId) {
            setPreparingSong(null)
        }
    }

    private suspend fun preparePlaybackQueueSegments(
        songsToPlay: List<Song>,
        startSongId: String,
        playlistId: String?,
        playbackRequestId: String
    ): QueueLoadPlan<MediaItem>? = withContext(Dispatchers.Default) {
        val currentIndex = songsToPlay
            .indexOfFirst { it.id == startSongId }
            .takeIf { it >= 0 }
            ?: 0
        QueueLoadPlan.create(
            items = songsToPlay.map {
                buildPlaybackMediaItem(it, playlistId, playbackRequestId)
            },
            preferredIndex = currentIndex
        )
    }

    private suspend fun attachPreparedQueueSegmentsIfCurrent(
        player: Player,
        startSongId: String,
        preparedSegments: QueueLoadPlan<MediaItem>,
        requestToken: PlaybackRequestToken
    ) {
        requestToken.ensureCurrent()
        if (player.currentMediaItem?.mediaId != startSongId) return
        if (player.mediaItemCount != 1) return
        if (player.getMediaItemAt(0).mediaId != startSongId) return

        if (preparedSegments.beforeCurrent.isNotEmpty()) {
            var insertedCount = 0
            preparedSegments.beforeBatches().forEach { batch ->
                requestToken.ensureCurrent()
                playbackRequestCoordinator.insertMediaItems(player, insertedCount, batch)
                insertedCount += batch.size
                yield()
            }
        }

        if (preparedSegments.afterCurrent.isNotEmpty()) {
            var insertedCount = 0
            preparedSegments.afterBatches().forEach { batch ->
                requestToken.ensureCurrent()
                playbackRequestCoordinator.insertMediaItems(
                    player,
                    preparedSegments.beforeCurrent.size + 1 + insertedCount,
                    batch
                )
                insertedCount += batch.size
                yield()
            }
        }

    }

    suspend fun internalPlaySongs(
        songsToPlay: List<Song>,
        startSong: Song,
        queueName: String = "None",
        playlistId: String? = null,
        requestToken: PlaybackRequestToken
    ) {
        if (songsToPlay.isEmpty()) {
            clearPreparingSongIfMatching()
            return
        }
        val effectiveStartSong = songsToPlay.firstOrNull { it.id == startSong.id } ?: songsToPlay.first()

        // Update dynamic shortcut for last played playlist
        if (playlistId != null && queueName != "None") {
            appShortcutManager.updateLastPlaylistShortcut(playlistId, queueName)
        }

        beginPreparingSong(effectiveStartSong)
            val startMediaItem = buildResolvedPlaybackMediaItem(
                effectiveStartSong,
                requestToken.requestId
            ) ?: return

            cb.updateUiState {
                it.copy(
                    currentPlaybackQueue = songsToPlay.toPlaybackQueue(),
                    currentQueueSourceName = queueName
                )
            }
            cb.showSheet()

            val playSongsAction = {
                // Use Direct Engine Access to avoid TransactionTooLargeException on Binder
                dualPlayerEngine.cancelNext()
                val enginePlayer = dualPlayerEngine.masterPlayer

                playbackRequestCoordinator.replaceCurrentAndPlay(enginePlayer, startMediaItem)
                cb.updateUiState { it.copy(isLoadingInitialSongs = false) }

                if (songsToPlay.size > 1) {
                    val attachQueue: suspend PlaybackRequestToken.() -> Unit = attach@{
                        ensureCurrent()
                        val preparedSegments = preparePlaybackQueueSegments(
                            songsToPlay = songsToPlay,
                            startSongId = effectiveStartSong.id,
                            playlistId = playlistId,
                            playbackRequestId = requestToken.requestId
                        ) ?: return@attach
                        withContext(Dispatchers.Main.immediate) {
                            attachPreparedQueueSegmentsIfCurrent(
                                player = dualPlayerEngine.masterPlayer,
                                startSongId = effectiveStartSong.id,
                                preparedSegments = preparedSegments,
                                requestToken = this@attach
                            )
                        }
                    }
                    playbackRequestCoordinator.launchChild(cb.scope, requestToken) {
                        attachQueue()
                    }
                }
            }

            // We still check for mediaController to ensure the Service is bound and active
            // even though we aren't using it for the heavy lifting anymore.
            if (cb.getController() == null) {
                Timber.w("MediaController not available. Queuing playback action.")
                playbackRequestCoordinator.deferUntilPlayerReady(requestToken, playSongsAction)
            } else {
                playSongsAction()
            }
    }

    suspend fun buildResolvedPlaybackMediaItem(
        song: Song,
        playbackRequestId: String = PlaybackTrace.newRequestId()
    ): MediaItem? {
        val mediaItem = MediaItemBuilder.build(
            PlaybackItem.fromCompatibleSong(song),
            playbackRequestId
        )
        val originalUri = mediaItem.localConfiguration?.uri ?: return mediaItem
        val requestId = PlaybackTrace.requestId(mediaItem)
        if (!MediaSourceClassifier.requiresPlaybackResolution(originalUri.toString())) {
            return mediaItem
        }

        Timber.tag(PlaybackTrace.LOG_TAG).d(
            "stage=resolve_requested requestId=%s mediaId=%s source=%s",
            requestId,
            mediaItem.mediaId,
            originalUri.scheme
        )

        return try {
            dualPlayerEngine.warmPlaybackSource(
                uri = originalUri,
                playbackRequestId = requestId,
                mediaId = mediaItem.mediaId
            )
            mediaItem
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            clearPreparingSongIfMatching(song.id)
            val failureKind = PlaybackFailureClassifier.classifySourcePreparationFailure(error)
            val message = if (failureKind == PlaybackFailureKind.SOURCE_NOT_CONFIGURED) {
                context.getString(R.string.player_audio_source_required)
            } else {
                context.getString(
                    R.string.player_playback_error,
                    error.localizedMessage ?: error.message ?: context.getString(R.string.common_error_unknown)
                )
            }
            Timber.tag(PlaybackTrace.LOG_TAG).e(
                error,
                "stage=resolve_failed requestId=%s mediaId=%s source=%s kind=%s",
                requestId,
                mediaItem.mediaId,
                originalUri.scheme,
                failureKind
            )
            cb.emitToast(message)
            null
        }
    }

    fun loadAndPlaySong(song: Song) {
        cancelPendingFullQueuePlayback()
        beginPreparingSong(song)
        val controller = cb.getController()
        if (controller == null) {
            playbackRequestCoordinator.deferUntilPlayerReady {
                loadAndPlaySong(song)
            }
            return
        }

        playbackRequestCoordinator.launchLatest(cb.scope, PlaybackRequestLane.DIRECT) {
            val mediaItem = buildResolvedPlaybackMediaItem(song, requestId)
                ?: return@launchLatest
            ensureCurrent()
            cb.showSheet()
            if (controller.currentMediaItem?.mediaId == song.id) {
                if (!controller.playWhenReady) {
                    playbackRequestCoordinator.dispatchTransport(controller, PlaybackTransportCommand.Play)
                }
            } else {
                playbackRequestCoordinator.replaceCurrentAndPlay(controller, mediaItem)
            }
        }
    }

    fun addSongToQueue(song: Song) {
        cb.getController()?.let { controller ->
            val mediaItem = buildPlaybackMediaItem(song)
            playbackRequestCoordinator.appendMediaItem(controller, mediaItem)
            // Queue UI is synced via onTimelineChanged listener
        }
    }

    fun addSongNextToQueue(song: Song) {
        cb.getController()?.let { controller ->
            val mediaItem = buildPlaybackMediaItem(song)

            val insertionIndex = if (controller.currentMediaItemIndex != C.INDEX_UNSET) {
                (controller.currentMediaItemIndex + 1).coerceAtMost(controller.mediaItemCount)
            } else {
                controller.mediaItemCount
            }

            playbackRequestCoordinator.insertMediaItem(controller, insertionIndex, mediaItem)
            // Queue UI is synced via onTimelineChanged listener
        }
    }

    private fun buildPlaybackMediaItem(
        song: Song,
        playlistId: String? = null,
        playbackRequestId: String = PlaybackTrace.newRequestId()
    ): MediaItem {
        val baseItem = MediaItemBuilder.build(
            PlaybackItem.fromCompatibleSong(song),
            playbackRequestId
        )
        if (playlistId == null) {
            return baseItem
        }

        val mergedExtras = Bundle(baseItem.mediaMetadata.extras ?: Bundle()).apply {
            putString("playlistId", playlistId)
        }

        return baseItem.buildUpon()
            .setMediaMetadata(
                baseItem.mediaMetadata.buildUpon()
                    .setExtras(mergedExtras)
                    .build()
            )
            .build()
    }

    fun playPause() {
        val controller = cb.getController()
        if (controller == null || !controller.isConnected) {
            playbackStateHolder.playPause()
            return
        }

        if (controller.isPlaying) {
            playbackRequestCoordinator.dispatchTransport(controller, PlaybackTransportCommand.Pause)
        } else if (controller.currentMediaItem == null) {
            val currentQueue = cb.getUiState().currentPlaybackQueue
            val currentSong = playbackStateHolder.stablePlayerState.value.currentSong
            when {
                currentQueue.isNotEmpty() && currentSong != null -> {
                    playSongs(
                        currentQueue.toList(),
                        currentSong,
                        cb.getUiState().currentQueueSourceName
                    )
                }
                currentSong != null -> loadAndPlaySong(currentSong)
                else -> {
                    cb.scope.launch {
                        val fallbackSong = musicRepository.getFirstPlayableSong()
                        if (fallbackSong != null) {
                            loadAndPlaySong(fallbackSong)
                        } else {
                            playbackRequestCoordinator.dispatchTransport(
                                controller,
                                PlaybackTransportCommand.Play
                            )
                        }
                    }
                }
            }
        } else {
            playbackRequestCoordinator.dispatchTransport(controller, PlaybackTransportCommand.Play)
        }
    }
}
