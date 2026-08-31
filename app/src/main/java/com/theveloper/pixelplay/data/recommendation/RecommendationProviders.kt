package com.theveloper.pixelplay.data.recommendation

import com.theveloper.pixelplay.data.model.OnlineBrowsePlatform
import com.theveloper.pixelplay.data.model.OnlineSongMapper
import com.theveloper.pixelplay.data.model.RecommendationAuthState
import com.theveloper.pixelplay.data.model.RecommendationItem
import com.theveloper.pixelplay.data.model.RecommendationPage
import com.theveloper.pixelplay.data.model.RecommendationSection
import com.theveloper.pixelplay.data.model.RecommendationSectionKind
import com.theveloper.pixelplay.data.model.RecommendationSongItem
import com.theveloper.pixelplay.data.model.RemoteChartDetail
import com.theveloper.pixelplay.data.model.RemoteChartReference
import com.theveloper.pixelplay.data.model.RemotePlaylistDetail
import com.theveloper.pixelplay.data.model.RemotePlaylistReference
import com.theveloper.pixelplay.data.model.TransientOnlineSong
import com.theveloper.pixelplay.data.network.netease.NeteaseApiService
import com.theveloper.pixelplay.data.network.qqmusic.QqMusicApiService
import com.theveloper.pixelplay.data.netease.NeteaseRepository
import com.theveloper.pixelplay.data.plugin.PluginSource
import com.theveloper.pixelplay.data.qqmusic.QqMusicRepository
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

class NeteaseRecommendationProvider @Inject constructor(
    private val api: NeteaseApiService,
    private val repository: NeteaseRepository
) : OnlineRecommendationProvider {
    override val platform: OnlineBrowsePlatform = OnlineBrowsePlatform.NETEASE

    override suspend fun loadHomePage(forceRefresh: Boolean): Result<RecommendationPage> = withContext(Dispatchers.IO) {
        runCatching {
            val loggedIn = repository.isLoggedIn
            val (dailyResult, newSongsResult, playlistResult) = coroutineScope {
                val daily = async {
                    if (loggedIn) safeRaw { api.getDailyRecommendSongs(forceRefresh) }
                    else Result.failure(IllegalStateException("daily recommendation requires login"))
                }
                val newSongs = async { safeRaw { api.getPersonalizedNewSongs() } }
                val playlists = async { loadNeteasePlaylistRecommendations(loggedIn) }
                Triple(daily.await(), newSongs.await(), playlists.await())
            }
            val chartReferences = loadChartPreviews(
                listOf(
                    RemoteChartReference(platform, "3778678", "热歌榜"),
                    RemoteChartReference(platform, "19723756", "飙升榜"),
                    RemoteChartReference(platform, "3779629", "新歌榜"),
                    RemoteChartReference(platform, "2884035", "原创榜")
                )
            )

            val sections = buildList {
                dailyResult.getOrNull()?.let { raw ->
                    val songs = RecommendationJson.parseNeteaseSongs(raw).take(6)
                    if (songs.isNotEmpty()) add(songSection("daily", "每日推荐", RecommendationSectionKind.DAILY_SONGS, songs))
                }
                newSongsResult.getOrNull()?.let { raw ->
                    val songs = RecommendationJson.parseNeteaseSongs(raw).take(6)
                    if (songs.isNotEmpty()) add(songSection("new", "新歌速递", RecommendationSectionKind.NEW_SONGS, songs))
                }
                playlistResult.getOrNull()?.let { playlists ->
                    if (playlists.isNotEmpty()) {
                        add(
                            RecommendationSection(
                                id = "playlists",
                                title = if (loggedIn) "推荐歌单" else "热门歌单",
                                kind = RecommendationSectionKind.RECOMMENDED_PLAYLISTS,
                                items = playlists,
                                hasMore = true
                            )
                        )
                    }
                }
                add(
                    RecommendationSection(
                        id = "charts",
                        title = "榜单",
                        kind = RecommendationSectionKind.CHART_ENTRY,
                        items = chartReferences,
                        hasMore = false
                    )
                )
            }

            RecommendationPage(
                platform = platform,
                authState = if (loggedIn) RecommendationAuthState.LOGGED_IN else RecommendationAuthState.LOGGED_OUT,
                sections = sections,
                fetchedAt = System.currentTimeMillis()
            )
        }
    }

    override suspend fun loadPlaylist(reference: RemotePlaylistReference): Result<RemotePlaylistDetail> = withContext(Dispatchers.IO) {
        runCatching {
            val id = reference.remoteId.toLongOrNull() ?: error("Invalid Netease playlist id")
            val raw = api.getPlaylistDetail(id)
            RemotePlaylistDetail(reference, RecommendationJson.parseNeteaseSongs(raw))
        }
    }

    override suspend fun loadSection(sectionId: String): Result<List<TransientOnlineSong>> = withContext(Dispatchers.IO) {
        runCatching {
            when (sectionId) {
                "daily" -> RecommendationJson.parseNeteaseSongs(api.getDailyRecommendSongs(afresh = true))
                "new" -> RecommendationJson.parseNeteaseSongs(api.getPersonalizedNewSongs(limit = 30))
                else -> error("Unsupported Netease recommendation section: $sectionId")
            }.take(30)
        }
    }

    override suspend fun loadChart(reference: RemoteChartReference): Result<RemoteChartDetail> =
        loadPlaylist(RemotePlaylistReference(platform, reference.remoteId, reference.title))
            .map { RemoteChartDetail(reference, it.songs) }

    private suspend fun loadChartPreviews(
        references: List<RemoteChartReference>
    ): List<RemoteChartReference> = coroutineScope {
        references.map { reference ->
            async {
                runCatching {
                    val id = reference.remoteId.toLongOrNull() ?: return@runCatching reference
                    val songs = RecommendationJson.parseNeteaseSongs(api.getPlaylistDetail(id, songLimit = 6))
                    reference.copy(previewArtworkUris = songs.mapNotNull { it.song.albumArtUriString }.distinct().take(4))
                }.getOrDefault(reference)
            }
        }.awaitAll()
    }

    private fun songSection(
        id: String,
        title: String,
        kind: RecommendationSectionKind,
        songs: List<TransientOnlineSong>
    ) = RecommendationSection(id, title, kind, songs.map(::RecommendationSongItem), hasMore = true)

    /**
     * A successful personalized response is not enough to use it: the public
     * endpoint can return a valid JSON envelope with no usable playlist items
     * (for example when the login cookie is stale or the response shape has
     * changed). In that case we must still try the public playlist feed.
     */
    private suspend fun loadNeteasePlaylistRecommendations(
        loggedIn: Boolean
    ): Result<List<RemotePlaylistReference>> {
        if (loggedIn) {
            val personalized = safeRaw { api.getPersonalizedPlaylists() }
                .mapCatching { raw ->
                    RecommendationJson.parseNeteasePlaylists(raw)
                }.onFailure { error ->
                    Timber.w(error, "Recommendation[NeteasePlaylists] parse failed source=personalized")
                }
            val personalizedPlaylists = personalized.getOrElse { emptyList() }
            Timber.d(
                "Recommendation[NeteasePlaylists] source=personalized count=%d",
                personalizedPlaylists.size
            )
            if (personalizedPlaylists.isNotEmpty()) {
                return Result.success(personalizedPlaylists)
            }
        }

        val public = safeRaw { api.getTopPlaylists() }
            .mapCatching { raw ->
                RecommendationJson.parseNeteasePlaylists(raw)
            }.onFailure { error ->
                Timber.w(error, "Recommendation[NeteasePlaylists] parse failed source=public")
            }
        public.fold(
            onSuccess = { playlists ->
                Timber.d(
                    "Recommendation[NeteasePlaylists] source=public count=%d",
                    playlists.size
                )
            },
            onFailure = { error ->
                Timber.w(error, "Recommendation[NeteasePlaylists] request failed source=public")
            }
        )
        return public
    }

    private suspend fun <T> safeRaw(block: suspend () -> T): Result<T> = runCatching { block() }
}

class QqMusicRecommendationProvider @Inject constructor(
    private val api: QqMusicApiService,
    private val repository: QqMusicRepository
) : OnlineRecommendationProvider {
    override val platform: OnlineBrowsePlatform = OnlineBrowsePlatform.QQ
    private val feedSongsBySectionId = ConcurrentHashMap<String, List<TransientOnlineSong>>()

    override suspend fun loadHomePage(forceRefresh: Boolean): Result<RecommendationPage> = withContext(Dispatchers.IO) {
        runCatching {
            val loggedIn = repository.isLoggedIn
            val (feedResult, newSongsResult, playlistResult) = coroutineScope {
                val feed = async { loadQqHomeFeed() }
                val newSongs = async { safeRaw { api.getRecommendNewSongs() } }
                val playlists = async { safeRaw { api.getRecommendSonglists() } }
                Triple(feed.await(), newSongs.await(), playlists.await())
            }
            val chartReferences = loadChartPreviews(
                listOf(
                    RemoteChartReference(platform, "26", "热歌榜"),
                    RemoteChartReference(platform, "62", "飙升榜"),
                    RemoteChartReference(platform, "27", "新歌榜"),
                    RemoteChartReference(platform, "4", "流行指数榜")
                )
            )
            val sections = buildList {
                feedResult.getOrNull()?.let { feedSections ->
                    enrichQqHomeFeedPlaylistCounts(feedSections)
                        .take(MAX_HOME_FEED_SECTIONS)
                        .forEach { section ->
                        val songs = section.items
                            .filterIsInstance<RecommendationSongItem>()
                            .map { it.transientSong }
                        if (songs.isNotEmpty()) {
                            feedSongsBySectionId[section.id] = songs.take(MAX_SECTION_SONGS)
                        }
                        add(section)
                    }
                }
                newSongsResult.getOrNull()?.let { raw ->
                    val songs = RecommendationJson.parseQqSongs(raw).take(6)
                    if (songs.isNotEmpty()) add(songSection("new", "新歌速递", RecommendationSectionKind.NEW_SONGS, songs))
                }
                playlistResult.getOrNull()?.let { raw ->
                    val playlists = RecommendationJson.parseQqPlaylists(raw).take(4)
                    if (playlists.isNotEmpty()) add(
                        RecommendationSection("playlists", "推荐歌单", RecommendationSectionKind.RECOMMENDED_PLAYLISTS, playlists, hasMore = false)
                    )
                }
                add(
                    RecommendationSection(
                        id = "charts",
                        title = "榜单",
                        kind = RecommendationSectionKind.CHART_ENTRY,
                        items = chartReferences
                    )
                )
            }
            RecommendationPage(
                platform = platform,
                authState = if (loggedIn) RecommendationAuthState.LOGGED_IN else RecommendationAuthState.LOGGED_OUT,
                sections = sections,
                fetchedAt = System.currentTimeMillis()
            )
        }
    }

    override suspend fun loadPlaylist(reference: RemotePlaylistReference): Result<RemotePlaylistDetail> = withContext(Dispatchers.IO) {
        runCatching {
            val id = reference.remoteId.toLongOrNull() ?: error("Invalid QQ playlist id")
            val raw = api.getPlaylistDetail(id)
            RemotePlaylistDetail(reference, RecommendationJson.parseQqSongs(raw))
        }
    }

    override suspend fun loadSection(sectionId: String): Result<List<TransientOnlineSong>> = withContext(Dispatchers.IO) {
        runCatching {
            when {
                feedSongsBySectionId.containsKey(sectionId) -> feedSongsBySectionId[sectionId].orEmpty()
                sectionId == "new" -> RecommendationJson.parseQqSongs(api.getRecommendNewSongs()).take(30)
                else -> {
                    val sections = loadQqHomeFeed().getOrThrow()
                    val songs = sections
                        .firstOrNull { it.id == sectionId }
                        ?.items
                        ?.filterIsInstance<RecommendationSongItem>()
                        ?.map { it.transientSong }
                        .orEmpty()
                    if (songs.isEmpty()) error("QQ recommendation section is unavailable")
                    feedSongsBySectionId[sectionId] = songs.take(MAX_SECTION_SONGS)
                    feedSongsBySectionId[sectionId].orEmpty()
                }
            }.take(30)
        }
    }

    override suspend fun loadChart(reference: RemoteChartReference): Result<RemoteChartDetail> = withContext(Dispatchers.IO) {
        runCatching {
            val id = reference.remoteId.toIntOrNull() ?: error("Invalid QQ chart id")
            val raw = api.getTopListDetail(id)
            RemoteChartDetail(reference, RecommendationJson.parseQqSongs(raw))
        }
    }

    private suspend fun loadChartPreviews(
        references: List<RemoteChartReference>
    ): List<RemoteChartReference> = coroutineScope {
        references.map { reference ->
            async {
                runCatching {
                    val id = reference.remoteId.toIntOrNull() ?: return@runCatching reference
                    val songs = RecommendationJson.parseQqSongs(api.getTopListDetail(id, count = 6))
                    reference.copy(previewArtworkUris = songs.mapNotNull { it.song.albumArtUriString }.distinct().take(4))
                }.getOrDefault(reference)
            }
        }.awaitAll()
    }

    private suspend fun enrichQqHomeFeedPlaylistCounts(
        sections: List<RecommendationSection>
    ): List<RecommendationSection> = coroutineScope {
        val playlists = sections
            .flatMap { it.items }
            .filterIsInstance<RemotePlaylistReference>()
            .distinctBy { it.remoteId }

        val counts = playlists.map { playlist ->
            async {
                playlist.remoteId.toLongOrNull()?.let { id ->
                    val count = runCatching {
                        RecommendationJson.parseQqPlaylistSongCount(
                            api.getPlaylistDetail(playlistId = id, songBegin = 0, songNum = 1)
                        )
                    }.getOrNull()
                    playlist.remoteId to (count ?: knownQqPlaylistCount(playlist.title))
                }
            }
        }.awaitAll().filterNotNull().toMap()

        sections.map { section ->
            section.copy(
                items = section.items.map { item ->
                    if (item is RemotePlaylistReference && item.songCount <= 0) {
                        item.copy(songCount = counts[item.remoteId] ?: knownQqPlaylistCount(item.title))
                    } else {
                        item
                    }
                }
            )
        }
    }

    private fun knownQqPlaylistCount(title: String): Int =
        if (title.contains("每日30")) 30 else 0

    private suspend fun loadQqHomeFeed(): Result<List<RecommendationSection>> =
        safeRaw { api.getRecommendHomeFeed() }
            .mapCatching { raw -> RecommendationJson.parseQqHomeFeed(raw) }
            .onFailure { error ->
                Timber.w(error, "Recommendation[QqHomeFeed] request or parse failed")
            }

    private fun songSection(
        id: String,
        title: String,
        kind: RecommendationSectionKind,
        songs: List<TransientOnlineSong>
    ) = RecommendationSection(id, title, kind, songs.map(::RecommendationSongItem), hasMore = true)

    private suspend fun <T> safeRaw(block: suspend () -> T): Result<T> = runCatching { block() }

    private companion object {
        const val MAX_HOME_FEED_SECTIONS = 6
        const val MAX_SECTION_SONGS = 30
    }

}

internal object RecommendationJson {
    fun parseNeteaseSongs(raw: String): List<TransientOnlineSong> {
        val root = JSONObject(raw)
        val arrays = listOfNotNull(
            root.optJSONObject("data")?.optJSONArray("dailySongs"),
            root.optJSONObject("playlist")?.optJSONArray("tracks"),
            root.optJSONObject("result")?.optJSONArray("songs"),
            root.optJSONArray("songs"),
            root.optJSONObject("result")?.optJSONArray("result")
        )
        return arrays.flatMap { array ->
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val song = obj.optJSONObject("song") ?: obj
                neteaseSong(song)
            }
        }.distinctBy { it.sourceSongId }
    }

    fun parseNeteasePlaylists(raw: String): List<RemotePlaylistReference> {
        val root = JSONObject(raw)
        val data = root.optJSONObject("data")
        val array = sequenceOf(
            root.optJSONArray("playlists"),
            root.optJSONArray("result"),
            data?.optJSONArray("playlists"),
            data?.optJSONArray("result")
        ).filterNotNull().firstOrNull() ?: JSONArray()
        val parsed = (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.opt("id")?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            RemotePlaylistReference(
                platform = OnlineBrowsePlatform.NETEASE,
                remoteId = id,
                title = item.optString("name").ifBlank { "未命名歌单" },
                subtitle = firstNonBlank(item, "copywriter", "description", "creatorName"),
                artworkUri = normalizeArtwork(
                    firstNonBlank(item, "picUrl", "coverImgUrl", "coverUrl", "coverImgUrlStr")
                ),
                songCount = firstPositiveInt(
                    item,
                    item.optJSONObject("creator"),
                    item.optJSONObject("user"),
                    keys = NETEASE_PLAYLIST_COUNT_KEYS
                ) ?: 0
            )
        }
        if (parsed.isEmpty()) {
            val keys = root.keys().asSequence().toList()
            Timber.w(
                "Recommendation[NeteasePlaylists] empty response rootKeys=%s arraySize=%d",
                keys,
                array.length()
            )
        }
        return parsed
    }

    /**
     * Parse QQ's home recommendation feed without flattening away its
     * shelf/niche structure. A feed is an operations-driven response, so
     * unsupported cards are intentionally ignored one by one.
     */
    fun parseQqHomeFeed(raw: String): List<RecommendationSection> {
        val root = JSONObject(raw)
        val data = root.optJSONObject("request")?.optJSONObject("data")
            ?: root.optJSONObject("data")
            ?: root
        val shelves = data.optJSONArray("v_shelf")
            ?: data.optJSONArray("shelves")
            ?: return emptyList()

        return buildList {
            for (shelfIndex in 0 until shelves.length()) {
                val shelf = shelves.optJSONObject(shelfIndex) ?: continue
                val niches = shelf.optJSONArray("v_niche")
                    ?: shelf.optJSONArray("niches")
                    ?: continue
                for (nicheIndex in 0 until niches.length()) {
                    val niche = niches.optJSONObject(nicheIndex) ?: continue
                    val cards = niche.optJSONArray("v_card")
                        ?: niche.optJSONArray("cards")
                        ?: continue
                    val items = (0 until cards.length())
                        .flatMap { cardIndex ->
                            parseQqHomeCard(cards.optJSONObject(cardIndex))
                        }
                        .distinctBy { it.key }
                    if (items.isEmpty()) continue

                    val shelfId = shelf.opt("id")?.toString().orEmpty().ifBlank { "shelf$shelfIndex" }
                    val nicheId = niche.opt("id")?.toString().orEmpty().ifBlank { "niche$nicheIndex" }
                    val title = firstNonBlank(
                        niche,
                        "title_content",
                        "title_template"
                    ) ?: firstNonBlank(
                        shelf,
                        "title_content",
                        "title_template"
                    ) ?: "QQ推荐"
                    val kind = when {
                        items.any { it is RecommendationSongItem } -> RecommendationSectionKind.DAILY_SONGS
                        items.any { it is RemotePlaylistReference } -> RecommendationSectionKind.RECOMMENDED_PLAYLISTS
                        else -> RecommendationSectionKind.CHART_ENTRY
                    }
                    add(
                        RecommendationSection(
                            id = "qq_feed_${shelfId}_${nicheId}_${shelfIndex}_$nicheIndex",
                            title = title,
                            kind = kind,
                            items = items,
                            hasMore = false
                        )
                    )
                }
            }
        }.also { sections ->
            Timber.d(
                "Recommendation[QqHomeFeed] sections=%d songs=%d playlists=%d charts=%d",
                sections.size,
                sections.sumOf { section -> section.items.count { it is RecommendationSongItem } },
                sections.sumOf { section -> section.items.count { it is RemotePlaylistReference } },
                sections.sumOf { section -> section.items.count { it is RemoteChartReference } }
            )
        }
    }

    private fun parseQqHomeCard(card: JSONObject?): List<RecommendationItem> {
        if (card == null) return emptyList()

        val type = card.optInt("type", -1)
        val jumpType = card.optInt("jumptype", card.optInt("jumpType", -1))

        // QQ's current home feed uses type 500 / jump type 10014 for playlist
        // cards. `cnt` is play count, not song count, so it is not copied into
        // the playlist model.
        if (type == 500 || jumpType == 10014) {
            parseQqHomePlaylist(card)?.let { return listOf(it) }
        }

        val chartId = firstNonBlank(card, "topId", "topid", "top_id")
        if (chartId != null) {
            return listOf(
                RemoteChartReference(
                    platform = OnlineBrowsePlatform.QQ,
                    remoteId = chartId,
                    title = firstNonBlank(card, "title", "name") ?: "QQ榜单",
                    subtitle = firstNonBlank(card, "subtitle", "desc"),
                    artworkUri = normalizeArtwork(firstNonBlank(card, "cover", "picurl", "cover_url"))
                )
            )
        }

        // Song cards are not stable across QQ feed versions. Inspect nested
        // SongList/Tracks/song objects, but only accept objects with a QQ song
        // MID and a song-like title to avoid turning artists into songs.
        return collectObjects(card)
            .asSequence()
            .filter { item ->
                (item.has("mid") || item.has("songmid")) &&
                    (item.has("title") || item.has("songname") || item.has("name"))
            }
            .mapNotNull(::qqSong)
            .distinctBy { it.sourceSongId }
            .map(::RecommendationSongItem)
            .toList()
    }

    private fun parseQqHomePlaylist(card: JSONObject): RemotePlaylistReference? {
        val id = firstNonBlank(card, "dissid", "playlist_id", "id") ?: return null
        val title = firstNonBlank(card, "title", "name", "dissname") ?: return null
        val songCount = firstPositiveInt(
            *collectObjects(card).toTypedArray(),
            keys = QQ_PLAYLIST_COUNT_KEYS
        ) ?: 0
        return RemotePlaylistReference(
            platform = OnlineBrowsePlatform.QQ,
            remoteId = id,
            title = title,
            subtitle = firstNonBlank(card, "subtitle", "desc"),
            artworkUri = normalizeArtwork(firstNonBlank(card, "cover", "picurl", "cover_url", "coverUrl")),
            songCount = songCount
        )
    }

    fun parseQqSongs(raw: String): List<TransientOnlineSong> {
        val root = JSONObject(raw)
        val data = root.optJSONObject("request")?.optJSONObject("data")
        val arrays = listOfNotNull(
            data?.optJSONObject("data")?.optJSONArray("songInfoList"),
            data?.optJSONArray("songInfoList"),
            data?.optJSONObject("body")?.optJSONArray("songlist"),
            data?.optJSONObject("body")?.optJSONArray("item_song"),
            data?.optJSONArray("songlist"),
            root.optJSONArray("songlist")
        )
        val structured = arrays.flatMap { array ->
            (0 until array.length()).mapNotNull { index -> qqSong(array.optJSONObject(index)) }
        }
        if (structured.isNotEmpty()) return structured.distinctBy { it.sourceSongId }

        // Some QQ feed variants do not expose songInfoList. Only inspect objects
        // that carry song-specific fields; recursively treating every object as a
        // song turns singers/albums into fake tracks.
        return collectObjects(root)
            .asSequence()
            .filter { it.has("songmid") || (it.has("interval") && it.has("singer")) }
            .mapNotNull(::qqSong)
            .distinctBy { it.sourceSongId }
            .toList()
    }

    fun parseQqPlaylistSongCount(raw: String): Int? {
        val root = JSONObject(raw)
        val firstPlaylist = root.optJSONArray("cdlist")?.optJSONObject(0) ?: return null
        return firstPositiveInt(
            firstPlaylist,
            keys = listOf("songnum", "song_num", "song_count", "total_song_num", "trackCount")
        )
    }

    fun parseQqPlaylists(raw: String): List<RemotePlaylistReference> {
        val root = JSONObject(raw)
        val list = root.optJSONObject("request")
            ?.optJSONObject("data")
            ?.optJSONArray("List")
        if (list != null) {
            return (0 until list.length()).mapNotNull { index ->
                val playlist = list.optJSONObject(index)
                    ?.optJSONObject("Playlist")
                    ?: return@mapNotNull null
                qqPlaylist(
                    item = playlist.optJSONObject("basic") ?: playlist,
                    fallback = playlist
                )
            }.distinctBy { it.remoteId }
        }

        return collectObjects(root)
            .asSequence()
            .filter { it.has("dissid") || it.has("playlist_id") }
            .mapNotNull { qqPlaylist(it) }
            .distinctBy { it.remoteId }
            .toList()
    }

    private fun qqPlaylist(item: JSONObject, fallback: JSONObject? = null): RemotePlaylistReference? {
        val id = firstNonBlank(item, "dissid", "tid", "playlist_id") ?: return null
        val title = firstNonBlank(item, "dissname", "title", "playlist_name") ?: return null
        val cover = item.optJSONObject("cover")
        val songCount = firstPositiveInt(
            item,
            fallback,
            item.optJSONObject("info"),
            item.optJSONObject("detail")
        ) ?: 0
        return RemotePlaylistReference(
            platform = OnlineBrowsePlatform.QQ,
            remoteId = id,
            title = title,
            subtitle = firstNonBlank(item, "creator_name", "nickname", "desc"),
            artworkUri = normalizeArtwork(
                firstNonBlank(cover, "medium_url", "big_url", "default_url", "small_url")
                    ?: firstNonBlank(item, "cover_url_big", "picurl", "logo")
            ),
            songCount = songCount
        )
    }

    private fun firstPositiveInt(
        vararg objects: JSONObject?,
        keys: List<String> = QQ_PLAYLIST_COUNT_KEYS
    ): Int? {
        return objects.asSequence()
            .filterNotNull()
            .flatMap { objectValue -> keys.asSequence().map { key -> objectValue.opt(key) } }
            .mapNotNull { value ->
                when (value) {
                    is Number -> value.toInt()
                    is String -> value.trim().toIntOrNull()
                    else -> null
                }
            }
            .firstOrNull { it > 0 }
    }

    private val QQ_PLAYLIST_COUNT_KEYS = listOf(
            "song_count",
            "song_num",
            "songnum",
            "song_cnt",
            "songCount",
            "songNum",
            "trackCount",
            "track_count",
            "total_song_num"
        )

    private val NETEASE_PLAYLIST_COUNT_KEYS = listOf(
        "trackCount",
        "track_count",
        "songCount",
        "song_count"
    )

    private fun neteaseSong(obj: JSONObject): TransientOnlineSong? {
        val id = obj.optLong("id", 0L).takeIf { it > 0 } ?: return null
        val title = obj.optString("name").takeIf { it.isNotBlank() } ?: return null
        val artistObjects = obj.optJSONArray("ar") ?: obj.optJSONArray("artists")
        val artists = if (artistObjects == null) emptyList() else (0 until artistObjects.length()).mapNotNull { i ->
            val artist = artistObjects.optJSONObject(i) ?: return@mapNotNull null
            artist.optString("name").takeIf { it.isNotBlank() }
        }
        val artist = artists.joinToString(" / ").ifBlank { "未知艺术家" }
        val album = obj.optJSONObject("al") ?: obj.optJSONObject("album")
        val albumName = album?.optString("name").orEmpty().ifBlank { "未知专辑" }
        return OnlineSongMapper.toTransient(
            source = PluginSource.NETEASE,
            sourceSongId = id.toString(),
            title = title,
            artist = artist,
            album = albumName,
            artworkUrl = album?.optString("picUrl"),
            durationMs = obj.optLong("dt", obj.optLong("duration", 0L)),
            artistNames = artists
        )
    }

    private fun qqSong(obj: JSONObject): TransientOnlineSong? {
        val mid = firstNonBlank(obj, "mid", "songmid") ?: return null
        val title = firstNonBlank(obj, "title", "songname", "name") ?: return null
        val singers = obj.optJSONArray("singer") ?: obj.optJSONArray("singers")
        val artists = if (singers == null) emptyList() else (0 until singers.length()).mapNotNull { i ->
            val singer = singers.optJSONObject(i) ?: return@mapNotNull null
            firstNonBlank(singer, "name", "title")
        }
        val artist = artists.joinToString(" / ")
            .ifBlank { firstNonBlank(obj, "singername", "artist") ?: "未知艺术家" }
        val album = obj.optJSONObject("album")
        val albumName = firstNonBlank(album, "name", "title")
            ?: firstNonBlank(obj, "albumname", "album_name")
            ?: "未知专辑"
        val albumMid = firstNonBlank(obj, "albummid", "album_mid")
            ?: firstNonBlank(album, "mid", "pmid")
        val duration = obj.optLong("interval", 0L).let { if (it > 0) it * 1000 else obj.optLong("duration", 0L) }
        return OnlineSongMapper.toTransient(
            source = PluginSource.QQ_MUSIC,
            sourceSongId = mid,
            title = title,
            artist = artist,
            album = albumName,
            artworkUrl = albumMid?.let { "https://y.qq.com/music/photo_new/T002R300x300M000$it.jpg" },
            durationMs = duration,
            artistNames = artists
        )
    }

    private fun collectObjects(value: Any?, depth: Int = 0): List<JSONObject> {
        if (depth > 8) return emptyList()
        return when (value) {
            is JSONObject -> buildList {
                add(value)
                val keys = value.keys()
                while (keys.hasNext()) addAll(collectObjects(value.opt(keys.next()), depth + 1))
            }
            is JSONArray -> buildList {
                for (i in 0 until value.length()) addAll(collectObjects(value.opt(i), depth + 1))
            }
            else -> emptyList()
        }
    }

    private fun firstNonBlank(obj: JSONObject?, vararg keys: String): String? {
        if (obj == null) return null
        return keys.firstNotNullOfOrNull { key -> obj.optString(key).trim().takeIf { it.isNotBlank() } }
    }

    private fun normalizeArtwork(value: String?): String? = value?.trim()?.takeIf { it.isNotBlank() }?.let {
        if (it.startsWith("http://", true)) "https://${it.substring(7)}" else it
    }

}
