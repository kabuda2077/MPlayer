package com.theveloper.pixelplay.data.repository

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.util.LruCache
import androidx.core.net.toUri
import com.google.gson.JsonParser
import com.google.gson.Gson
import com.kyant.taglib.TagLib
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.model.Lyrics
import com.theveloper.pixelplay.data.model.LyricsSourcePreference
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.model.SyncedLine
import com.theveloper.pixelplay.data.netease.NeteaseRepository
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.data.qqmusic.QqLyricsFetcher
import com.theveloper.pixelplay.utils.LyricsImportSecurity
import com.theveloper.pixelplay.utils.LyricsImportValidationResult
import com.theveloper.pixelplay.utils.LogUtils
import com.theveloper.pixelplay.utils.LyricsUtils
import com.theveloper.pixelplay.utils.NetworkRetryUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private val EMBEDDED_LYRICS_KEYS = listOf("LYRICS", "SYNCEDLYRICS", "TTML", "UNSYNCEDLYRICS")

private fun Lyrics.isValid(): Boolean = !synced.isNullOrEmpty() || !plain.isNullOrEmpty()

internal fun parseBestEmbeddedLyricsField(propertyMap: Map<String, Array<String>>?): Lyrics? {
    var firstPlainLyrics: Lyrics? = null

    EMBEDDED_LYRICS_KEYS.forEach { key ->
        propertyMap?.get(key).orEmpty().forEach { field ->
            if (field.isBlank()) return@forEach

            val parsedLyrics = LyricsUtils.parseLyrics(field)
            if (!parsedLyrics.isValid()) return@forEach

            val localLyrics = parsedLyrics.copy(areFromRemote = false)
            if (!localLyrics.synced.isNullOrEmpty()) return localLyrics
            if (firstPlainLyrics == null) firstPlainLyrics = localLyrics
        }
    }

    return firstPlainLyrics
}

/**
 * LyricsData for JSON disk cache (matches Rhythm's format)
 */
private data class LyricsData(
    val plainLyrics: String?,
    val syncedLyrics: String?,
    val wordByWordLyrics: String? = null,
    val structuredLyrics: String? = null
) {
    fun hasLyrics(): Boolean =
        !plainLyrics.isNullOrBlank() ||
            !syncedLyrics.isNullOrBlank() ||
            !wordByWordLyrics.isNullOrBlank() ||
            !structuredLyrics.isNullOrBlank()
}

@Singleton
class LyricsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val neteaseRepository: NeteaseRepository,
    private val qqLyricsFetcher: QqLyricsFetcher,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val lyricsDao: com.theveloper.pixelplay.data.database.LyricsDao,
    private val okHttpClient: OkHttpClient
) : LyricsRepository {


    companion object {
        private const val TAG = "LyricsRepository"
        
        // Cache sizes (matching Rhythm)
        private const val MAX_LYRICS_CACHE_SIZE = 150
        
        private const val AMLLDB_BASE_URL = "https://amlldb.bikonoo.com"
        private const val AMLLDB_SEARCH_URL = "$AMLLDB_BASE_URL/api/search-lyrics"
        private const val NETWORK_RETRY_ATTEMPTS = 3
        private const val NETWORK_RETRY_INITIAL_DELAY_MS = 500L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val NETEASE_YRC_LINE_REGEX = Regex("""^\[(\d+),(\d+)](.*)$""")
        private val NETEASE_YRC_WORD_REGEX = Regex("""(.*?)\((\d+),(\d+)\)""")
    }

    // Repository scope for background tasks
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Thread-safe LRU cache to avoid race conditions across concurrent lyrics requests.
    private val lyricsCache = LruCache<String, Lyrics>(MAX_LYRICS_CACHE_SIZE)

    // Gson for JSON cache
    private val gson = Gson()

    private suspend fun <T> withNetworkRetry(
        operationName: String,
        maxAttempts: Int = NETWORK_RETRY_ATTEMPTS,
        initialDelayMs: Long = NETWORK_RETRY_INITIAL_DELAY_MS,
        shouldRetry: (Throwable) -> Boolean = { it is IOException || (it is HttpException && (it.code() == 429 || it.code() >= 500)) },
        block: suspend () -> T
    ): T {
        return NetworkRetryUtils.withNetworkRetry(
            operationName = operationName,
            maxAttempts = maxAttempts,
            initialDelayMs = initialDelayMs,
            shouldRetry = shouldRetry,
            onRetry = { attempt, attempts, throwable ->
                Log.d(
                    TAG,
                    "Retrying $operationName after failure ($attempt/$attempts): ${throwable.message}"
                )
            },
            block = block
        )
    }

    private fun Int.isRetryableHttpStatusCode(): Boolean = this == 429 || this in 500..599

    /**
     * Main lyrics fetching method with source preference support.
     */
    override suspend fun getLyrics(
        song: Song,
        sourcePreference: LyricsSourcePreference,
        forceRefresh: Boolean
    ): Lyrics? = withContext(Dispatchers.IO) {
        val cacheKey = generateCacheKey(song.id)
        val bypassMemoryCache = forceRefresh || isNeteaseSong(song)

        Log.d(
            TAG,
            "===== FETCH LYRICS START: ${song.displayArtist} - ${song.title} " +
                "(forceRefresh=$forceRefresh, source=$sourcePreference) ====="
        )

        if (!bypassMemoryCache) {
            lyricsCache.get(cacheKey)?.let { cached ->
                Log.d(TAG, "===== RETURNING IN-MEMORY CACHED LYRICS =====")
                return@withContext cached
            }
        }

        if (!forceRefresh) {
            loadStoredLyrics(song, cacheKey, includeMemoryCache = false)?.let { stored ->
                val enhancedStoredLyrics = if (!stored.first.areFromRemote) {
                    maybeEnhanceLyricsWithQqWordLevel(song, stored.first)
                } else {
                    stored.first
                }
                lyricsCache.put(cacheKey, enhancedStoredLyrics)
                Log.d(TAG, "===== RETURNING STORED LYRICS WITHOUT REMOTE FETCH =====")
                return@withContext enhancedStoredLyrics
            }
        }

        val sourceFetchers = when {
            isLikelyLocalSong(song) -> listOf(
                "Embedded" to suspend { loadEmbeddedLyricsFromMetadata(song) },
                "Local" to suspend { findLocalLyricsFile(song) },
                "Remote" to suspend { fetchLyricsFromRemoteSource(song)?.lyrics }
            )

            else -> listOf(
                "Remote" to suspend { fetchLyricsFromRemoteSource(song)?.lyrics }
            )
        }

        for ((sourceName, fetcher) in sourceFetchers) {
            try {
                var lyrics = fetcher()
                if (lyrics != null && sourceName != "Remote") {
                    lyrics = maybeEnhanceLyricsWithQqWordLevel(song, lyrics)
                }
                if (lyrics != null && lyrics.isValid()) {
                    lyricsCache.put(cacheKey, lyrics)
                    Log.d(TAG, "Found lyrics from $sourceName for: ${song.displayArtist} - ${song.title}")
                    return@withContext lyrics
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error fetching lyrics from $sourceName: ${e.message}")
            }
        }

        Log.d(TAG, "No lyrics found from any source for: ${song.displayArtist} - ${song.title}")
        null
    }

    private suspend fun maybeEnhanceLyricsWithQqWordLevel(song: Song, baseLyrics: Lyrics): Lyrics {
        if (baseLyrics.synced.isNullOrEmpty()) return baseLyrics
        if (baseLyrics.synced.orEmpty().any { !it.words.isNullOrEmpty() }) return baseLyrics

        val options = loadRemoteLyricsOptions()
        val shouldTryQq = options.enableQqLyrics && isNeteaseSong(song)
        if (!shouldTryQq) return baseLyrics

        val qqPayload = fetchQqRemoteLyrics(song) ?: return baseLyrics
        val baseSourceTag = when {
            isLikelyLocalSong(song) -> "local"
            isNeteaseSong(song) -> "embedded"
            else -> "embedded"
        }
        val baseDescriptor = lyricsDescriptor(
            sourceTag = baseSourceTag,
            lyrics = baseLyrics,
            matchType = "id",
            exactPlatformMatch = true
        )
        val qqDescriptor = lyricsDescriptor(
            sourceTag = qqPayload.sourceTag,
            lyrics = qqPayload.lyrics,
            matchType = "fuzzy",
            durationDiffMs = extractDurationDiffMs(qqPayload.rawLyrics),
            exactPlatformMatch = false
        )
        val unified = buildUnifiedLyricsResult(
            song = song,
            primary = baseDescriptor to (baseLyrics to lyricsToRawContent(baseLyrics).orEmpty()),
            wordLevel = qqDescriptor to (qqPayload.lyrics to qqPayload.rawLyrics)
        )
        val merged = unified?.lyrics
        if (merged != null && merged.synced.orEmpty().any { !it.words.isNullOrEmpty() }) {
            Timber.d(
                "Lyrics[Enhance] applied QQ word-level fallback for %s - %s (primary=%s wordLevel=%s)",
                song.displayArtist,
                song.title,
                unified.primarySource?.sourceTag ?: baseSourceTag,
                unified.wordLevelSource?.sourceTag ?: qqPayload.sourceTag
            )
            return merged.copy(areFromRemote = baseLyrics.areFromRemote || qqPayload.lyrics.areFromRemote)
        }
        return baseLyrics
    }

    override suspend fun getStoredLyrics(song: Song): Pair<Lyrics, String>? = withContext(Dispatchers.IO) {
        val cacheKey = generateCacheKey(song.id)
        loadStoredLyrics(song, cacheKey, includeMemoryCache = true)?.also { stored ->
            lyricsCache.put(cacheKey, stored.first)
        }
    }

    private data class RemoteLyricsOptions(
        val enableOnlineTtmlLyrics: Boolean,
        val enableQqLyrics: Boolean
    )

    private data class RemoteLyricsPayload(
        val lyrics: Lyrics,
        val rawLyrics: String,
        val sourceTag: String
    )

    private data class RemoteLyricsCandidate(
        val descriptor: LyricsSourceDescriptor,
        val lyrics: Lyrics,
        val rawLyrics: String
    ) {
        fun asMergeCandidate(): Pair<LyricsSourceDescriptor, Pair<Lyrics, String>> =
            descriptor to (lyrics to rawLyrics)
    }

    private data class LyricsCapabilities(
        val hasWordLevel: Boolean,
        val hasTranslation: Boolean,
        val hasRoman: Boolean
    )

    private data class LyricsSourceDescriptor(
        val sourceTag: String,
        val matchType: String,
        val durationDiffMs: Long? = null,
        val exactPlatformMatch: Boolean = false,
        val capabilities: LyricsCapabilities
    )

    private data class UnifiedLyricsResult(
        val lyrics: Lyrics,
        val primarySource: LyricsSourceDescriptor?,
        val wordLevelSource: LyricsSourceDescriptor?,
        val rawContents: Map<String, String>
    )

    private suspend fun loadRemoteLyricsOptions(): RemoteLyricsOptions {
        val enableOnlineLyrics = userPreferencesRepository.enableQqLyricsFlow.first()
        return RemoteLyricsOptions(
            enableOnlineTtmlLyrics = enableOnlineLyrics && userPreferencesRepository.enableOnlineTtmlLyricsFlow.first(),
            enableQqLyrics = enableOnlineLyrics
        )
    }

    private suspend fun fetchLyricsFromRemoteSource(song: Song): RemoteLyricsPayload? = withContext(Dispatchers.IO) {
        val options = loadRemoteLyricsOptions()
        logLyricsRequest(song, options)
        if (!options.enableQqLyrics) return@withContext null

        val payload = when {
            isNeteaseSong(song) -> fetchNeteaseRemoteLyrics(song, options)
            shouldUseQqLyrics(song, options) -> fetchQqRemoteLyrics(song)
            else -> null
        } ?: return@withContext null

        persistRemoteLyrics(song, payload)
        payload
    }

    private suspend fun fetchNeteaseRemoteLyrics(
        song: Song,
        options: RemoteLyricsOptions
    ): RemoteLyricsPayload? = withContext(Dispatchers.IO) {
        val remoteCandidates = coroutineScope {
            val ttmlDeferred = if (options.enableOnlineTtmlLyrics) {
                async { fetchAmlldbRemoteCandidate(song) }
            } else {
                null
            }
            val officialDeferred = async { fetchNeteaseOfficialCandidate(song) }
            val qqDeferred = if (options.enableQqLyrics) {
                async { fetchQqRemoteCandidate(song) }
            } else {
                null
            }

            listOfNotNull(
                ttmlDeferred?.await(),
                officialDeferred.await(),
                qqDeferred?.await()
            ).map { it.asMergeCandidate() }
        }

        val merged = mergeRemoteLyricsCandidates(song, remoteCandidates)
            ?: return@withContext null

        return@withContext buildRemoteLyricsPayload(merged)
    }

    private suspend fun fetchAmlldbRemoteCandidate(song: Song): RemoteLyricsCandidate? {
        val amlLyrics = fetchFromAmlldb(song)
        if (amlLyrics == null) {
            Timber.d("Lyrics[TTML] miss for %s - %s", song.displayArtist, song.title)
            return null
        }

        val rawLyrics = lyricsToRawContent(amlLyrics) ?: return null
        val lyrics = amlLyrics.copy(areFromRemote = true)
        Timber.d("Lyrics[TTML] hit for %s - %s", song.displayArtist, song.title)
        return RemoteLyricsCandidate(
            descriptor = lyricsDescriptor(
                sourceTag = "amll_ttml",
                lyrics = lyrics,
                matchType = "id",
                exactPlatformMatch = true
            ),
            lyrics = lyrics,
            rawLyrics = rawLyrics
        )
    }

    private suspend fun fetchNeteaseOfficialCandidate(song: Song): RemoteLyricsCandidate? {
        val songId = resolveNeteaseSongId(song) ?: return null
        val officialRawLyrics = withNetworkRetry(operationName = "netease_official_lyrics:$songId") {
            neteaseRepository.getLyricsPayload(songId).getOrNull()
        }?.takeIf { raw -> raw.isNotBlank() }

        val officialLyrics = officialRawLyrics?.let(::parseNeteaseLyricsPayload)
        if (officialLyrics == null) {
            Timber.d("Lyrics[Official] miss for %s - %s", song.displayArtist, song.title)
            return null
        }

        Timber.d(
            "Lyrics[Official] hit for %s - %s (wordLevel=%s, translation=%s, roman=%s)",
            song.displayArtist,
            song.title,
            officialLyrics.synced.orEmpty().any { !it.words.isNullOrEmpty() },
            officialLyrics.synced.orEmpty().any { !it.translation.isNullOrBlank() },
            officialLyrics.synced.orEmpty().any { !it.romanization.isNullOrBlank() }
        )
        return RemoteLyricsCandidate(
            descriptor = lyricsDescriptor(
                sourceTag = "netease_official",
                lyrics = officialLyrics,
                matchType = "id",
                exactPlatformMatch = true
            ),
            lyrics = officialLyrics,
            rawLyrics = officialRawLyrics
        )
    }

    private suspend fun fetchQqRemoteCandidate(song: Song): RemoteLyricsCandidate? {
        val payload = fetchQqRemoteLyrics(song) ?: return null
        return RemoteLyricsCandidate(
            descriptor = lyricsDescriptor(
                sourceTag = payload.sourceTag,
                lyrics = payload.lyrics,
                matchType = "fuzzy",
                durationDiffMs = extractDurationDiffMs(payload.rawLyrics),
                exactPlatformMatch = false
            ),
            lyrics = payload.lyrics,
            rawLyrics = payload.rawLyrics
        )
    }

    private suspend fun fetchQqRemoteLyrics(song: Song): RemoteLyricsPayload? = withContext(Dispatchers.IO) {
        val result = qqLyricsFetcher.fetch(song) ?: run {
            Timber.d("Lyrics[QQ] miss for %s - %s", song.displayArtist, song.title)
            return@withContext null
        }
        val descriptor = LyricsSourceDescriptor(
            sourceTag = result.metadata.sourceTag,
            matchType = result.metadata.matchType,
            durationDiffMs = result.metadata.durationDiffMs,
            exactPlatformMatch = result.metadata.exactPlatformMatch,
            capabilities = LyricsCapabilities(
                hasWordLevel = result.metadata.hasWordLevel,
                hasTranslation = result.metadata.hasTranslation,
                hasRoman = result.metadata.hasRomanization
            )
        )
        logLyricsSourceHit(song, descriptor)
        buildRemoteLyricsPayload(
            UnifiedLyricsResult(
                lyrics = result.lyrics.copy(areFromRemote = true),
                primarySource = descriptor,
                wordLevelSource = descriptor.takeIf { descriptor.capabilities.hasWordLevel },
                rawContents = mapOf(descriptor.sourceTag to result.rawLyrics)
            )
        )
    }

    private fun mergeRemoteLyricsCandidates(
        song: Song,
        candidates: List<Pair<LyricsSourceDescriptor, Pair<Lyrics, String>>>
    ): UnifiedLyricsResult? {
        if (candidates.isEmpty()) return null

        var primary: Pair<LyricsSourceDescriptor, Pair<Lyrics, String>>? = null
        var wordLevel: Pair<LyricsSourceDescriptor, Pair<Lyrics, String>>? = null
        val rawContents = linkedMapOf<String, String>()

        candidates.forEach { candidate ->
            rawContents.putIfAbsent(candidate.first.sourceTag, candidate.second.second)
        }

        primary = candidates.firstOrNull { it.first.sourceTag == "netease_official" }
            ?: candidates.firstOrNull { it.first.sourceTag == "qq_lrc" || it.first.sourceTag == "qq_qrc" }
            ?: candidates.firstOrNull()

        wordLevel = candidates.firstOrNull { it.first.sourceTag == "amll_ttml" && it.first.capabilities.hasWordLevel }
            ?: candidates.firstOrNull { it.first.sourceTag == "netease_official" && it.first.capabilities.hasWordLevel }
            ?: candidates.firstOrNull { it.first.sourceTag == "qq_qrc" && it.first.capabilities.hasWordLevel }
            ?: candidates.firstOrNull { it.first.capabilities.hasWordLevel }

        return buildUnifiedLyricsResult(song, primary, wordLevel, rawContents)
    }

    private fun buildUnifiedLyricsResult(
        song: Song,
        primary: Pair<LyricsSourceDescriptor, Pair<Lyrics, String>>?,
        wordLevel: Pair<LyricsSourceDescriptor, Pair<Lyrics, String>>?,
        existingRawContents: Map<String, String> = emptyMap()
    ): UnifiedLyricsResult? {
        val rawContents = linkedMapOf<String, String>()
        existingRawContents.forEach { (key, value) ->
            if (value.isNotBlank()) rawContents.putIfAbsent(key, value)
        }
        primary?.let { (descriptor, payload) ->
            if (payload.second.isNotBlank()) rawContents.putIfAbsent(descriptor.sourceTag, payload.second)
        }
        wordLevel?.let { (descriptor, payload) ->
            if (payload.second.isNotBlank()) rawContents.putIfAbsent(descriptor.sourceTag, payload.second)
        }

        val mergedLyrics = mergeLyricsPayloads(
            primaryLyrics = primary?.second?.first,
            wordLevelLyrics = wordLevel?.second?.first
        ) ?: return null

        logLyricsMergeSummary(song, primary?.first, wordLevel?.first, mergedLyrics)

        return UnifiedLyricsResult(
            lyrics = mergedLyrics.copy(areFromRemote = true),
            primarySource = primary?.first,
            wordLevelSource = wordLevel?.first,
            rawContents = rawContents
        )
    }

    private fun mergeLyricsPayloads(
        primaryLyrics: Lyrics?,
        wordLevelLyrics: Lyrics?
    ): Lyrics? {
        val base = primaryLyrics ?: wordLevelLyrics ?: return null
        val primarySynced = primaryLyrics?.synced.orEmpty()
        val wordSynced = wordLevelLyrics?.synced.orEmpty()

        if (primarySynced.isEmpty() && wordSynced.isEmpty()) {
            return base.copy(areFromRemote = true)
        }

        val mergedSynced = when {
            primarySynced.isEmpty() -> wordSynced
            wordSynced.isEmpty() -> primarySynced
            else -> mergePrimaryAndWordLevel(primarySynced, wordSynced)
        }

        val mergedPlain = mergedSynced.takeIf { it.isNotEmpty() }
            ?.let(LyricsUtils::rebuildPlainFromSynced)
            ?: base.plain

        return base.copy(
            synced = mergedSynced.ifEmpty { null },
            plain = mergedPlain,
            areFromRemote = true
        )
    }

    private fun mergePrimaryAndWordLevel(
        primaryLines: List<SyncedLine>,
        wordLines: List<SyncedLine>
    ): List<SyncedLine> {
        if (primaryLines.isEmpty()) return wordLines
        if (wordLines.isEmpty()) return primaryLines

        val merged = mutableListOf<SyncedLine>()
        var wordIndex = 0

        for (primary in primaryLines) {
            while (wordIndex < wordLines.size && wordLines[wordIndex].time < primary.time - 300) {
                wordIndex++
            }

            val candidate = wordLines.getOrNull(wordIndex)
            val matched = candidate?.takeIf { kotlin.math.abs(it.time - primary.time) <= 300 }
            if (matched != null) {
                merged += primary.copy(
                    words = matched.words ?: primary.words,
                    translation = primary.translation ?: matched.translation,
                    romanization = primary.romanization ?: matched.romanization
                )
            } else {
                merged += primary
            }
        }

        return merged
    }

    private suspend fun persistRemoteLyrics(song: Song, payload: RemoteLyricsPayload) {
        Timber.d("Lyrics[Persist] source=%s for %s - %s", payload.sourceTag, song.displayArtist, song.title)
        saveLocalLyricsJson(song, payload.lyrics, payload.rawLyrics)
        song.id.toLongOrNull()?.let { songId ->
            runCatching {
                val persistedContent = lyricsToPersistedContent(payload)
                lyricsDao.insert(
                    com.theveloper.pixelplay.data.database.LyricsEntity(
                        songId = songId,
                        content = persistedContent,
                        isSynced = !payload.lyrics.synced.isNullOrEmpty(),
                        source = payload.sourceTag
                    )
                )
            }.onFailure { error ->
                Log.w(TAG, "Failed to persist remote lyrics for ${song.id}: ${error.message}")
            }
        }
    }

    private fun lyricsToPersistedContent(payload: RemoteLyricsPayload): String {
        if (payload.rawLyrics.startsWith("qqlyrics://")) return payload.rawLyrics
        return lyricsToRawContent(payload.lyrics) ?: payload.rawLyrics
    }

    private fun shouldUseQqLyrics(song: Song, options: RemoteLyricsOptions): Boolean {
        if (!options.enableQqLyrics) return false
        return when {
            isQqMusicSong(song) -> true
            isLikelyLocalSong(song) -> true
            else -> true
        }
    }

    private fun parseRawLyricsContent(rawLyrics: String, areFromRemote: Boolean): Lyrics? {
        val parsed = rawLyrics
            .takeIf(::looksLikeNeteaseOfficialLyricsPayload)
            ?.let(::parseNeteaseLyricsPayload)
            ?: qqLyricsFetcher.parsePersistedRaw(rawLyrics)
            ?: LyricsUtils.parseLyrics(rawLyrics)

        return parsed
            .takeIf { it.isValid() }
            ?.takeUnless(LyricsUtils::hasNeteaseJsonLyricGarbage)
            ?.copy(areFromRemote = areFromRemote)
    }

    private fun looksLikeNeteaseOfficialLyricsPayload(rawLyrics: String): Boolean {
        val trimmed = rawLyrics.trimStart()
        if (!trimmed.startsWith("{")) return false
        if (!trimmed.contains("\"lyric\"")) return false
        return listOf("\"lrc\"", "\"tlyric\"", "\"romalrc\"", "\"yrc\"", "\"ytlrc\"", "\"yromalrc\"")
            .any(trimmed::contains)
    }

    internal fun parseNeteaseLyricsPayload(rawPayload: String): Lyrics? {
        val root = runCatching { JsonParser.parseString(rawPayload).asJsonObject }.getOrNull()
            ?: return null

        fun nestedLyric(key: String): String {
            val nested = root.get(key)
            if (nested == null || !nested.isJsonObject) return ""
            val lyricElement = nested.asJsonObject.get("lyric") ?: return ""
            return runCatching { lyricElement.asString }.getOrDefault("")
        }

        val lrc = nestedLyric("lrc")
        val tlyric = nestedLyric("tlyric")
        val romalrc = nestedLyric("romalrc")
        val yrc = nestedLyric("yrc")
        val ytlrc = nestedLyric("ytlrc")
        val yromalrc = nestedLyric("yromalrc")

        val primaryLyrics = buildNeteaseLineLyrics(
            lyric = lrc,
            translatedLyric = tlyric,
            romanizedLyric = romalrc
        )

        val wordLevelLyrics = buildNeteaseWordLevelLyrics(
            yrc = yrc,
            translatedLyric = ytlrc,
            romanizedLyric = yromalrc
        )

        if (primaryLyrics != null && wordLevelLyrics == null) {
            return primaryLyrics.copy(areFromRemote = true)
        }
        if (primaryLyrics == null && wordLevelLyrics != null) {
            return wordLevelLyrics.copy(areFromRemote = true)
        }
        return mergeLyricsPayloads(primaryLyrics, wordLevelLyrics)?.copy(areFromRemote = true)
            ?: if (lrc.isNotBlank()) LyricsUtils.parseLyrics(lrc)
                .takeIf { it.isValid() }
                ?.takeUnless(LyricsUtils::hasNeteaseJsonLyricGarbage)
                ?.copy(areFromRemote = true) else null
    }

    private fun buildNeteaseLineLyrics(
        lyric: String,
        translatedLyric: String,
        romanizedLyric: String
    ): Lyrics? {
        if (lyric.isBlank()) return null
        var parsed = LyricsUtils.parseLyrics(lyric)

        if (parsed.synced.isNullOrEmpty() && parsed.plain.isNullOrEmpty()) return null

        parsed = parsed.copy(
            synced = LyricsUtils.mergeSupplementalLines(
                baseLines = parsed.synced.orEmpty(),
                translationLines = LyricsUtils.parseSameTimestampBilingualLrc(translatedLyric).synced.orEmpty(),
                romanizationLines = LyricsUtils.parseLyrics(romanizedLyric).synced.orEmpty()
            )
        )

        return parsed.copy(
            plain = LyricsUtils.rebuildPlainFromSynced(parsed.synced.orEmpty()).ifEmpty { parsed.plain },
            areFromRemote = true
        )
    }

    private fun buildNeteaseWordLevelLyrics(
        yrc: String,
        translatedLyric: String,
        romanizedLyric: String
    ): Lyrics? {
        if (yrc.isBlank()) return null
        val parsedYrc = parseNeteaseYrc(yrc) ?: return null
        val merged = LyricsUtils.mergeSupplementalLines(
            baseLines = parsedYrc.synced.orEmpty(),
            translationLines = LyricsUtils.parseSameTimestampBilingualLrc(translatedLyric).synced.orEmpty(),
            romanizationLines = LyricsUtils.parseLyrics(romanizedLyric).synced.orEmpty()
        )

        return parsedYrc.copy(
            synced = merged,
            plain = LyricsUtils.rebuildPlainFromSynced(merged),
            areFromRemote = true
        )
    }

    private fun parseNeteaseYrc(raw: String): Lyrics? {
        val syncedLines = raw.lines().mapNotNull { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@mapNotNull null
            val match = NETEASE_YRC_LINE_REGEX.matchEntire(line) ?: return@mapNotNull null
            val lineTime = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val body = match.groupValues[3]
            val words = mutableListOf<com.theveloper.pixelplay.data.model.SyncedWord>()
            val plainText = StringBuilder()
            var previousEndsWithSpace = true

            NETEASE_YRC_WORD_REGEX.findAll(body).forEach { wordMatch ->
                val text = wordMatch.groupValues[1]
                val wordOffset = wordMatch.groupValues[2].toIntOrNull() ?: 0
                val cleaned = text.trim()
                plainText.append(text)
                if (cleaned.isNotEmpty()) {
                    val startsNew = previousEndsWithSpace || text.firstOrNull()?.isWhitespace() == true || words.isEmpty()
                    words += com.theveloper.pixelplay.data.model.SyncedWord(
                        time = lineTime + wordOffset,
                        word = cleaned,
                        startsNewWord = startsNew
                    )
                }
                previousEndsWithSpace = text.lastOrNull()?.isWhitespace() == true
            }

            val lineText = plainText.toString().trim()
            if (lineText.isEmpty() && words.isEmpty()) return@mapNotNull null
            SyncedLine(
                time = lineTime,
                line = lineText,
                words = words.takeIf { it.isNotEmpty() }
            )
        }

        if (syncedLines.isEmpty()) return null
        val plain = syncedLines.map { it.line }
        return Lyrics(synced = syncedLines, plain = plain, areFromRemote = true)
    }

    private fun isNeteaseSong(song: Song): Boolean =
        song.neteaseId != null || song.contentUriString.startsWith("netease://")

    private fun isQqMusicSong(song: Song): Boolean =
        song.qqMusicMid != null || song.contentUriString.startsWith("qqmusic://")

    private fun isLikelyLocalSong(song: Song): Boolean = isLocalMetadataReadableUri(song.contentUriString)

    private fun lyricsDescriptor(
        sourceTag: String,
        lyrics: Lyrics,
        matchType: String,
        durationDiffMs: Long? = null,
        exactPlatformMatch: Boolean = false
    ): LyricsSourceDescriptor = LyricsSourceDescriptor(
        sourceTag = sourceTag,
        matchType = matchType,
        durationDiffMs = durationDiffMs,
        exactPlatformMatch = exactPlatformMatch,
        capabilities = lyricsCapabilities(lyrics)
    )

    private fun lyricsCapabilities(lyrics: Lyrics): LyricsCapabilities {
        val synced = lyrics.synced.orEmpty()
        return LyricsCapabilities(
            hasWordLevel = synced.any { !it.words.isNullOrEmpty() },
            hasTranslation = synced.any { !it.translation.isNullOrBlank() },
            hasRoman = synced.any { !it.romanization.isNullOrBlank() }
        )
    }

    private fun buildRemoteLyricsPayload(result: UnifiedLyricsResult): RemoteLyricsPayload? {
        val primarySourceTag = result.primarySource?.sourceTag ?: result.wordLevelSource?.sourceTag ?: return null
        val raw = result.rawContents[primarySourceTag]
            ?: result.wordLevelSource?.sourceTag?.let { result.rawContents[it] }
            ?: lyricsToRawContent(result.lyrics)
            ?: return null

        return RemoteLyricsPayload(
            lyrics = result.lyrics,
            rawLyrics = raw,
            sourceTag = primarySourceTag
        )
    }

    private fun extractDurationDiffMs(rawLyrics: String): Long? = runCatching {
        if (!rawLyrics.startsWith("qqlyrics://")) return@runCatching null
        null
    }.getOrNull()

    private fun logLyricsRequest(song: Song, options: RemoteLyricsOptions) {
        Timber.d(
            "Lyrics[Request] platform=%s title=%s artist=%s durationMs=%s online=%s ttml=%s",
            when {
                isNeteaseSong(song) -> "netease"
                isQqMusicSong(song) -> "qq"
                isLikelyLocalSong(song) -> "local"
                else -> "other"
            },
            song.title,
            song.displayArtist,
            song.duration,
            options.enableQqLyrics,
            options.enableOnlineTtmlLyrics
        )
    }

    private fun logLyricsSourceHit(song: Song, descriptor: LyricsSourceDescriptor) {
        Timber.d(
            "Lyrics[Hit] source=%s match=%s durationDiffMs=%s wordLevel=%s translation=%s roman=%s exact=%s song=%s - %s",
            descriptor.sourceTag,
            descriptor.matchType,
            descriptor.durationDiffMs,
            descriptor.capabilities.hasWordLevel,
            descriptor.capabilities.hasTranslation,
            descriptor.capabilities.hasRoman,
            descriptor.exactPlatformMatch,
            song.displayArtist,
            song.title
        )
    }

    private fun logLyricsMergeSummary(
        song: Song,
        primary: LyricsSourceDescriptor?,
        wordLevel: LyricsSourceDescriptor?,
        lyrics: Lyrics
    ) {
        val capabilities = lyricsCapabilities(lyrics)
        Timber.d(
            "Lyrics[Final] primary=%s wordLevel=%s translation=%s roman=%s hasWordLevel=%s song=%s - %s",
            primary?.sourceTag ?: "none",
            wordLevel?.sourceTag ?: "none",
            capabilities.hasTranslation,
            capabilities.hasRoman,
            capabilities.hasWordLevel,
            song.displayArtist,
            song.title
        )
    }

    private fun resolveNeteaseSongId(song: Song): Long? {
        song.neteaseId?.let { return it }
        if (!song.contentUriString.startsWith("netease://")) return null
        return Uri.parse(song.contentUriString).host?.toLongOrNull()
    }

    private data class AmlldbSearchResult(
        val title: String,
        val artist: String,
        val file: String
    )

    private suspend fun fetchFromAmlldb(song: Song): Lyrics? = withContext(Dispatchers.IO) {
        val neteaseSongId = resolveNeteaseSongId(song) ?: return@withContext null

        try {
            fetchAmlldbNcmTtml(neteaseSongId)
                ?.let { ttml ->
                    parseAmlldbTtml(ttml)?.also {
                        Log.d(TAG, "AMLLDB direct NCM lyrics hit for songId=$neteaseSongId")
                    }
                }
                ?.let { return@withContext it }

            val searchHit = searchAmlldbByMetadata(song).firstOrNull()
                ?: return@withContext null
            val rawTtml = fetchAmlldbRawLyrics(searchHit.file)
                ?: return@withContext null

            parseAmlldbTtml(rawTtml)?.also {
                Log.d(
                    TAG,
                    "AMLLDB search lyrics hit for songId=$neteaseSongId " +
                        "title=${searchHit.title} artist=${searchHit.artist} file=${searchHit.file}"
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "AMLLDB fetch failed for $neteaseSongId: ${e.message}")
            return@withContext null
        }
    }

    private suspend fun fetchAmlldbNcmTtml(neteaseSongId: Long): String? {
        val request = Request.Builder()
            .url("$AMLLDB_BASE_URL/ncm-lyrics/$neteaseSongId.ttml")
            .get()
            .build()

        return fetchAmlldbTtml(request, operationName = "amlldb_ncm_fetch:$neteaseSongId")
    }

    private suspend fun fetchAmlldbRawLyrics(fileName: String): String? {
        val safeFileName = fileName.substringAfterLast('/').takeIf { it.endsWith(".ttml") } ?: return null
        val request = Request.Builder()
            .url("$AMLLDB_BASE_URL/raw-lyrics/$safeFileName")
            .get()
            .build()

        return fetchAmlldbTtml(request, operationName = "amlldb_raw_fetch:$safeFileName")
    }

    private suspend fun fetchAmlldbTtml(
        request: Request,
        operationName: String
    ): String? {
        val ttml = withNetworkRetry(
            operationName = operationName,
            shouldRetry = { throwable -> throwable is IOException }
        ) {
            okHttpClient.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> response.body.string()
                    response.code.isRetryableHttpStatusCode() ->
                        throw IOException("AMLLDB HTTP ${response.code} for ${request.url}")
                    else -> ""
                }
            }
        }

        return ttml.takeIf { it.isNotBlank() && !it.contains("歌词不存在") }
    }

    private suspend fun searchAmlldbByMetadata(song: Song): List<AmlldbSearchResult> {
        val title = song.title.trim()
        if (title.isBlank()) return emptyList()

        val body = JSONObject()
            .put("query", title)
            .put("type", "title")
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(AMLLDB_SEARCH_URL)
            .post(body)
            .build()

        val raw = withNetworkRetry(
            operationName = "amlldb_search:${song.id}",
            shouldRetry = { throwable -> throwable is IOException }
        ) {
            okHttpClient.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> response.body.string()
                    response.code.isRetryableHttpStatusCode() ->
                        throw IOException("AMLLDB search HTTP ${response.code}")
                    else -> "[]"
                }
            }
        }

        return parseAmlldbSearchResults(raw)
            .filter { result -> result.file.endsWith(".ttml") }
            .filter { result -> result.matchesSong(song) }
    }

    private fun parseAmlldbSearchResults(rawJson: String): List<AmlldbSearchResult> {
        val array = runCatching { JSONArray(rawJson) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val file = item.optString("file").takeIf { it.isNotBlank() } ?: continue
                add(
                    AmlldbSearchResult(
                        title = item.optString("title"),
                        artist = item.optString("artist"),
                        file = file
                    )
                )
            }
        }
    }

    private fun AmlldbSearchResult.matchesSong(song: Song): Boolean {
        val normalizedSongTitle = normalizeAmlldbMatchText(song.title)
        val normalizedResultTitle = normalizeAmlldbMatchText(title)
        if (normalizedSongTitle.isBlank() || normalizedResultTitle != normalizedSongTitle) {
            return false
        }

        val songArtistTokens = normalizeAmlldbMatchText(song.displayArtist)
            .split(' ')
            .filter { it.isNotBlank() }
            .toSet()
        if (songArtistTokens.isEmpty()) return true

        val resultArtist = normalizeAmlldbMatchText(artist)
        return songArtistTokens.any { token -> resultArtist.contains(token) }
    }

    private fun normalizeAmlldbMatchText(value: String): String =
        value
            .lowercase(Locale.ROOT)
            .replace("&", " ")
            .replace(Regex("""[\p{Punct}\p{IsPunctuation}]+"""), " ")
            .trim()
            .replace(Regex("""\s+"""), " ")

    private fun parseAmlldbTtml(ttml: String): Lyrics? {
        val lrc = convertAmlTtmlToLrc(ttml) ?: return null
        val parsed = LyricsUtils.parseLyrics(lrc)
        return parsed
            .takeIf { it.isValid() }
            ?.copy(areFromRemote = true)
    }

    private fun convertAmlTtmlToLrc(ttml: String): String? {
        val lineRegex = Regex(
            "<p\\b[^>]*\\bbegin=\"([^\"]+)\"[^>]*>(.*?)</p>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val spanRegex = Regex(
            "<span\\b([^>]*)>(.*?)</span>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val beginAttrRegex = Regex("\\bbegin=\"([^\"]+)\"")
        val roleAttrRegex = Regex("\\bttm:role=\"([^\"]+)\"")

        val lrcLines = mutableListOf<String>()
        lineRegex.findAll(ttml).forEach { lineMatch ->
            val lineStartMs = parseTtmlTimeToMs(lineMatch.groupValues[1]) ?: return@forEach
            var inner = lineMatch.groupValues[2]
            val translationLines = mutableListOf<String>()
            val markerRegex = Regex("§§TS\\(([^)]+)\\)§§")

            inner = spanRegex.replace(inner) { spanMatch ->
                val attributes = spanMatch.groupValues[1]
                val role = roleAttrRegex.find(attributes)?.groupValues?.getOrNull(1)?.lowercase()
                if (role == "x-roman") {
                    return@replace ""
                }
                val wordStartMs = beginAttrRegex
                    .find(attributes)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.let(::parseTtmlTimeToMs)
                val text = decodeXmlEntities(spanMatch.groupValues[2])

                if (role == "x-translation") {
                    text.trim().takeIf { it.isNotBlank() }?.let(translationLines::add)
                    return@replace ""
                }

                if (wordStartMs == null) {
                    // Keep visible non-timed text but do not inject word timing.
                    return@replace text
                }

                "§§TS(${formatTimestamp(wordStartMs.toInt())})§§$text"
            }

            val withoutXmlTags = decodeXmlEntities(inner.replace(Regex("<[^>]+>"), ""))
            val lrcInlineTagged = markerRegex.replace(withoutXmlTags, "<$1>")
            if (lrcInlineTagged.isBlank()) return@forEach

            lrcLines += "[${formatTimestamp(lineStartMs.toInt())}]$lrcInlineTagged"
            translationLines.forEach { translation ->
                lrcLines += "[${formatTimestamp(lineStartMs.toInt())}]$translation"
            }
        }

        return lrcLines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    private fun parseTtmlTimeToMs(value: String): Long? {
        val raw = value.trim()
        if (raw.isEmpty()) return null

        if (raw.endsWith("s")) {
            val seconds = raw.removeSuffix("s").toDoubleOrNull() ?: return null
            return (seconds * 1000.0).toLong()
        }

        val parts = raw.split(":")
        val secondsPart = parts.lastOrNull()?.toDoubleOrNull() ?: return null
        return when (parts.size) {
            3 -> {
                val hours = parts[0].toLongOrNull() ?: return null
                val minutes = parts[1].toLongOrNull() ?: return null
                (hours * 3_600_000L) + (minutes * 60_000L) + (secondsPart * 1000.0).toLong()
            }
            2 -> {
                val minutes = parts[0].toLongOrNull() ?: return null
                (minutes * 60_000L) + (secondsPart * 1000.0).toLong()
            }
            1 -> (secondsPart * 1000.0).toLong()
            else -> null
        }
    }

    private fun decodeXmlEntities(value: String): String =
        value
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")

    /**
     * Find local .lrc file next to the music file (matching Rhythm)
     */
    private suspend fun findLocalLyricsFile(song: Song): Lyrics? = withContext(Dispatchers.IO) {
        try {
            val songFile = File(song.path)
            val directory = songFile.parentFile ?: return@withContext null
            val songNameWithoutExt = songFile.nameWithoutExtension

            if (directory.exists()) {
                for (extension in LyricsImportSecurity.supportedFileExtensions()) {
                    val lyricsFile = File(directory, "$songNameWithoutExt.$extension")
                    if (!lyricsFile.exists() || !lyricsFile.canRead()) continue

                    val validated = readValidatedLocalLyrics(lyricsFile)
                    if (validated != null) {
                        Log.d(TAG, "===== FOUND LOCAL LYRICS FILE: ${lyricsFile.name} =====")
                        return@withContext validated.parsedLyrics
                    }
                }

                val cleanArtist = song.displayArtist.replace(Regex("[^a-zA-Z0-9]"), "_")
                val cleanTitle = song.title.replace(Regex("[^a-zA-Z0-9]"), "_")

                for (extension in LyricsImportSecurity.supportedFileExtensions()) {
                    val alternativeLyricsFile = File(directory, "${cleanArtist}_${cleanTitle}.$extension")
                    if (!alternativeLyricsFile.exists() || !alternativeLyricsFile.canRead()) continue

                    val validated = readValidatedLocalLyrics(alternativeLyricsFile)
                    if (validated != null) {
                        Log.d(TAG, "===== FOUND LOCAL LYRICS FILE (alt pattern): ${alternativeLyricsFile.name} =====")
                        return@withContext validated.parsedLyrics
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error searching for local lyrics file", e)
        }
        return@withContext null
    }

    private fun readValidatedLocalLyrics(file: File): com.theveloper.pixelplay.utils.ValidatedLyricsImport? {
        return when (val validation = LyricsImportSecurity.validateLocalLyricsFile(file)) {
            is LyricsImportValidationResult.Valid -> validation.value
            is LyricsImportValidationResult.Invalid -> null
        }
    }

    /**
     * Save lyrics to JSON disk cache (matching Rhythm)
     */
    private fun saveLocalLyricsJson(song: Song, lyrics: Lyrics, rawLyricsOverride: String? = null) {
        try {
            val fileName = "${song.id}.json"
            val lyricsDir = File(context.filesDir, "lyrics")
            lyricsDir.mkdirs()

            val wordByWordLyrics = lyrics.synced
                ?.takeIf { lines -> lines.any { !it.words.isNullOrEmpty() } }
                ?.let(::toWordByWordLrc)

            val lyricsData = LyricsData(
                plainLyrics = lyrics.plain?.joinToString("\n"),
                syncedLyrics = lyrics.synced?.let(LyricsUtils::syncedToLrcString),
                wordByWordLyrics = wordByWordLyrics,
                structuredLyrics = rawLyricsOverride?.takeIf { it.startsWith("qqlyrics://") }
            )

            val file = File(lyricsDir, fileName)
            val json = gson.toJson(lyricsData)
            file.writeText(json)
            Log.d(TAG, "Saved lyrics to JSON cache: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving lyrics to JSON cache: ${e.message}", e)
        }
    }

    /**
     * Load lyrics from JSON disk cache (matching Rhythm)
     */
    private suspend fun loadLocalLyricsJson(song: Song): Lyrics? {
        try {
            val data = readLyricsJsonCache(song) ?: return null
            if (data.hasLyrics()) {
                val rawLyrics = data.structuredLyrics ?: data.wordByWordLyrics ?: data.syncedLyrics ?: data.plainLyrics
                val parsed = rawLyrics?.let { parseRawLyricsContent(it, areFromRemote = true) }
                if (parsed != null && parsed.isValid()) {
                    val hasWordTimestamps = parsed.synced?.any { !it.words.isNullOrEmpty() } == true
                    if (!hasWordTimestamps && data.wordByWordLyrics.isNullOrBlank()) {
                        // Legacy cache may have flattened word-by-word lines.
                        // Recover richer raw lyrics from DB when available.
                        val persistedContent = song.id.toLongOrNull()
                            ?.let { lyricsDao.getLyrics(it)?.content }
                            ?.takeIf { it.isNotBlank() }
                        if (persistedContent != null) {
                            val recovered = LyricsUtils.parseLyrics(persistedContent)
                            val recoveredHasWords = recovered.synced?.any { !it.words.isNullOrEmpty() } == true
                            if (recovered.isValid() && recoveredHasWords) {
                                saveLocalLyricsJson(song, recovered)
                                return recovered
                            }
                        }

                        if (looksLikeFlattenedWordByWordCache(parsed)) {
                            // Force a remote re-fetch instead of serving degraded cache.
                            return null
                        }
                    }
                    return parsed
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading JSON cache: ${e.message}", e)
        }
        return null
    }

    private fun formatTimestamp(timeMs: Int): String {
        val totalSeconds = timeMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val hundredths = (timeMs % 1000) / 10
        return String.format("%02d:%02d.%02d", minutes, seconds, hundredths)
    }

    private fun toWordByWordLrc(lines: List<SyncedLine>): String {
        return lines.flatMap { line ->
            val linePrefix = "[${formatTimestamp(line.time)}]"
            val words = line.words
            val primaryLine = if (words.isNullOrEmpty()) {
                linePrefix + line.line
            } else {
                val wordsPart = words.mapIndexed { index, word ->
                    val separator = if (index > 0 && word.startsNewWord) " " else ""
                    "$separator<${formatTimestamp(word.time)}>${word.word}"
                }
                    .joinToString("")
                linePrefix + wordsPart
            }
            buildList {
                add(primaryLine)
                line.translation
                    ?.lines()
                    ?.filter { it.isNotBlank() }
                    ?.forEach { translationLine ->
                        add(linePrefix + translationLine)
                    }
            }
        }.joinToString("\n")
    }

    private fun looksLikeFlattenedWordByWordCache(lyrics: Lyrics): Boolean {
        val synced = lyrics.synced ?: return false
        var suspiciousLines = 0

        for (line in synced) {
            val text = line.line
            if (text.isBlank() || text.any { it.isWhitespace() }) continue

            val hasLongLatinRun = Regex("[A-Za-z]{10,}").containsMatchIn(text)
            if (hasLongLatinRun) {
                suspiciousLines += 1
                if (suspiciousLines >= 2) return true
            }
        }

        return false
    }

    /**
     * Load embedded lyrics from audio file metadata
     */
    private suspend fun loadEmbeddedLyricsFromMetadata(song: Song): Lyrics? = withContext(Dispatchers.IO) {
        // Embedded lyrics are only available from local files/content URIs. Cloud provider
        // schemes are resolved at playback time and cannot be copied through ContentResolver.
        if (!isLocalMetadataReadableUri(song.contentUriString)) {
            return@withContext null
        }

        // Then try to read from file metadata
        return@withContext try {
            val uri = song.contentUriString.toUri()
            val tempFile = createTempFileFromUri(uri)
            if (tempFile == null) {
                LogUtils.w(this@LyricsRepositoryImpl, "Could not create temp file from URI: ${song.contentUriString}")
                return@withContext null
            }

            try {
                ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    val metadata = TagLib.getMetadata(fd.detachFd())
                    val propertyMap = metadata?.propertyMap
                    val parsedLyrics = parseBestEmbeddedLyricsField(propertyMap)

                    if (parsedLyrics != null) {
                        Log.d(TAG, "===== FOUND EMBEDDED LYRICS =====")
                        parsedLyrics
                    } else {
                        null
                    }
                }
            } finally {
                tempFile.delete()
            }
        } catch (e: Exception) {
            LogUtils.e(this@LyricsRepositoryImpl, e, "Error reading lyrics from file metadata")
            null
        }
    }

    private fun isLocalMetadataReadableUri(contentUriString: String): Boolean {
        val normalized = contentUriString.trim().lowercase(Locale.ROOT)
        if (normalized.isBlank()) return false
        return !normalized.startsWith("telegram://") &&
            !normalized.startsWith("telegram:") &&
            !normalized.startsWith("netease://") &&
            !normalized.startsWith("netease:") &&
            !normalized.startsWith("qqmusic://") &&
            !normalized.startsWith("qqmusic:") &&
            !normalized.startsWith("gdrive://") &&
            !normalized.startsWith("gdrive:") &&
            !normalized.startsWith("navidrome://") &&
            !normalized.startsWith("navidrome:") &&
            !normalized.startsWith("jellyfin://") &&
            !normalized.startsWith("jellyfin:")
    }

    private suspend fun loadStoredLyrics(
        song: Song,
        cacheKey: String,
        includeMemoryCache: Boolean
    ): Pair<Lyrics, String>? = withContext(Dispatchers.IO) {
        song.lyrics
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { rawLyrics ->
                parseStoredLyrics(rawLyrics)?.let { return@withContext it to rawLyrics }
            }

        song.id.toLongOrNull()
            ?.let { lyricsDao.getLyrics(it)?.content }
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { rawLyrics ->
                parseStoredLyrics(rawLyrics)?.let { return@withContext it to rawLyrics }
            }

        readLyricsJsonCache(song)
            ?.takeIf { it.hasLyrics() }
            ?.let { data ->
                val rawLyrics = data.structuredLyrics ?: data.wordByWordLyrics ?: data.syncedLyrics ?: data.plainLyrics
                if (!rawLyrics.isNullOrBlank()) {
                    parseStoredLyrics(rawLyrics)?.let { return@withContext it to rawLyrics }
                }
            }

        if (includeMemoryCache) {
            lyricsCache.get(cacheKey)?.let { cached ->
                lyricsToRawContent(cached)?.let { rawLyrics ->
                    return@withContext cached to rawLyrics
                }
            }
        }

        null
    }

    private fun parseStoredLyrics(rawLyrics: String): Lyrics? =
        parseRawLyricsContent(rawLyrics, areFromRemote = false)

    private fun lyricsToRawContent(lyrics: Lyrics): String? {
        val syncedLyrics = lyrics.synced
        if (!syncedLyrics.isNullOrEmpty()) {
            val hasWordTimestamps = syncedLyrics.any { !it.words.isNullOrEmpty() }
            return if (hasWordTimestamps) {
                toWordByWordLrc(syncedLyrics)
            } else {
                syncedLyrics.joinToString("\n") { line ->
                    "[${formatTimestamp(line.time)}]${line.line}"
                }
            }
        }

        return lyrics.plain
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString("\n")
            ?.takeIf { it.isNotBlank() }
    }

    private fun readLyricsJsonCache(song: Song): LyricsData? {
        val fileName = "${song.id}.json"
        val file = File(context.filesDir, "lyrics/$fileName")
        if (!file.exists()) return null

        val json = file.readText()
        return gson.fromJson(json, LyricsData::class.java)
    }

    override suspend fun fetchFromRemote(song: Song): Result<Pair<Lyrics, String>> = withContext(Dispatchers.IO) {
        try {
            LogUtils.d(this@LyricsRepositoryImpl, "Fetching lyrics from remote for: ${song.title}")

            val cacheKey = generateCacheKey(song.id)
            val payload = fetchLyricsFromRemoteSource(song)
                ?: return@withContext Result.failure(NoLyricsFoundException())

            lyricsCache.put(cacheKey, payload.lyrics)
            Result.success(payload.lyrics to payload.rawLyrics)
        } catch (e: Exception) {
            LogUtils.e(this@LyricsRepositoryImpl, e, "Error fetching lyrics from remote")
            when {
                e is HttpException && e.code() == 404 -> Result.failure(NoLyricsFoundException())
                e is SocketTimeoutException -> Result.failure(LyricsException(context.getString(R.string.lyrics_fetch_timeout), e))
                e is UnknownHostException -> Result.failure(LyricsException(context.getString(R.string.lyrics_network_error), e))
                e is IOException -> Result.failure(LyricsException(context.getString(R.string.lyrics_network_error), e))
                e is HttpException -> Result.failure(LyricsException(context.getString(R.string.lyrics_server_error, e.code()), e))
                else -> Result.failure(LyricsException(context.getString(R.string.lyrics_failed_to_fetch_from_remote), e))
            }
        }
    }

    override suspend fun searchRemote(song: Song): Result<Pair<String, List<LyricsSearchResult>>> = withContext(Dispatchers.IO) {
        try {
            val options = loadRemoteLyricsOptions()
            if (!options.enableQqLyrics) {
                return@withContext Result.failure(NoLyricsFoundException("${song.title} ${song.displayArtist}".trim()))
            }

            val query = listOf(song.title, song.displayArtist)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .trim()
            val results = qqLyricsFetcher.search(song)

            if (results.isEmpty()) {
                Result.failure(NoLyricsFoundException(query))
            } else {
                Result.success(query to results)
            }
        } catch (e: Exception) {
            LogUtils.e(this@LyricsRepositoryImpl, e, "Error searching remote for lyrics")
            when {
                e is SocketTimeoutException -> Result.failure(LyricsException(context.getString(R.string.lyrics_fetch_timeout), e))
                e is UnknownHostException -> Result.failure(LyricsException(context.getString(R.string.lyrics_network_error), e))
                e is IOException -> Result.failure(LyricsException(context.getString(R.string.lyrics_network_error), e))
                e is HttpException -> Result.failure(LyricsException(context.getString(R.string.lyrics_server_error, e.code()), e))
                else -> Result.failure(LyricsException(context.getString(R.string.lyrics_failed_to_search), e))
            }
        }
    }

    override suspend fun searchRemoteByQuery(title: String, artist: String?): Result<Pair<String, List<LyricsSearchResult>>> = withContext(Dispatchers.IO) {
        try {
            val options = loadRemoteLyricsOptions()
            val query = listOfNotNull(
                title.trim().takeIf { it.isNotBlank() },
                artist?.trim()?.takeIf { it.isNotBlank() }
            ).joinToString(" ")

            if (!options.enableQqLyrics) {
                return@withContext Result.failure(NoLyricsFoundException(query))
            }

            val results = qqLyricsFetcher.searchByQuery(
                title = title,
                artist = artist
            )
            if (results.isEmpty()) {
                Result.failure(NoLyricsFoundException(query))
            } else {
                Result.success(query to results)
            }
        } catch (e: Exception) {
            LogUtils.e(this@LyricsRepositoryImpl, e, "Manual search failed")
            Result.failure(LyricsException(context.getString(R.string.lyrics_failed_to_search), e))
        }
    }

    override suspend fun updateLyrics(songId: Long, lyricsContent: String): Unit = withContext(Dispatchers.IO) {
        LogUtils.d(this@LyricsRepositoryImpl, "Updating lyrics for songId: $songId")

        val parsedLyrics = LyricsUtils.parseLyrics(lyricsContent)
        if (!parsedLyrics.isValid()) {
            LogUtils.w(this@LyricsRepositoryImpl, "Attempted to save empty lyrics for songId: $songId")
            return@withContext
        }

        lyricsDao.insert(
             com.theveloper.pixelplay.data.database.LyricsEntity(
                 songId = songId,
                 content = lyricsContent,
                 isSynced = parsedLyrics.synced?.isNotEmpty() == true,
                 source = "manual"
             )
        )

        val cacheKey = generateCacheKey(songId.toString())
        lyricsCache.put(cacheKey, parsedLyrics)
        LogUtils.d(this@LyricsRepositoryImpl, "Updated and cached lyrics for songId: $songId")
    }

    override suspend fun resetLyrics(songId: Long): Unit = withContext(Dispatchers.IO) {
        LogUtils.d(this, "Resetting lyrics for songId: $songId")
        val cacheKey = generateCacheKey(songId.toString())
        lyricsCache.remove(cacheKey)
        try {
            lyricsDao.deleteLyrics(songId)
        } catch (e: Exception) {
            Log.w(TAG, "Error removing lyrics from DB for ID: $songId", e)
        }
        
        // Also remove JSON cache
        try {
            val file = File(context.filesDir, "lyrics/${songId}.json")
            if (file.exists()) file.delete()
        } catch (e: Exception) {
            Log.w(TAG, "Error deleting JSON cache: ${e.message}")
        }
    }

    override suspend fun resetAllLyrics(): Unit = withContext(Dispatchers.IO) {
        LogUtils.d(this, "Resetting all lyrics")
        lyricsCache.evictAll()
        lyricsDao.deleteAll()
        
        // Also clear JSON cache directory
        try {
            val lyricsDir = File(context.filesDir, "lyrics")
            if (lyricsDir.exists()) {
                lyricsDir.listFiles()?.forEach { it.delete() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error clearing JSON cache: ${e.message}")
        }
    }

    override suspend fun scanAndAssignLocalLrcFiles(
        songs: List<Song>,
        onProgress: suspend (current: Int, total: Int) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        LogUtils.d(this@LyricsRepositoryImpl, "Starting bulk scan for .lrc files for ${songs.size} songs")
        val updatedCount = AtomicInteger(0)
        val processedCount = AtomicInteger(0)
        val total = songs.size

        val idsWithPersistedLyrics = songs
            .mapNotNull { it.id.toLongOrNull() }
            .chunked(900)
            .flatMap { chunk -> lyricsDao.getSongIdsWithLyrics(chunk) }
            .toHashSet()

        // Only scan songs that don't already have persisted lyrics.
        val songsToScan = songs.filter { song ->
            val songId = song.id.toLongOrNull()
            song.lyrics.isNullOrBlank() && (songId == null || songId !in idsWithPersistedLyrics)
        }
        val skippedCount = total - songsToScan.size
        processedCount.addAndGet(skippedCount)
        
        LogUtils.d(this@LyricsRepositoryImpl, "Skipping $skippedCount songs that already have lyrics. Scanning ${songsToScan.size} songs.")
        
        onProgress(processedCount.get(), total)
        
        if (songsToScan.isEmpty()) {
            return@withContext 0
        }

        val semaphore = Semaphore(8) // Limit concurrency

        coroutineScope {
            songsToScan.map { song ->
                async {
                    semaphore.withPermit {
                        try {
                            // Find lyrics file
                            val songFile = File(song.path)
                            val directory = songFile.parentFile
                            
                            if (directory != null && directory.exists()) {
                                var foundFile: File? = null
                                
                                // Strategy 1: Exact match name
                                for (extension in LyricsImportSecurity.supportedFileExtensions()) {
                                    val exactMatch = File(directory, "${songFile.nameWithoutExtension}.$extension")
                                    if (exactMatch.exists() && exactMatch.canRead()) {
                                        foundFile = exactMatch
                                        break
                                    }
                                }
                                
                                // Strategy 2: Artist - Title
                                if (foundFile == null) {
                                    val cleanArtist = song.displayArtist.replace(Regex("[^a-zA-Z0-9]"), "_")
                                    val cleanTitle = song.title.replace(Regex("[^a-zA-Z0-9]"), "_")
                                    for (extension in LyricsImportSecurity.supportedFileExtensions()) {
                                        val altMatch = File(directory, "${cleanArtist}_${cleanTitle}.$extension")
                                        if (altMatch.exists() && altMatch.canRead()) {
                                            foundFile = altMatch
                                            break
                                        }
                                    }
                                }
                                
                                if (foundFile != null) {
                                    val validated = readValidatedLocalLyrics(foundFile)
                                    if (validated != null) {
                                        try {
                                            lyricsDao.insert(
                                                 com.theveloper.pixelplay.data.database.LyricsEntity(
                                                     songId = song.id.toLong(),
                                                     content = validated.sanitizedContent,
                                                     isSynced = validated.parsedLyrics.synced?.isNotEmpty() == true,
                                                     source = "local_file"
                                                 )
                                            )
                                            updatedCount.incrementAndGet()
                                            LogUtils.d(this@LyricsRepositoryImpl, "Auto-assigned lyrics from ${foundFile.name}")
                                        } catch (e: Exception) {
                                            Log.w(TAG, "Skipping DB update for ID in scanner: ${song.id}", e)
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Error scanning lyrics for ${song.title}: ${e.message}")
                        }
                        
                        val current = processedCount.incrementAndGet()
                        if (current % 20 == 0 || current == total) {
                            onProgress(current, total)
                        }
                    }
                }
            }.awaitAll()
        }
        
        LogUtils.d(this@LyricsRepositoryImpl, "Bulk scan complete. Updated ${updatedCount.get()} songs.")
        return@withContext updatedCount.get()
    }

    override fun clearCache() {
        LogUtils.d(this, "Clearing lyrics in-memory cache")
        lyricsCache.evictAll()
    }

    private fun generateCacheKey(songId: String): String = songId

    private fun createTempFileFromUri(uri: Uri): File? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val fileName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex >= 0) cursor.getString(nameIndex) else "temp_audio"
                    } else "temp_audio"
                } ?: "temp_audio"

                val tempFile = File.createTempFile("lyrics_", "_$fileName", context.cacheDir)
                FileOutputStream(tempFile).use { output ->
                    inputStream.copyTo(output)
                }
                tempFile
            }
        } catch (e: Exception) {
            LogUtils.e(this, e, "Error creating temp file from URI")
            null
        }
    }

}

data class NoLyricsFoundException(val query: String? = null) : Exception()

class LyricsException(message: String, cause: Throwable? = null) : Exception(message, cause)
