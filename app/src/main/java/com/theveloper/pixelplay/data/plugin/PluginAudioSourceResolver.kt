package com.theveloper.pixelplay.data.plugin

import com.theveloper.pixelplay.data.database.MusicDao
import com.theveloper.pixelplay.data.database.toSong
import com.theveloper.pixelplay.BuildConfig
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
        if (!state.capabilities.supports(source)) throw PluginResolveException.UnsupportedSource()

        val preferredQuality = requestedQuality ?: preferredQuality.value
        val failureKey = "${plugin.id}:${source.lxSource}:$platformSongId:${preferredQuality.lxQuality}"
        recentFailures[failureKey]?.let { cached ->
            val ageMs = System.currentTimeMillis() - cached.timestampMs
            if (ageMs < FAILURE_CACHE_MS) {
                throw cached.failure
            } else {
                recentFailures.remove(failureKey, cached)
            }
        }

        val qualityCandidates = chooseQualityCandidates(
            supportedQualities = state.capabilities.qualitiesFor(source),
            requested = preferredQuality
        )

        val info = buildMusicInfo(source, platformSongId)
        var lastFailure: PluginResolveException? = null
        for (quality in qualityCandidates) {
            try {
                val result = pluginRepository.requestMusicUrl(
                    requestKey = UUID.randomUUID().toString(),
                    source = source,
                    quality = quality.lxQuality,
                    info = info
                )
                val validResult = PluginAudioSourceValidator.validate(result)
                val returnedQuality = PluginQuality.fromAlias(validResult.quality)
                val displayQuality = returnedQuality?.lxQuality
                    ?: validResult.quality?.takeIf { it.isNotBlank() }
                    ?: quality.lxQuality
                if (validResult.isTrial) {
                    Timber.w(
                        "Plugin returned trial stream for ${source.name}/$platformSongId " +
                            "requestQuality=${quality.lxQuality} returnQuality=${validResult.quality.orEmpty()}"
                    )
                }
                if (quality != preferredQuality) {
                    Timber.w(
                        "Plugin resolved with lower quality source=${source.name} id=$platformSongId " +
                            "preferred=${preferredQuality.lxQuality} actual=${quality.lxQuality}"
                    )
                }
                if (BuildConfig.DEBUG && returnedQuality != null && returnedQuality != quality) {
                    Timber.d(
                        "Plugin returned different quality source=${source.name} id=$platformSongId " +
                            "request=${quality.lxQuality} returned=${validResult.quality}"
                    )
                }
                recentFailures.remove(failureKey)
                return ResolvedStream(
                    url = validResult.url,
                    headers = validResult.headers,
                    expireAt = validResult.expireAt,
                    isTrial = validResult.isTrial,
                    quality = displayQuality,
                    mimeType = validResult.mimeType,
                    cacheKeySuffix = "${plugin.id}:${source.lxSource}:$platformSongId:${quality.lxQuality}"
                )
            } catch (e: PluginResolveException) {
                lastFailure = e
                if (BuildConfig.DEBUG) {
                    Timber.d(
                        "Plugin musicUrl failed source=${source.name} id=$platformSongId " +
                            "quality=${quality.lxQuality} error=${e.message}"
                    )
                }
            }
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

    private fun chooseQualityCandidates(
        supportedQualities: Set<PluginQuality>,
        requested: PluginQuality
    ): List<PluginQuality> {
        val orderedFromRequested = PluginQuality.preferenceOrder
            .dropWhile { it != requested }
        val candidates = orderedFromRequested.filter { it in supportedQualities }
        if (candidates.isEmpty()) throw PluginResolveException.UnsupportedQuality()
        return candidates
    }

    private suspend fun buildMusicInfo(source: PluginSource, platformSongId: String): PluginMusicInfo {
        val rawUri = "${source.uriScheme}://$platformSongId"
        val song = musicDao.getSongByContentUriOnce(rawUri)?.toSong()
        return if (song != null) {
            song.toPluginMusicInfo(source, platformSongId)
        } else {
            PluginMusicInfo(
                id = platformSongId,
                source = source,
                title = "",
                artist = "",
                album = "",
                durationMs = 0L,
                albumArtUrl = null,
                rawContentUri = rawUri,
                platformSongId = platformSongId
            )
        }
    }

    private data class CachedFailure(
        val failure: PluginResolveException,
        val timestampMs: Long
    )

    private companion object {
        const val FAILURE_CACHE_MS = 30_000L
    }
}
