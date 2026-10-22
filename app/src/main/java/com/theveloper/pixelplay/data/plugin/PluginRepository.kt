package com.theveloper.pixelplay.data.plugin

import com.theveloper.pixelplay.data.database.PluginDao
import com.theveloper.pixelplay.data.database.PluginEntity
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class PluginRepository @Inject constructor(
    private val pluginDao: PluginDao,
    private val runtime: PluginRuntime,
    private val downloader: PluginScriptDownloader
) {
    private val loadMutex = Mutex()

    val runtimeState: StateFlow<PluginRuntimeState> = runtime.state

    fun observePlugins(): Flow<List<PluginEntity>> = pluginDao.observePlugins()

    suspend fun getPlugins(): List<PluginEntity> = pluginDao.getPlugins()

    suspend fun importScript(script: String, sourceUrl: String? = null): PluginEntity {
        val content = PluginScriptContent.validate(script)
        val now = System.currentTimeMillis()
        val metadata = PluginMetadataParser.parse(content, now)
        val entity = PluginEntity(
            id = UUID.randomUUID().toString(),
            name = metadata.name,
            description = metadata.description,
            author = metadata.author,
            version = metadata.version,
            homepage = metadata.homepage,
            enabled = false,
            script = content,
            createdAt = now,
            updatedAt = now,
            sourceUrl = sourceUrl?.let(PluginScriptContent::normalizeUrl)
        )
        pluginDao.upsert(entity)
        return entity
    }

    suspend fun importFromUrl(url: String): PluginEntity {
        val normalized = PluginScriptContent.normalizeUrl(url)
        return importScript(downloader.download(normalized), normalized)
    }

    suspend fun checkUpdate(id: String, sourceUrl: String? = null): PluginUpdateCandidate? {
        val plugin = pluginDao.getPlugin(id) ?: error("插件已被删除")
        val url = PluginScriptContent.normalizeUrl(sourceUrl ?: plugin.sourceUrl ?: error("请先设置更新链接"))
        val script = downloader.download(url)
        // Remember an explicitly supplied source even when it currently has no update.
        loadMutex.withLock {
            val current = pluginDao.getPlugin(id) ?: error("插件已被删除")
            check(current.script == plugin.script) { "插件已变化，请重新检查更新" }
            pluginDao.upsert(current.copy(sourceUrl = url))
        }
        if (script == PluginScriptContent.validate(plugin.script)) return null
        return PluginUpdateCandidate(id, plugin.script, url, script, PluginMetadataParser.parse(script))
    }

    suspend fun applyUpdate(candidate: PluginUpdateCandidate) = loadMutex.withLock {
        val current = pluginDao.getPlugin(candidate.pluginId) ?: error("插件已被删除")
        check(current.script == candidate.previousScript) { "插件已变化，请重新检查更新" }
        val updated = current.copy(
            name = candidate.metadata.name,
            description = candidate.metadata.description,
            author = candidate.metadata.author,
            version = candidate.metadata.version,
            homepage = candidate.metadata.homepage,
            script = candidate.script,
            sourceUrl = candidate.sourceUrl,
            updatedAt = System.currentTimeMillis()
        )
        try {
            if (current.enabled) runtime.load(updated)
            pluginDao.upsert(updated)
        } catch (error: Exception) {
            if (current.enabled) {
                withContext(NonCancellable) {
                    runCatching { runtime.load(current) }
                }
            }
            throw error
        }
    }

    suspend fun enablePlugin(id: String) {
        loadMutex.withLock {
            val plugin = pluginDao.getPlugin(id) ?: return
            pluginDao.switchEnabled(id, System.currentTimeMillis())
            runtime.load(plugin.copy(enabled = true))
        }
    }

    suspend fun disablePlugin(id: String) = loadMutex.withLock {
        pluginDao.disable(id, System.currentTimeMillis())
        if (runtimeState.value.pluginId == id) {
            runtime.destroy()
        }
    }

    suspend fun deletePlugin(id: String) = loadMutex.withLock {
        pluginDao.delete(id)
        if (runtimeState.value.pluginId == id) {
            runtime.destroy()
        }
    }

    suspend fun ensureEnabledPluginLoaded(): PluginEntity {
        val plugin = pluginDao.getEnabledPlugin() ?: throw PluginResolveException.NoEnabledPlugin()
        val state = runtimeState.value
        if (state.pluginId == plugin.id && state.initialized) return plugin
        loadMutex.withLock {
            val refreshed = pluginDao.getEnabledPlugin() ?: throw PluginResolveException.NoEnabledPlugin()
            val refreshedState = runtimeState.value
            if (refreshedState.pluginId != refreshed.id || !refreshedState.initialized) {
                runtime.load(refreshed)
            }
            return refreshed
        }
    }

    fun cancelPendingRequests() {
        runtime.cancelAllRequests()
    }

    suspend fun requestMusicUrl(
        requestKey: String,
        source: PluginSource,
        quality: String,
        info: PluginMusicInfo
    ): PluginAudioResolveResult =
        runtime.requestMusicUrl(requestKey, source, quality, info)
}
