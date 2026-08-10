package com.theveloper.pixelplay.data.repository

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.net.toUri
import com.google.gson.JsonParser
import com.google.gson.Gson
import com.kyant.taglib.TagLib
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.model.Lyrics
import com.theveloper.pixelplay.data.model.LyricsMediaKey
import com.theveloper.pixelplay.data.model.LyricsDocument
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.model.SyncedLine
import com.theveloper.pixelplay.data.netease.NeteaseRepository
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.data.qqmusic.QqLyricsFetcher
import com.theveloper.pixelplay.utils.LyricsImportSecurity
import com.theveloper.pixelplay.utils.LyricsImportValidationResult
import com.theveloper.pixelplay.utils.LogUtils
import com.theveloper.pixelplay.utils.LyricsUtils
import com.theveloper.pixelplay.utils.MediaSourceClassifier
import com.theveloper.pixelplay.utils.MediaSourceKind
import com.theveloper.pixelplay.utils.NetworkRetryUtils
import com.theveloper.pixelplay.utils.PlaybackTrace
import com.theveloper.pixelplay.utils.PlaybackTraceContext
import com.theveloper.pixelplay.utils.PlaybackTraceOrigin
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
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
    private val musicDao: com.theveloper.pixelplay.data.database.MusicDao,
    private val okHttpClient: OkHttpClient,
    private val lyricsCoordinator: LyricsCoordinator
) : LyricsRepository {


    companion object {
        private const val TAG = "LyricsRepository"
        
        private const val AMLLDB_BASE_URL = "https://amlldb.bikonoo.com"
        private const val AMLLDB_SEARCH_URL = "$AMLLDB_BASE_URL/api/search-lyrics"
        private const val NETWORK_RETRY_ATTEMPTS = 3
        private const val NETWORK_RETRY_INITIAL_DELAY_MS = 500L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val NETEASE_YRC_LINE_REGEX = Regex("""^\[(\d+),(\d+)](.*)$""")
        private val NETEASE_YRC_WORD_REGEX = Regex("""(.*?)\((\d+),(\d+)\)""")
    }

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
        forceRefresh: Boolean,
        traceContext: PlaybackTraceContext?
    ): Lyrics? = withContext(Dispatchers.IO) {
        val cacheKey = generateCacheKey(song)
        Log.d(
            TAG,
            "===== FETCH LYRICS START: ${song.displayArtist} - ${song.title} " +
                "(forceRefresh=$forceRefresh) ====="
        )

        val sourceFetchers: List<LyricsLoadAttempt> = when {
            isLikelyLocalSong(song) -> listOf(
                LyricsLoadAttempt("Embedded") { loadEmbeddedLyricsFromMetadata(song) },
                LyricsLoadAttempt("Local") { findLocalLyricsFile(song) },
                LyricsLoadAttempt("Remote") { fetchLyricsFromRemoteSource(song, traceContext)?.lyrics }
            )

            else -> listOf(
                LyricsLoadAttempt("Remote") { fetchLyricsFromRemoteSource(song, traceContext)?.lyrics }
            )
        }

        lyricsCoordinator.resolve(
            cacheKey = cacheKey,
            forceRefresh = forceRefresh,
            loadStored = {
                loadStoredLyrics(song, cacheKey, includeMemoryCache = false)?.first
            },
            attempts = sourceFetchers,
            onSourceHit = { sourceName ->
                Log.d(TAG, "Found lyrics from $sourceName for: ${song.displayArtist} - ${song.title}")
            },
            onSourceFailure = { sourceName, error ->
                Log.w(TAG, "Error fetching lyrics from $sourceName: ${error.message}")
            }
        ).also { lyrics ->
            if (lyrics == null) {
                Log.d(TAG, "No lyrics found from any source for: ${song.displayArtist} - ${song.title}")
            }
        }
    }

    override suspend fun getStoredLyrics(song: Song): Pair<Lyrics, String>? = withContext(Dispatchers.IO) {
        val cacheKey = generateCacheKey(song)
        loadStoredLyrics(song, cacheKey, includeMemoryCache = true)?.also { stored ->
            lyricsCoordinator.putMemory(cacheKey, stored.first)
        }
    }

    private data class RemoteLyricsOptions(
        val enableOnlineTtmlLyrics: Boolean,
        val enableOnlineLyrics: Boolean
    )

    private data class RemoteLyricsPayload(
        val lyrics: Lyrics,
        val rawLyrics: String,
        val sourceTag: String
    )

    private data class RemoteLyricsCandidate(
        val descriptor: LyricsSourceDescriptor,
        val lyrics: Lyrics,
        val rawLyrics: String,
        val priority: Int
    )

    private data class RemoteLyricsProviderPlan(
        val sourceTag: String,
        val priority: Int,
        val fetch: suspend () -> RemoteLyricsCandidate?
    )

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

    private suspend fun loadRemoteLyricsOptions(): RemoteLyricsOptions {
        val enableOnlineLyrics = userPreferencesRepository.enableOnlineLyricsFlow.first()
        return RemoteLyricsOptions(
            enableOnlineTtmlLyrics = enableOnlineLyrics && userPreferencesRepository.enableOnlineTtmlLyricsFlow.first(),
            enableOnlineLyrics = enableOnlineLyrics
        )
    }

    private suspend fun fetchLyricsFromRemoteSource(
        song: Song,
        traceContext: PlaybackTraceContext? = null
    ): RemoteLyricsPayload? = withContext(Dispatchers.IO) {
        val trace = traceContext?.copy(origin = PlaybackTraceOrigin.LYRICS) ?: PlaybackTrace.newContext(
            mediaId = song.id,
            sourceUri = song.contentUriString,
            origin = PlaybackTraceOrigin.LYRICS
        )
        val options = loadRemoteLyricsOptions()
        logLyricsRequest(song, options, trace.requestId)
        if (!options.enableOnlineLyrics) return@withContext null

        val candidate = selectRemoteLyricsCandidate(song, options, trace.requestId) ?: return@withContext null
        val payload = buildRemoteLyricsPayload(candidate)

        persistRemoteLyrics(song, payload)
        payload
    }

    private suspend fun selectRemoteLyricsCandidate(
        song: Song,
        options: RemoteLyricsOptions,
        requestId: String
    ): RemoteLyricsCandidate? = coroutineScope {
        val plan = buildRemoteLyricsProviderPlan(song, options)
        if (plan.isEmpty()) return@coroutineScope null
        Timber.d(
            "Lyrics[Plan] requestId=%s providers=%s song=%s - %s",
            requestId,
            plan.sortedBy { it.priority }.joinToString(",") { it.sourceTag },
            song.displayArtist,
            song.title
        )

        val selected = lyricsCoordinator.selectFirstByPriority(
            attempts = plan.map { provider ->
                PrioritizedLyricsAttempt(provider.sourceTag, provider.priority) {
                    provider.fetch()?.copy(priority = provider.priority)
                }
            },
            isUsable = ::isUsableRemoteCandidate,
            onTry = { provider ->
                Timber.d("Lyrics[Try] requestId=%s source=%s song=%s - %s", requestId, provider.sourceName, song.displayArtist, song.title)
            },
            onHit = { _, candidate ->
                logLyricsSourceHit(song, candidate.descriptor, requestId)
            },
            onMiss = { provider, error ->
                if (error == null) {
                    Timber.d("Lyrics[Reject] requestId=%s source=%s reason=miss song=%s - %s", requestId, provider.sourceName, song.displayArtist, song.title)
                } else {
                    Timber.d(
                        error,
                        "Lyrics[Reject] requestId=%s source=%s reason=exception song=%s - %s",
                        requestId,
                        provider.sourceName,
                        song.displayArtist,
                        song.title
                    )
                }
            }
        )

        if (selected != null) {
            logLyricsFinal(song, selected, requestId)
        }
        selected
    }

    private fun buildRemoteLyricsProviderPlan(
        song: Song,
        options: RemoteLyricsOptions
    ): List<RemoteLyricsProviderPlan> = buildList {
        if (options.enableOnlineTtmlLyrics) {
            add(RemoteLyricsProviderPlan("amll_ttml", 10) { fetchAmlldbRemoteCandidate(song, priority = 10) })
        }

        when {
            isNeteaseSong(song) -> {
                add(RemoteLyricsProviderPlan("netease_official", 20) { fetchNeteaseOfficialCandidate(song, priority = 20) })
                add(RemoteLyricsProviderPlan("qq", 30) { fetchQqRemoteCandidate(song, priority = 30) })
            }

            isQqMusicSong(song) -> {
                add(RemoteLyricsProviderPlan("qq", 20) { fetchQqRemoteCandidate(song, priority = 20) })
                add(RemoteLyricsProviderPlan("netease_strict", 30) { fetchNeteaseStrictMatchedCandidate(song, priority = 30) })
            }

            else -> {
                add(RemoteLyricsProviderPlan("qq", 20) { fetchQqRemoteCandidate(song, priority = 20) })
                add(RemoteLyricsProviderPlan("netease_strict", 30) { fetchNeteaseStrictMatchedCandidate(song, priority = 30) })
            }
        }
    }

    private suspend fun fetchAmlldbRemoteCandidate(song: Song, priority: Int): RemoteLyricsCandidate? {
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
                exactPlatformMatch = isNeteaseSong(song)
            ),
            lyrics = lyrics,
            rawLyrics = rawLyrics,
            priority = priority
        )
    }

    private suspend fun fetchNeteaseOfficialCandidate(song: Song, priority: Int): RemoteLyricsCandidate? {
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
            rawLyrics = officialRawLyrics,
            priority = priority
        )
    }

    private suspend fun fetchNeteaseStrictMatchedCandidate(song: Song, priority: Int): RemoteLyricsCandidate? {
        val candidate = searchNeteaseStrictCandidate(song) ?: return null
        val songId = resolveNeteaseSongId(candidate) ?: return null
        val rawLyrics = withNetworkRetry(operationName = "netease_strict_lyrics:$songId") {
            neteaseRepository.getLyricsPayload(songId).getOrNull()
        }?.takeIf { it.isNotBlank() } ?: return null

        val lyrics = rawLyrics.let(::parseNeteaseLyricsPayload)
            ?.takeIf { it.isValid() }
            ?: return null
        val durationDiffMs = if (song.duration > 0L && candidate.duration > 0L) {
            kotlin.math.abs(song.duration - candidate.duration)
        } else {
            null
        }

        return RemoteLyricsCandidate(
            descriptor = lyricsDescriptor(
                sourceTag = "netease_official",
                lyrics = lyrics,
                matchType = "fuzzy",
                durationDiffMs = durationDiffMs,
                exactPlatformMatch = false
            ),
            lyrics = lyrics,
            rawLyrics = rawLyrics,
            priority = priority
        )
    }

    private suspend fun fetchQqRemoteCandidate(song: Song, priority: Int): RemoteLyricsCandidate? {
        val result = qqLyricsFetcher.fetch(song) ?: run {
            Timber.d("Lyrics[QQ] miss for %s - %s", song.displayArtist, song.title)
            return null
        }
        return RemoteLyricsCandidate(
            descriptor = lyricsDescriptor(
                sourceTag = result.metadata.sourceTag,
                lyrics = result.lyrics,
                matchType = result.metadata.matchType,
                durationDiffMs = result.metadata.durationDiffMs,
                exactPlatformMatch = result.metadata.exactPlatformMatch
            ),
            lyrics = result.lyrics.copy(areFromRemote = true),
            rawLyrics = result.rawLyrics,
            priority = priority
        )
    }

    private fun buildRemoteLyricsPayload(candidate: RemoteLyricsCandidate): RemoteLyricsPayload =
        RemoteLyricsPayload(
            lyrics = candidate.lyrics.copy(areFromRemote = true),
            rawLyrics = candidate.rawLyrics,
            sourceTag = candidate.descriptor.sourceTag
        )

    private fun isUsableRemoteCandidate(candidate: RemoteLyricsCandidate): Boolean =
        candidate.lyrics.isValid() && !LyricsUtils.hasNeteaseJsonLyricGarbage(candidate.lyrics)

    private suspend fun persistRemoteLyrics(song: Song, payload: RemoteLyricsPayload) {
        Timber.d("Lyrics[Persist] source=%s for %s - %s", payload.sourceTag, song.displayArtist, song.title)
        runCatching {
            val persistedContent = lyricsToPersistedContent(payload)
            lyricsDao.insert(
                structuredLyricsEntity(
                    mediaKey = LyricsMediaKey.fromSong(song),
                    songId = song.libraryId,
                    lyrics = payload.lyrics,
                    rawContent = persistedContent,
                    sourceTag = persistedSourceTag(payload.sourceTag)
                )
            )
        }.onFailure { error ->
            Log.w(TAG, "Failed to persist remote lyrics for ${song.id}: ${error.message}")
        }
    }

    private fun persistedSourceTag(sourceTag: String): String =
        if (sourceTag == "netease_official") "netease_official_v2" else sourceTag

    private fun structuredLyricsEntity(
        mediaKey: LyricsMediaKey,
        songId: Long?,
        lyrics: Lyrics,
        rawContent: String,
        sourceTag: String
    ): com.theveloper.pixelplay.data.database.LyricsEntity {
        val document = LyricsDocument(
            lyrics = lyrics,
            rawContent = rawContent,
            sourceTag = sourceTag
        )
        return com.theveloper.pixelplay.data.database.LyricsEntity(
            mediaKey = mediaKey.value,
            songId = songId,
            content = rawContent,
            isSynced = !lyrics.synced.isNullOrEmpty(),
            source = sourceTag,
            schemaVersion = LyricsDocument.CURRENT_SCHEMA_VERSION,
            payloadJson = gson.toJson(document),
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun lyricsToPersistedContent(payload: RemoteLyricsPayload): String {
        if (payload.rawLyrics.startsWith("qqlyrics://")) return payload.rawLyrics
        return lyricsToRawContent(payload.lyrics) ?: payload.rawLyrics
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
        return mergeSameProviderLyricsPayloads(primaryLyrics, wordLevelLyrics)?.copy(areFromRemote = true)
            ?: if (lrc.isNotBlank()) LyricsUtils.parseLyrics(lrc)
                .takeIf { it.isValid() }
                ?.takeUnless(LyricsUtils::hasNeteaseJsonLyricGarbage)
                ?.copy(areFromRemote = true) else null
    }

    private fun mergeSameProviderLyricsPayloads(
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
            else -> mergeSameProviderLineAndWordLevel(primarySynced, wordSynced)
        }

        return base.copy(
            synced = mergedSynced.ifEmpty { null },
            plain = mergedSynced.takeIf { it.isNotEmpty() }?.let(LyricsUtils::rebuildPlainFromSynced) ?: base.plain,
            areFromRemote = true
        )
    }

    private fun mergeSameProviderLineAndWordLevel(
        primaryLines: List<SyncedLine>,
        wordLines: List<SyncedLine>
    ): List<SyncedLine> {
        val merged = mutableListOf<SyncedLine>()
        var wordIndex = 0

        for (primary in primaryLines) {
            while (wordIndex < wordLines.size && wordLines[wordIndex].time < primary.time - 300) {
                wordIndex++
            }

            val matched = wordLines.getOrNull(wordIndex)
                ?.takeIf { kotlin.math.abs(it.time - primary.time) <= 300 }

            merged += if (matched != null) {
                primary.copy(
                    words = matched.words ?: primary.words,
                    translation = primary.translation ?: matched.translation,
                    romanization = primary.romanization ?: matched.romanization
                )
            } else {
                primary
            }
        }

        return merged
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
        song.neteaseId != null || MediaSourceClassifier.classify(song.contentUriString) == MediaSourceKind.NETEASE

    private fun isQqMusicSong(song: Song): Boolean =
        song.qqMusicMid != null || MediaSourceClassifier.classify(song.contentUriString) == MediaSourceKind.QQ_MUSIC

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

    private fun extractDurationDiffMs(rawLyrics: String): Long? = runCatching {
        if (!rawLyrics.startsWith("qqlyrics://")) return@runCatching null
        null
    }.getOrNull()

    private fun logLyricsRequest(song: Song, options: RemoteLyricsOptions, requestId: String) {
        Timber.d(
            "Lyrics[Request] requestId=%s platform=%s title=%s artist=%s durationMs=%s online=%s ttml=%s",
            requestId,
            when {
                isNeteaseSong(song) -> "netease"
                isQqMusicSong(song) -> "qq"
                isLikelyLocalSong(song) -> "local"
                else -> "other"
            },
            song.title,
            song.displayArtist,
            song.duration,
            options.enableOnlineLyrics,
            options.enableOnlineTtmlLyrics
        )
    }

    private fun logLyricsSourceHit(
        song: Song,
        descriptor: LyricsSourceDescriptor,
        requestId: String
    ) {
        Timber.d(
            "Lyrics[Hit] requestId=%s source=%s match=%s durationDiffMs=%s wordLevel=%s translation=%s roman=%s exact=%s song=%s - %s",
            requestId,
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

    private fun logLyricsFinal(song: Song, candidate: RemoteLyricsCandidate, requestId: String) {
        val capabilities = candidate.descriptor.capabilities
        Timber.d(
            "Lyrics[Final] requestId=%s source=%s priority=%d translation=%s roman=%s hasWordLevel=%s song=%s - %s",
            requestId,
            candidate.descriptor.sourceTag,
            candidate.priority,
            capabilities.hasTranslation,
            capabilities.hasRoman,
            capabilities.hasWordLevel,
            song.displayArtist,
            song.title
        )
    }

    private fun resolveNeteaseSongId(song: Song): Long? {
        song.neteaseId?.let { return it }
        return MediaSourceClassifier.idPart(song.contentUriString)
            ?.takeIf { MediaSourceClassifier.classify(song.contentUriString) == MediaSourceKind.NETEASE }
            ?.toLongOrNull()
    }

    private suspend fun searchNeteaseStrictCandidate(song: Song): Song? {
        val query = listOf(song.title, song.displayArtist)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()
            .ifBlank { return null }

        return neteaseRepository.searchOnline(query, limit = 10)
            .getOrNull()
            .orEmpty()
            .mapNotNull { candidate ->
                val score = LyricsMetadataMatcher.strictScore(
                    originalTitle = song.title,
                    originalArtist = song.displayArtist,
                    originalDurationMs = song.duration,
                    candidateTitle = candidate.title,
                    candidateArtist = candidate.displayArtist,
                    candidateDurationMs = candidate.duration
                ) ?: return@mapNotNull null
                candidate to score
            }
            .maxByOrNull { it.second }
            ?.first
    }


    private data class AmlldbSearchResult(
        val title: String,
        val artist: String,
        val file: String
    )

    private suspend fun fetchFromAmlldb(song: Song): Lyrics? = withContext(Dispatchers.IO) {
        val neteaseSongId = resolveNeteaseSongId(song)

        try {
            if (neteaseSongId != null) {
                fetchAmlldbNcmTtml(neteaseSongId)
                    ?.let { ttml ->
                        parseAmlldbTtml(ttml)?.also {
                            Log.d(TAG, "AMLLDB direct NCM lyrics hit for songId=$neteaseSongId")
                        }
                    }
                    ?.let { return@withContext it }
            }

            val searchHit = searchAmlldbByMetadata(song).firstOrNull()
                ?: return@withContext null
            val rawTtml = fetchAmlldbRawLyrics(searchHit.file)
                ?: return@withContext null

            parseAmlldbTtml(rawTtml)?.also {
                Log.d(
                    TAG,
                    "AMLLDB search lyrics hit " +
                        "title=${searchHit.title} artist=${searchHit.artist} file=${searchHit.file}"
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "AMLLDB fetch failed for ${song.displayArtist} - ${song.title}: ${e.message}")
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
        return contentUriString.isNotBlank() && MediaSourceClassifier.isLocal(contentUriString)
    }

    private suspend fun loadStoredLyrics(
        song: Song,
        cacheKey: String,
        includeMemoryCache: Boolean
    ): Pair<Lyrics, String>? = withContext(Dispatchers.IO) {
        fun acceptedStoredLyrics(
            parsed: Lyrics,
            rawLyrics: String
        ): Pair<Lyrics, String>? = parsed.takeIf { it.isValid() }?.let { it to rawLyrics }

        fun acceptedRawLyrics(rawLyrics: String): Pair<Lyrics, String>? {
            val parsed = parseStoredLyrics(rawLyrics) ?: return null
            return acceptedStoredLyrics(parsed, rawLyrics)
        }

        if (includeMemoryCache) {
            lyricsCoordinator.getMemory(cacheKey)?.let { cached ->
                lyricsToRawContent(cached)?.let { rawLyrics ->
                    acceptedStoredLyrics(cached, rawLyrics)?.let { return@withContext it }
                }
            }
        }

        val mediaKey = LyricsMediaKey.fromSong(song)
        val songId = song.libraryId
        lyricsDao.getLyrics(mediaKey.value)?.let { entity ->
            val sourceTag = entity.source?.takeIf { it.isNotBlank() } ?: "lyrics_db"
            val document = entity.payloadJson
                ?.takeIf { entity.schemaVersion == LyricsDocument.CURRENT_SCHEMA_VERSION }
                ?.let { payload ->
                    runCatching { gson.fromJson(payload, LyricsDocument::class.java) }.getOrNull()
                }
            if (document != null && document.lyrics.isValid()) {
                acceptedStoredLyrics(
                    parsed = document.lyrics,
                    rawLyrics = document.rawContent
                )?.let { return@withContext it }
            }

            val rawLyrics = entity.content.trim().takeIf { it.isNotBlank() } ?: return@let
            acceptedRawLyrics(rawLyrics)?.let { accepted ->
                lyricsDao.insert(
                    structuredLyricsEntity(mediaKey, songId, accepted.first, accepted.second, sourceTag)
                )
                return@withContext accepted
            }
        }

        val legacySongLyrics = songId
            ?.let { musicDao.getLegacySongLyrics(it) }
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        legacySongLyrics?.trim()?.takeIf { it.isNotBlank() }?.let { rawLyrics ->
            acceptedRawLyrics(rawLyrics)?.let { accepted ->
                lyricsDao.insert(
                    structuredLyricsEntity(mediaKey, songId, accepted.first, accepted.second, "song_field")
                )
                musicDao.clearLegacySongLyrics(songId)
                return@withContext accepted
            }
        }

        readLyricsJsonCache(song)
            ?.takeIf { it.hasLyrics() }
            ?.let { data ->
                val rawLyrics = data.structuredLyrics ?: data.wordByWordLyrics ?: data.syncedLyrics ?: data.plainLyrics
                if (!rawLyrics.isNullOrBlank()) {
                    acceptedRawLyrics(rawLyrics)?.let { accepted ->
                        lyricsDao.insert(
                            structuredLyricsEntity(mediaKey, songId, accepted.first, accepted.second, "json_cache")
                        )
                        deleteLyricsJsonCache(song)
                        return@withContext accepted
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
        val file = legacyLyricsJsonFile(song) ?: return null
        if (!file.exists()) return null

        val json = file.readText()
        return gson.fromJson(json, LyricsData::class.java)
    }

    private fun deleteLyricsJsonCache(song: Song) {
        val file = legacyLyricsJsonFile(song) ?: return
        runCatching { file.delete() }
            .onFailure { Log.w(TAG, "Failed to delete migrated lyrics JSON for ${song.id}", it) }
    }

    private fun legacyLyricsJsonFile(song: Song): File? = song.libraryId
        ?.let { libraryId -> File(context.filesDir, "lyrics/$libraryId.json") }

    override suspend fun fetchFromRemote(song: Song): Result<Pair<Lyrics, String>> = withContext(Dispatchers.IO) {
        try {
            LogUtils.d(this@LyricsRepositoryImpl, "Fetching lyrics from remote for: ${song.title}")

            val cacheKey = generateCacheKey(song)
            val payload = fetchLyricsFromRemoteSource(song)
                ?: return@withContext Result.failure(NoLyricsFoundException())

            lyricsCoordinator.putMemory(cacheKey, payload.lyrics)
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
            if (!options.enableOnlineLyrics) {
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

            if (!options.enableOnlineLyrics) {
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

    override suspend fun updateLyrics(song: Song, lyricsContent: String): Unit = withContext(Dispatchers.IO) {
        val mediaKey = LyricsMediaKey.fromSong(song)
        val songId = song.libraryId
        LogUtils.d(this@LyricsRepositoryImpl, "Updating lyrics for mediaKey: ${mediaKey.value}")

        val parsedLyrics = LyricsUtils.parseLyrics(lyricsContent)
        if (!parsedLyrics.isValid()) {
            LogUtils.w(this@LyricsRepositoryImpl, "Attempted to save empty lyrics for mediaKey: ${mediaKey.value}")
            return@withContext
        }

        lyricsDao.insert(
            structuredLyricsEntity(
                mediaKey = mediaKey,
                songId = songId,
                lyrics = parsedLyrics,
                rawContent = lyricsContent,
                sourceTag = "manual"
            )
        )

        val cacheKey = mediaKey.value
        lyricsCoordinator.putMemory(cacheKey, parsedLyrics)
        LogUtils.d(this@LyricsRepositoryImpl, "Updated and cached lyrics for mediaKey: ${mediaKey.value}")
    }

    override suspend fun resetLyrics(song: Song): Unit = withContext(Dispatchers.IO) {
        val mediaKey = LyricsMediaKey.fromSong(song)
        LogUtils.d(this, "Resetting lyrics for mediaKey: ${mediaKey.value}")
        val cacheKey = mediaKey.value
        lyricsCoordinator.removeMemory(cacheKey)
        try {
            lyricsDao.deleteLyrics(mediaKey.value)
            song.libraryId?.let { musicDao.clearLegacySongLyrics(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Error removing lyrics from DB for key: ${mediaKey.value}", e)
        }
        
        // Also remove JSON cache
        try {
            val file = legacyLyricsJsonFile(song)
            if (file?.exists() == true) file.delete()
        } catch (e: Exception) {
            Log.w(TAG, "Error deleting JSON cache: ${e.message}")
        }
    }

    override suspend fun resetAllLyrics(): Unit = withContext(Dispatchers.IO) {
        LogUtils.d(this, "Resetting all lyrics")
        lyricsCoordinator.clearMemory()
        lyricsDao.deleteAll()
        musicDao.clearAllLegacySongLyrics()
        
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

        val mediaKeysWithPersistedLyrics = songs
            .map { LyricsMediaKey.fromSong(it).value }
            .chunked(900)
            .flatMap { chunk -> lyricsDao.getMediaKeysWithLyrics(chunk) }
            .toHashSet()

        // Only scan songs that don't already have persisted lyrics.
        val songsToScan = songs.filter { song ->
            LyricsMediaKey.fromSong(song).value !in mediaKeysWithPersistedLyrics
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
                                                structuredLyricsEntity(
                                                     mediaKey = LyricsMediaKey.fromSong(song),
                                                     songId = song.libraryId,
                                                     lyrics = validated.parsedLyrics,
                                                     rawContent = validated.sanitizedContent,
                                                     sourceTag = "local_file"
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
        lyricsCoordinator.clearMemory()
    }

    private fun generateCacheKey(song: Song): String = LyricsMediaKey.fromSong(song).value

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
