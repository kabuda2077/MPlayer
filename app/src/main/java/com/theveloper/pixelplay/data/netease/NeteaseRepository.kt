@file:Suppress("DEPRECATION")
package com.theveloper.pixelplay.data.netease

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.theveloper.pixelplay.data.database.NeteaseDao
import com.theveloper.pixelplay.data.database.NeteasePlaylistEntity
import com.theveloper.pixelplay.data.database.NeteaseSongEntity
import com.theveloper.pixelplay.data.database.toSong
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.network.netease.NeteaseApiService
import com.theveloper.pixelplay.data.preferences.PlaylistPreferencesRepository
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.data.repository.CloudLibraryProjector
import com.theveloper.pixelplay.data.repository.OnlineLibraryIds
import com.theveloper.pixelplay.data.stream.BulkSyncResult
import com.theveloper.pixelplay.data.stream.CloudMusicUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Suppress("DEPRECATION")
@Singleton
class NeteaseRepository @Inject constructor(
    private val api: NeteaseApiService,
    private val dao: NeteaseDao,
    private val cloudLibraryProjector: CloudLibraryProjector,
    private val playlistPreferencesRepository: PlaylistPreferencesRepository,
    @ApplicationContext private val context: Context
) {
    data class AuthBackupSnapshot(
        val cookieJson: String? = null,
        val userId: Long? = null,
        val nickname: String? = null,
        val avatarUrl: String? = null
    )

    private companion object {
        private const val NETEASE_PARENT_DIRECTORY = "/Cloud/Netease"
        private const val NETEASE_GENRE = "Netease Cloud"
        private const val NETEASE_PLAYLIST_PREFIX = "netease_playlist:"
        private const val NETEASE_PLAYLIST_PAGE_SIZE = 50
        private const val NETEASE_SONG_DETAIL_BATCH_SIZE = 500
        private const val NETEASE_MAX_PLAYLIST_PAGES = 200
    }

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "netease_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Timber.e(e, "NeteaseRepository: Failed to create EncryptedSharedPreferences, falling back to plain")
        context.getSharedPreferences("netease_prefs_plain", Context.MODE_PRIVATE)
    }

    private val _isLoggedInFlow = MutableStateFlow(false)
    val isLoggedInFlow: StateFlow<Boolean> = _isLoggedInFlow.asStateFlow()

    private val inFlightSongUrlRequests = java.util.concurrent.ConcurrentHashMap<Long, CompletableDeferred<Result<String>>>()
    private val lastSongUrlAttemptAtMs = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    private val songUrlRequestCooldownMs = 1500L
    private val neteaseSongUrlRequestMutex = Mutex()
    @Volatile
    private var lastGlobalSongUrlRequestAtMs = 0L
    private val globalSongUrlRequestIntervalMs = 1100L

    init {
        // Auto-load saved cookies on creation so API client is ready
        initFromSavedCookies()
        _isLoggedInFlow.value = api.hasLogin()
        Timber.d("NeteaseRepository init: isLoggedIn=${api.hasLogin()}")
    }

    // ─── Auth State ────────────────────────────────────────────────────

    val isLoggedIn: Boolean
        get() = api.hasLogin()

    val userId: Long
        get() = prefs.getLong("netease_user_id", -1L)

    val userNickname: String?
        get() = prefs.getString("netease_nickname", null)

    val userAvatar: String?
        get() = prefs.getString("netease_avatar", null)

    fun exportAuthBackupSnapshot(): AuthBackupSnapshot? {
        val cookieJson = prefs.getString("netease_cookies", null)
        val savedUserId = prefs.getLong("netease_user_id", -1L).takeIf { it > 0L }
        val nickname = prefs.getString("netease_nickname", null)
        val avatarUrl = prefs.getString("netease_avatar", null)
        if (cookieJson.isNullOrBlank() && savedUserId == null && nickname.isNullOrBlank() && avatarUrl.isNullOrBlank()) {
            return null
        }
        return AuthBackupSnapshot(
            cookieJson = cookieJson,
            userId = savedUserId,
            nickname = nickname,
            avatarUrl = avatarUrl
        )
    }

    fun restoreAuthBackupSnapshot(snapshot: AuthBackupSnapshot?) {
        api.logout()
        clearLoginState()
        if (snapshot == null) {
            _isLoggedInFlow.value = false
            return
        }

        prefs.edit().apply {
            snapshot.cookieJson?.takeIf { it.isNotBlank() }?.let { putString("netease_cookies", it) }
            snapshot.userId?.takeIf { it > 0L }?.let { putLong("netease_user_id", it) }
            snapshot.nickname?.takeIf { it.isNotBlank() }?.let { putString("netease_nickname", it) }
            snapshot.avatarUrl?.takeIf { it.isNotBlank() }?.let { putString("netease_avatar", it) }
        }.apply()

        initFromSavedCookies()
        _isLoggedInFlow.value = api.hasLogin()
    }

    // ─── Cookie-Based Authentication ──────────────────────────────────

    /**
     * Initialize from saved cookies on app start.
     */
    fun initFromSavedCookies() {
        val cookieJson = prefs.getString("netease_cookies", null) ?: return
        try {
            val map = jsonToMap(cookieJson)
            if (map.isNotEmpty()) {
                api.setPersistedCookies(map)
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to restore Netease cookies")
        }
    }

    /**
     * Save cookies from WebView login result and initialize the API client.
     * Returns the user's nickname on success.
     */
    suspend fun loginWithCookies(cookieJson: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val cookies = jsonToMap(cookieJson)

                if (!cookies.containsKey("MUSIC_U")) {
                    Timber.w("loginWithCookies: required session cookie not found")
                    return@withContext Result.failure(Exception("MUSIC_U cookie not found"))
                }

                // Persist cookies
                prefs.edit().putString("netease_cookies", cookieJson).apply()

                // Initialize API client with cookies
                api.setPersistedCookies(cookies)

                // Fetch user info
                val userAccountRaw = api.getCurrentUserAccount()
                val root = JSONObject(userAccountRaw)
                val code = root.optInt("code", -1)
                val profile = root.optJSONObject("profile")

                if (profile != null) {
                    val uid = profile.optLong("userId")
                    val nickname = profile.optString("nickname", "User")
                    val avatarUrl = profile.optString("avatarUrl", "")
                    Timber.d("loginWithCookies: login successful")

                    saveUserInfo(uid, nickname, avatarUrl)
                    _isLoggedInFlow.value = true
                    Result.success(nickname)
                } else {
                    Timber.w("loginWithCookies: No profile in response (code=$code)")
                    Result.failure(Exception("Failed to fetch user profile (code=$code)"))
                }
            } catch (e: Exception) {
                Timber.e(e, "loginWithCookies: failed")
                Result.failure(e)
            }
        }
    }

    suspend fun logout() {
        api.logout()
        clearLoginState()
        
        // Delete all Netease playlists from the database
        val neteasePlaylistsToDelete = dao.getAllPlaylistsList()
        neteasePlaylistsToDelete.forEach { playlist ->
            dao.deleteSongsByPlaylist(playlist.id)
            dao.deletePlaylist(playlist.id)
            deleteAppPlaylistForNeteasePlaylist(playlist.id)
        }
        
        cloudLibraryProjector.removeUnretainedNeteaseSongs()
        _isLoggedInFlow.value = false
    }

    private fun saveUserInfo(userId: Long, nickname: String, avatarUrl: String?) {
        prefs.edit()
            .putLong("netease_user_id", userId)
            .putString("netease_nickname", nickname)
            .putString("netease_avatar", avatarUrl)
            .apply()
    }

    private fun clearLoginState() {
        prefs.edit().clear().apply()
    }

    // ─── Content ───────────────────────────────────────────────────────

    suspend fun syncUserPlaylists(): Result<List<NeteasePlaylistEntity>> {
        Timber.d("syncUserPlaylists called, isLoggedIn=${isLoggedIn}, userId=$userId")
        if (!isLoggedIn) {
            Timber.w("syncUserPlaylists: Not logged in, aborting")
            return Result.failure(Exception("Not logged in"))
        }
        return withContext(Dispatchers.IO) {
            try {
                val uid = if (userId != -1L) userId else api.getCurrentUserId()
                Timber.d("syncUserPlaylists: fetching playlists for uid=$uid")
                val entitiesById = LinkedHashMap<Long, NeteasePlaylistEntity>()
                var offset = 0
                var page = 0
                var hasMore = true

                while (hasMore) {
                    val raw = api.getUserPlaylists(
                        userId = uid,
                        offset = offset,
                        limit = NETEASE_PLAYLIST_PAGE_SIZE
                    )
                    Timber.d("syncUserPlaylists: page=$page offset=$offset response length=${raw.length}")
                    val root = JSONObject(raw)

                    if (root.optInt("code", -1) != 200) {
                        Timber.e("syncUserPlaylists: API error code=${root.optInt("code")}")
                        return@withContext Result.failure(Exception("API error: code ${root.optInt("code")}"))
                    }

                    val playlistArray = root.optJSONArray("playlist") ?: break
                    val fetchedCount = playlistArray.length()
                    if (fetchedCount == 0) break

                    for (i in 0 until fetchedCount) {
                        val pl = playlistArray.optJSONObject(i) ?: continue
                        val id = pl.optLong("id")
                        if (id <= 0L) continue
                        entitiesById[id] = NeteasePlaylistEntity(
                            id = id,
                            name = pl.optString("name", ""),
                            coverUrl = pl.optString("coverImgUrl", ""),
                            songCount = pl.optInt("trackCount", 0),
                            lastSyncTime = System.currentTimeMillis()
                        )
                    }

                    offset += fetchedCount
                    val totalCount = root.optInt("count", -1)
                    val moreFlag = root.optBoolean("more", false)
                    hasMore = when {
                        moreFlag -> true
                        totalCount > 0 -> offset < totalCount
                        else -> fetchedCount >= NETEASE_PLAYLIST_PAGE_SIZE
                    }

                    page += 1
                    if (page >= NETEASE_MAX_PLAYLIST_PAGES) {
                        Timber.w("syncUserPlaylists: reached max page guard ($NETEASE_MAX_PLAYLIST_PAGES), stopping pagination")
                        hasMore = false
                    }
                }

                val entities = entitiesById.values.toList()

                val localPlaylists = dao.getAllPlaylistsList()
                val remoteIds = entities.map { it.id }.toSet()
                val stalePlaylists = localPlaylists.filter { it.id !in remoteIds }

                stalePlaylists.forEach { stale ->
                    dao.deleteSongsByPlaylist(stale.id)
                    dao.deletePlaylist(stale.id)
                    // Delete corresponding app playlists for removed Netease playlists
                    deleteAppPlaylistForNeteasePlaylist(stale.id)
                }

                entities.forEach { dao.insertPlaylist(it) }
                if (stalePlaylists.isNotEmpty()) {
                    syncUnifiedLibrarySongsFromNetease()
                }
                Result.success(entities)
            } catch (e: Exception) {
                Timber.e(e, "Failed to sync user playlists")
                Result.failure(e)
            }
        }
    }

    suspend fun syncPlaylistSongs(playlistId: Long): Result<Int> {
        return syncPlaylistSongs(playlistId, syncUnifiedLibrary = true)
    }

    suspend fun syncPlaylistSongs(
        playlistId: Long,
        syncUnifiedLibrary: Boolean
    ): Result<Int> {
        return withContext(Dispatchers.IO) {
            try {
                val raw = api.getPlaylistDetail(playlistId)
                val root = JSONObject(raw)

                if (root.optInt("code", -1) != 200) {
                    return@withContext Result.failure(Exception("API error"))
                }

                val playlist = root.optJSONObject("playlist")
                    ?: return@withContext Result.failure(Exception("No playlist data"))
                val embeddedTracks = playlist.optJSONArray("tracks")
                val trackIds = playlist.optJSONArray("trackIds")
                val playlistName = playlist.optString("name", "")

                val entitiesBySongId = LinkedHashMap<Long, NeteaseSongEntity>()
                val orderedTrackIds = mutableListOf<Long>()

                for (i in 0 until (embeddedTracks?.length() ?: 0)) {
                    val track = embeddedTracks?.optJSONObject(i) ?: continue
                    val entity = parseTrackToEntity(track, playlistId)
                    entitiesBySongId[entity.neteaseId] = entity
                }

                for (i in 0 until (trackIds?.length() ?: 0)) {
                    val id = trackIds?.optJSONObject(i)?.optLong("id") ?: 0L
                    if (id > 0L) {
                        orderedTrackIds.add(id)
                    }
                }

                val existingTrackIds = entitiesBySongId.keys.toSet()
                val missingTrackIds = if (orderedTrackIds.isNotEmpty()) {
                    orderedTrackIds.filterNot(existingTrackIds::contains)
                } else {
                    emptyList()
                }

                if (missingTrackIds.isNotEmpty()) {
                    Timber.d(
                        "syncPlaylistSongs: playlistId=$playlistId needs ${missingTrackIds.size} additional tracks beyond embedded detail"
                    )
                    missingTrackIds.chunked(NETEASE_SONG_DETAIL_BATCH_SIZE).forEach { chunk ->
                        val detailRaw = api.getSongDetails(chunk)
                        val detailRoot = JSONObject(detailRaw)
                        if (detailRoot.optInt("code", -1) != 200) {
                            Timber.w(
                                "syncPlaylistSongs: getSongDetails failed for chunk size=${chunk.size}, code=${detailRoot.optInt("code", -1)}"
                            )
                            return@forEach
                        }
                        val detailSongs = detailRoot.optJSONArray("songs") ?: return@forEach
                        for (i in 0 until detailSongs.length()) {
                            val track = detailSongs.optJSONObject(i) ?: continue
                            val entity = parseTrackToEntity(track, playlistId)
                            entitiesBySongId[entity.neteaseId] = entity
                        }
                    }
                }

                val entities = if (orderedTrackIds.isNotEmpty()) {
                    val ordered = orderedTrackIds.mapNotNull { entitiesBySongId[it] }
                    if (ordered.size < entitiesBySongId.size) {
                        val orderedSet = orderedTrackIds.toSet()
                        ordered + entitiesBySongId.values.filterNot { it.neteaseId in orderedSet }
                    } else {
                        ordered
                    }
                } else {
                    entitiesBySongId.values.toList()
                }

                val expectedTrackCount = playlist.optInt("trackCount", orderedTrackIds.size)
                if (expectedTrackCount > 0 && entities.size < expectedTrackCount) {
                    Timber.w(
                        "syncPlaylistSongs: playlistId=$playlistId expected=$expectedTrackCount synced=${entities.size} (API may still be limiting some tracks)"
                    )
                }

                dao.deleteSongsByPlaylist(playlistId)
                dao.insertSongs(entities)
                
                // Create or update the corresponding app playlist
                updateAppPlaylistForNeteasePlaylist(playlistId, playlistName, entities)
                
                if (syncUnifiedLibrary) {
                    syncUnifiedLibrarySongsFromNetease()
                }

                Timber.d("Synced ${entities.size} songs for playlist $playlistId")
                Result.success(entities.size)
            } catch (e: Exception) {
                Timber.e(e, "Failed to sync playlist $playlistId")
                Result.failure(e)
            }
        }
    }

    suspend fun syncAllPlaylistsAndSongs(): Result<BulkSyncResult> {
        return withContext(Dispatchers.IO) {
            val playlistResult = syncUserPlaylists().getOrElse { return@withContext Result.failure(it) }
            if (playlistResult.isEmpty()) {
                syncUnifiedLibrarySongsFromNetease()
                return@withContext Result.success(
                    BulkSyncResult(
                        playlistCount = 0,
                        syncedSongCount = 0,
                        failedPlaylistCount = 0
                    )
                )
            }

            var syncedSongCount = 0
            var failedPlaylistCount = 0

            playlistResult.forEach { playlist ->
                val songSyncResult = syncPlaylistSongs(
                    playlistId = playlist.id,
                    syncUnifiedLibrary = false
                )
                songSyncResult.fold(
                    onSuccess = { count -> syncedSongCount += count },
                    onFailure = {
                        failedPlaylistCount += 1
                        Timber.w(it, "Failed syncing playlistId=${playlist.id}")
                    }
                )
            }

            syncUnifiedLibrarySongsFromNetease()

            Result.success(
                BulkSyncResult(
                    playlistCount = playlistResult.size,
                    syncedSongCount = syncedSongCount,
                    failedPlaylistCount = failedPlaylistCount
                )
            )
        }
    }

    fun getPlaylists(): Flow<List<NeteasePlaylistEntity>> = dao.getAllPlaylists()

    fun getPlaylistSongs(playlistId: Long): Flow<List<Song>> {
        return dao.getSongsByPlaylist(playlistId).map { entities ->
            entities.map { it.toSong() }
        }
    }

    fun getAllSongs(): Flow<List<Song>> {
        return dao.getAllNeteaseSongs().map { entities ->
            entities.map { it.toSong() }
        }
    }

    fun searchLocalSongs(query: String): Flow<List<Song>> {
        return dao.searchSongs(query).map { entities ->
            entities.map { it.toSong() }
        }
    }

    // ─── Online Search ─────────────────────────────────────────────────

    suspend fun searchOnline(keywords: String, limit: Int = 30, offset: Int = 0): Result<List<Song>> {
        return withContext(Dispatchers.IO) {
            try {
                val raw = api.searchSongs(keywords, limit, offset)
                val root = JSONObject(raw)
                val code = root.optInt("code", -1)
                if (code != 200) {
                    throw IllegalStateException("Netease search failed with code $code")
                }
                val result = root.optJSONObject("result")
                    ?: throw IllegalStateException("Netease search response is missing results")
                val songs = result.optJSONArray("songs")

                if (songs != null) {
                    val songList = mutableListOf<Song>()
                    for (i in 0 until songs.length()) {
                        val track = songs.optJSONObject(i) ?: continue
                        songList.add(parseTrackToSong(track))
                    }
                    Result.success(songList)
                } else {
                    Result.success(emptyList())
                }
            } catch (e: Exception) {
                Timber.e(e, "Online search failed for: $keywords")
                Result.failure(e)
            }
        }
    }

    // ─── Song URL Resolution ───────────────────────────────────────────

    suspend fun getSongUrl(songId: Long, quality: String = "exhigh"): Result<String> {
        val now = System.currentTimeMillis()
        val lastAttempt = lastSongUrlAttemptAtMs[songId]
        if (lastAttempt != null && now - lastAttempt < songUrlRequestCooldownMs) {
            Timber.d("Skip Netease song URL retry due to cooldown: songId=$songId")
            return Result.failure(IllegalStateException("Netease song URL request throttled"))
        }
        lastSongUrlAttemptAtMs[songId] = now

        inFlightSongUrlRequests[songId]?.let {
            return it.await()
        }

        val requestDeferred = CompletableDeferred<Result<String>>()
        val existing = inFlightSongUrlRequests.putIfAbsent(songId, requestDeferred)
        if (existing != null) {
            return existing.await()
        }

        val result = withContext(Dispatchers.IO) {
            runCatching {
                val qualityFallbacks = linkedSetOf(quality, "higher", "standard")
                var lastFailure: String? = null

                for (level in qualityFallbacks) {
                    val raw = requestSongUrl(songId, level)
                    val root = JSONObject(raw)
                    val code = root.optInt("code", -1)
                    if (code != 200) {
                        lastFailure = "API code=$code for level=$level"
                        continue
                    }

                    val data = root.optJSONArray("data")
                    val urlObj = data?.optJSONObject(0)
                    val url = urlObj?.optString("url", "")
                    if (!url.isNullOrBlank() && url != "null") {
                        Timber.d("Resolved Netease URL for songId=$songId with level=$level")
                        return@runCatching url
                    }

                    val freeTrialInfo = urlObj?.opt("freeTrialInfo")
                    lastFailure = "Empty URL at level=$level, freeTrialInfo=$freeTrialInfo"
                }

                throw Exception("No URL available for song $songId ($lastFailure)")
            }
        }

        requestDeferred.complete(result)
        inFlightSongUrlRequests.remove(songId, requestDeferred)
        return result
    }

    /**
     * Make a single song URL request with global rate-limit guard.
     */
    private suspend fun requestSongUrl(songId: Long, level: String): String {
        neteaseSongUrlRequestMutex.withLock {
            val now = System.currentTimeMillis()
            val waitMs = globalSongUrlRequestIntervalMs - (now - lastGlobalSongUrlRequestAtMs)
            if (waitMs > 0) delay(waitMs)
            lastGlobalSongUrlRequestAtMs = System.currentTimeMillis()
        }
        return api.getSongDownloadUrl(songId, level)
    }

    // ─── Lyrics ────────────────────────────────────────────────────────

    suspend fun getLyrics(songId: Long): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val raw = api.getLyrics(songId)
                val root = JSONObject(raw)
                val lyric = root.optJSONObject("lrc")?.optString("lyric", "")
                val translatedLyric = root.optJSONObject("tlyric")?.optString("lyric", "")

                if (!lyric.isNullOrBlank()) {
                    Result.success(mergeNeteaseLyricWithTranslation(lyric, translatedLyric))
                } else {
                    Result.failure(Exception("No lyrics for song $songId"))
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to get lyrics for song $songId")
                Result.failure(e)
            }
        }
    }

    suspend fun getLyricsPayload(songId: Long): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                Result.success(api.getLyrics(songId))
            } catch (e: Exception) {
                Timber.e(e, "Failed to get raw lyrics payload for song $songId")
                Result.failure(e)
            }
        }
    }

    private fun mergeNeteaseLyricWithTranslation(
        lyric: String,
        translatedLyric: String?
    ): String {
        if (translatedLyric.isNullOrBlank()) return lyric

        val cleanedTranslation = translatedLyric
            .lines()
            .filter { it.isNotBlank() }
            .joinToString("\n")

        if (cleanedTranslation.isBlank()) return lyric

        // LyricsUtils pairs consecutive lines with the same timestamp as
        // original + translation, so keeping both LRC streams preserves
        // bilingual display without replacing the original lyric.
        return buildString {
            append(lyric.trimEnd())
            append('\n')
            append(cleanedTranslation)
        }
    }

    // ─── JSON Parsing Helpers ──────────────────────────────────────────

    private fun parseTrackToEntity(track: JSONObject, playlistId: Long): NeteaseSongEntity {
        val artists = track.optJSONArray("ar")
        val artistName = buildString {
            if (artists != null) {
                for (j in 0 until artists.length()) {
                    if (j > 0) append(", ")
                    append(artists.optJSONObject(j)?.optString("name", "") ?: "")
                }
            } else {
                append("Unknown Artist")
            }
        }
        val album = track.optJSONObject("al")
        val albumArtUrl = normalizeArtworkUrl(album?.optString("picUrl"))

        return NeteaseSongEntity(
            id = "${playlistId}_${track.optLong("id")}",
            neteaseId = track.optLong("id"),
            playlistId = playlistId,
            title = track.optString("name", ""),
            artist = artistName,
            album = album?.optString("name", "Unknown Album") ?: "Unknown Album",
            albumId = album?.optLong("id") ?: -1L,
            duration = track.optLong("dt"),
            albumArtUrl = albumArtUrl,
            mimeType = "audio/mpeg",
            bitrate = null,
            dateAdded = track.optLong("publishTime", System.currentTimeMillis())
        )
    }

    private fun parseTrackToSong(track: JSONObject): Song {
        val artists = track.optJSONArray("ar")
        val artistName = buildString {
            if (artists != null) {
                for (j in 0 until artists.length()) {
                    if (j > 0) append(", ")
                    append(artists.optJSONObject(j)?.optString("name", "") ?: "")
                }
            } else {
                append("Unknown Artist")
            }
        }
        val album = track.optJSONObject("al")
        val neteaseId = track.optLong("id")
        val albumArtUrl = normalizeArtworkUrl(album?.optString("picUrl"))

        return Song(
            id = "netease_$neteaseId",
            title = track.optString("name", ""),
            artist = artistName,
            artistId = artists?.optJSONObject(0)?.optLong("id") ?: -1L,
            album = album?.optString("name", "Unknown Album") ?: "Unknown Album",
            albumId = album?.optLong("id") ?: -1L,
            path = "",
            contentUriString = "netease://$neteaseId",
            albumArtUriString = albumArtUrl,
            duration = track.optLong("dt"),
            mimeType = "audio/mpeg",
            bitrate = null,
            sampleRate = null,
            year = 0,
            trackNumber = 0,
            dateAdded = track.optLong("publishTime", System.currentTimeMillis()),
            isFavorite = false,
            neteaseId = neteaseId
        )
    }

    private fun normalizeArtworkUrl(value: String?): String? {
        val trimmed = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return if (trimmed.startsWith("http://", ignoreCase = true)) {
            "https://${trimmed.removePrefix("http://")}"
        } else {
            trimmed
        }
    }

    private suspend fun syncUnifiedLibrarySongsFromNetease() =
        cloudLibraryProjector.projectNetease()

    // ─── Delete ────────────────────────────────────────────────────────

    suspend fun deletePlaylist(playlistId: Long) {
        dao.deleteSongsByPlaylist(playlistId)
        dao.deletePlaylist(playlistId)
        deleteAppPlaylistForNeteasePlaylist(playlistId)
        syncUnifiedLibrarySongsFromNetease()
    }

    // ─── App Playlist Management ────────────────────────────────────────

    private suspend fun getAppPlaylistIdForNetease(neteasePlaylistId: Long): String {
        return "$NETEASE_PLAYLIST_PREFIX$neteasePlaylistId"
    }

    private suspend fun updateAppPlaylistForNeteasePlaylist(
        neteasePlaylistId: Long,
        playlistName: String,
        neteaseEntities: List<NeteaseSongEntity>
    ) {
        try {
            // Convert Netease song entities to unified song IDs (Long format, stored as String)
            // These must match the IDs generated in syncUnifiedLibrarySongsFromNetease
            val unifiedSongIds = neteaseEntities.map { entity ->
                OnlineLibraryIds.neteaseSong(entity.neteaseId).toString()
            }

            val appPlaylistId = getAppPlaylistIdForNetease(neteasePlaylistId)
            
            // Get all current app playlists
            val allPlaylists = playlistPreferencesRepository.userPlaylistsFlow
            val existingPlaylist = withContext(Dispatchers.IO) {
                allPlaylists.map { playlists ->
                    playlists.find { it.id == appPlaylistId }
                }.first()
            }

            if (existingPlaylist != null) {
                // Update the existing playlist
                playlistPreferencesRepository.updatePlaylist(
                    existingPlaylist.copy(
                        name = playlistName,
                        songIds = unifiedSongIds,
                        lastModified = System.currentTimeMillis(),
                        source = "NETEASE" // Mark as NetEase source
                    )
                )
                Timber.d("Updated app playlist for Netease playlist $neteasePlaylistId: $playlistName")
            } else {
                // Create a new playlist with custom ID to prevent duplicates
                playlistPreferencesRepository.createPlaylist(
                    name = playlistName,
                    songIds = unifiedSongIds,
                    customId = appPlaylistId,  // Use NetEase prefix ID for matching on next sync
                    source = "NETEASE"         // Mark as NetEase source
                )
                Timber.d("Created new app playlist for Netease playlist $neteasePlaylistId: $playlistName with ID: $appPlaylistId")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to update/create app playlist for Netease playlist $neteasePlaylistId")
        }
    }

    private suspend fun deleteAppPlaylistForNeteasePlaylist(neteasePlaylistId: Long) {
        try {
            val appPlaylistId = getAppPlaylistIdForNetease(neteasePlaylistId)
            playlistPreferencesRepository.deletePlaylist(appPlaylistId)
            Timber.d("Deleted app playlist for Netease playlist $neteasePlaylistId")
        } catch (e: Exception) {
            Timber.w(e, "Failed to delete app playlist for Netease playlist $neteasePlaylistId")
        }
    }

    // ─── Utility ───────────────────────────────────────────────────────

    private fun jsonToMap(json: String): Map<String, String> =
        CloudMusicUtils.jsonToMap(json)
}
