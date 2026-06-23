package com.theveloper.pixelplay.data.plugin

import com.theveloper.pixelplay.data.database.PluginDao
import com.theveloper.pixelplay.data.database.PluginEntity
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class PluginRepository @Inject constructor(
    private val pluginDao: PluginDao,
    private val runtime: PluginRuntime
) {
    private val loadMutex = Mutex()

    val runtimeState: StateFlow<PluginRuntimeState> = runtime.state

    fun observePlugins(): Flow<List<PluginEntity>> = pluginDao.observePlugins()

    suspend fun getPlugins(): List<PluginEntity> = pluginDao.getPlugins()

    suspend fun importScript(script: String): PluginEntity {
        val now = System.currentTimeMillis()
        val metadata = PluginMetadataParser.parse(script, now)
        val entity = PluginEntity(
            id = UUID.randomUUID().toString(),
            name = metadata.name,
            description = metadata.description,
            author = metadata.author,
            version = metadata.version,
            homepage = metadata.homepage,
            enabled = false,
            script = script,
            createdAt = now,
            updatedAt = now
        )
        pluginDao.upsert(entity)
        return entity
    }

    suspend fun enablePlugin(id: String) {
        loadMutex.withLock {
            val plugin = pluginDao.getPlugin(id) ?: return
            runtime.destroy()
            pluginDao.disableAll(System.currentTimeMillis())
            pluginDao.enable(id, System.currentTimeMillis())
            runtime.load(plugin.copy(enabled = true))
        }
    }

    suspend fun disablePlugin(id: String) {
        pluginDao.disable(id, System.currentTimeMillis())
        if (runtimeState.value.pluginId == id) {
            runtime.destroy()
        }
    }

    suspend fun deletePlugin(id: String) {
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
