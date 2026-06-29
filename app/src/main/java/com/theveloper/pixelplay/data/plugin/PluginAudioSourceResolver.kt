package com.theveloper.pixelplay.data.plugin

import com.theveloper.pixelplay.BuildConfig
import com.theveloper.pixelplay.data.database.MusicDao
import com.theveloper.pixelplay.data.database.toSong
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.data.stream.ResolvedStream
import com.theveloper.pixelplay.di.AppScope
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

@Singleton
class PluginAudioSourceResolver @Inject constructor(
    private val pluginRepository: PluginRepository,
    private val musicDao: MusicDao,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val candidateResolver: PluginPlatformCandidateResolver,
    @param:AppScope private val appScope: CoroutineScope
) {
    private val preferredQuality = MutableStateFlow(PluginQuality.HIGH)
    private val recentFailures = ConcurrentHashMap<String, CachedFailure>()

    init {
        appScope.launch {
            userPreferencesRepository.audioSourcePluginPreferredQualityFlow.collect { quality ->
                preferredQuality.value = quality
            }
        }
    }

    suspend fun resolve(
        source: PluginSource,
        platformSongId: String,
        requestedQuality: PluginQuality? = null
    ): ResolvedStream {
        val plugin = pluginRepository.ensureEnabledPluginLoaded()
        val state = pluginRepository.runtimeState.value
        if (!state.initialized) throw PluginResolveException.InitFailed(state.errorMessage)

        val preferredQuality = requestedQuality ?: preferredQuality.value
        val sources = sourceAttemptOrder(source)
        val failureKey = buildFailureKey(plugin.id, source, platformSongId, preferredQuality, sources)
        recentFailures[failureKey]?.let { cached ->
            val ageMs = System.currentTimeMillis() - cached.timestampMs
            if (ageMs < FAILURE_CACHE_MS) {
                throw cached.failure
            } else {
                recentFailures.remove(failureKey, cached)
            }
        }

        val originalSong = songFor(source, platformSongId)
        var lastFailure: PluginResolveException? = null
        var bestLowerQualityStream: ResolvedStream? = null

        for (quality in chooseQualityCandidates(preferredQuality)) {
            for (targetSource in sources) {
                if (!state.capabilities.supports(targetSource)) {
                    logDebug(
                        "Plugin source skipped unsupported source=${targetSource.lxSource} " +
                            "origin=${source.lxSource} id=$platformSongId"
                    )
                    continue
                }
                if (quality !in state.capabilities.qualitiesFor(targetSource)) {
                    logDebug(
                        "Plugin source skipped unsupported quality source=${targetSource.lxSource} " +
                            "quality=${quality.lxQuality} id=$platformSongId"
                    )
                    continue
                }

                val candidates = candidatesForTarget(
                    originalSong = originalSong,
                    originalSource = source,
                    targetSource = targetSource,
                    platformSongId = platformSongId
                )
                if (candidates.isEmpty()) {
                    logDebug(
                        "Plugin source no candidate origin=${source.lxSource} target=${targetSource.lxSource} " +
                            "quality=${quality.lxQuality} id=$platformSongId"
                    )
                    continue
                }

                for (candidate in candidates) {
                    val info = buildMusicInfo(
                        song = originalSong,
                        originalSource = source,
                        targetSource = targetSource,
                        platformSongId = platformSongId,
                        candidate = candidate
                    )
                    try {
                        val stream = requestCandidateStream(
                            pluginId = plugin.id,
                            originalSource = source,
                            originalPlatformSongId = platformSongId,
                            targetSource = targetSource,
                            quality = quality,
                            preferredQuality = preferredQuality,
                            info = info,
                            candidate = candidate
                        )
                        val effectiveQuality = PluginQuality.fromAlias(stream.quality) ?: quality
                        if (effectiveQuality.rank >= quality.rank) {
                            recentFailures.remove(failureKey)
                            return stream
                        }
                        bestLowerQualityStream = pickBetterStream(bestLowerQualityStream, stream)
                        logDebug(
                            "Plugin returned lower quality origin=${source.lxSource} target=${targetSource.lxSource} " +
                                "id=${candidate.id} request=${quality.lxQuality} returned=${stream.quality.orEmpty()}"
                        )
                    } catch (e: PluginResolveException) {
                        lastFailure = e
                        logDebug(
                            "Plugin musicUrl failed origin=${source.lxSource} target=${targetSource.lxSource} " +
                                "id=${candidate.id} quality=${quality.lxQuality} score=${candidate.matchScore} error=${e.message}"
                        )
                    }
                }
            }
        }

        bestLowerQualityStream?.let {
            recentFailures.remove(failureKey)
            return it
        }

        val failure = lastFailure ?: PluginResolveException.UnsupportedQuality()
        recentFailures[failureKey] = CachedFailure(failure, System.currentTimeMillis())
        throw failure
    }

    fun cancelPendingRequests() {
        pluginRepository.cancelPendingRequests()
    }

    fun cacheKey(
        source: PluginSource,
        platformSongId: String,
        requestedQuality: PluginQuality? = null
    ): String {
        val pluginId = pluginRepository.runtimeState.value.pluginId ?: "none"
        val quality = requestedQuality ?: preferredQuality.value
        return "$pluginId:${source.lxSource}:$platformSongId:${quality.lxQuality}"
    }

    private suspend fun requestCandidateStream(
        pluginId: String,
        originalSource: PluginSource,
        originalPlatformSongId: String,
        targetSource: PluginSource,
        quality: PluginQuality,
        preferredQuality: PluginQuality,
        info: PluginMusicInfo,
        candidate: PluginPlatformCandidate
    ): ResolvedStream {
        val result = pluginRepository.requestMusicUrl(
            requestKey = UUID.randomUUID().toString(),
            source = targetSource,
            quality = quality.lxQuality,
            info = info
        )
        val validResult = PluginAudioSourceValidator.validate(result)
        val returnedQuality = PluginQuality.fromAlias(validResult.quality)
        val displayQuality = returnedQuality?.lxQuality
            ?: validResult.quality?.takeIf { it.isNotBlank() }
            ?: quality.lxQuality
        val returnedSource = PluginSource.fromAlias(validResult.source) ?: targetSource

        if (validResult.isTrial) {
            Timber.w(
                "Plugin returned trial stream origin=${originalSource.lxSource} target=${targetSource.lxSource} " +
                    "id=${candidate.id} requestQuality=${quality.lxQuality} returnQuality=${validResult.quality.orEmpty()}"
            )
        }
        if (quality != preferredQuality) {
            Timber.w(
                "Plugin resolved with lower requested quality origin=${originalSource.lxSource} target=${targetSource.lxSource} " +
                    "id=${candidate.id} preferred=${preferredQuality.lxQuality} actualRequest=${quality.lxQuality}"
            )
        }
        logDebug(
            "Plugin resolved origin=${originalSource.lxSource} target=${targetSource.lxSource} " +
                "returnedSource=${returnedSource.lxSource} originalId=$originalPlatformSongId candidate=${candidate.id} " +
                "request=${quality.lxQuality} returned=$displayQuality match=${candidate.matchType} score=${candidate.matchScore}"
        )

        return ResolvedStream(
            url = validResult.url,
            headers = validResult.headers,
            expireAt = validResult.expireAt,
            isTrial = validResult.isTrial,
            quality = displayQuality,
            source = returnedSource.lxSource,
            candidateId = candidate.id,
            candidateMatchScore = candidate.matchScore,
            mimeType = validResult.mimeType,
            cacheKeySuffix = "$pluginId:${originalSource.lxSource}:$originalPlatformSongId:${returnedSource.lxSource}:${candidate.id}:${quality.lxQuality}"
        )
    }

    private suspend fun candidatesForTarget(
        originalSong: Song?,
        originalSource: PluginSource,
        targetSource: PluginSource,
        platformSongId: String
    ): List<PluginPlatformCandidate> {
        if (targetSource == originalSource) {
            return listOf(
                PluginPlatformCandidate(
                    source = targetSource,
                    id = platformSongId,
                    songMid = if (targetSource == PluginSource.QQ_MUSIC) platformSongId else null,
                    hash = if (targetSource == PluginSource.KUGOU) platformSongId else null,
                    title = originalSong?.title.orEmpty(),
                    artist = originalSong?.displayArtist.orEmpty(),
                    album = originalSong?.album.orEmpty(),
                    durationMs = originalSong?.duration ?: 0L,
                    matchScore = 1.0,
                    matchType = "id"
                )
            )
        }
        val song = originalSong ?: return emptyList()
        return candidateResolver.candidatesFor(song, targetSource)
    }

    private fun buildMusicInfo(
        song: Song?,
        originalSource: PluginSource,
        targetSource: PluginSource,
        platformSongId: String,
        candidate: PluginPlatformCandidate
    ): PluginMusicInfo {
        if (song != null) {
            return song.toPluginMusicInfo(targetSource, candidate.id).copy(
                rawContentUri = "${originalSource.uriScheme}://$platformSongId",
                platformSongId = candidate.id,
                sourceSongId = candidate.id,
                sourceSongMid = candidate.songMid,
                sourceSongHash = candidate.hash
            )
        }
        return PluginMusicInfo(
            id = platformSongId,
            source = targetSource,
            title = "",
            artist = "",
            album = "",
            durationMs = 0L,
            albumArtUrl = null,
            rawContentUri = "${originalSource.uriScheme}://$platformSongId",
            platformSongId = candidate.id,
            sourceSongId = candidate.id,
            sourceSongMid = candidate.songMid,
            sourceSongHash = candidate.hash
        )
    }

    private suspend fun songFor(source: PluginSource, platformSongId: String): Song? =
        musicDao.getSongByContentUriOnce("${source.uriScheme}://$platformSongId")?.toSong()

    private fun sourceAttemptOrder(originalSource: PluginSource): List<PluginSource> =
        (listOf(originalSource) + PluginSource.fallbackPriority)
            .filter { it in PluginSource.fallbackPriority }
            .distinct()

    private fun chooseQualityCandidates(requested: PluginQuality): List<PluginQuality> =
        PluginQuality.preferenceOrder.dropWhile { it != requested }.ifEmpty { listOf(requested) }

    private fun pickBetterStream(current: ResolvedStream?, next: ResolvedStream): ResolvedStream =
        when {
            current == null -> next
            qualityRank(next.quality) > qualityRank(current.quality) -> next
            else -> current
        }

    private fun qualityRank(quality: String?): Int =
        PluginQuality.fromAlias(quality)?.rank ?: 0

    private fun buildFailureKey(
        pluginId: String,
        source: PluginSource,
        platformSongId: String,
        requestedQuality: PluginQuality,
        sources: List<PluginSource>
    ): String =
        "$pluginId:${source.lxSource}:$platformSongId:${requestedQuality.lxQuality}:${sources.joinToString(",") { it.lxSource }}"

    private fun logDebug(message: String) {
        if (BuildConfig.DEBUG) Timber.d(message)
    }

    private data class CachedFailure(
        val failure: PluginResolveException,
        val timestampMs: Long
    )

    private companion object {
        const val FAILURE_CACHE_MS = 30_000L
    }
}
