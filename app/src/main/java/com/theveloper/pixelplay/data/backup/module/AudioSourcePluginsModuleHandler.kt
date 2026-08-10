package com.theveloper.pixelplay.data.backup.module

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.theveloper.pixelplay.data.backup.model.BackupSection
import com.theveloper.pixelplay.data.database.PluginDao
import com.theveloper.pixelplay.data.database.PluginEntity
import com.theveloper.pixelplay.di.BackupGson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioSourcePluginsModuleHandler @Inject constructor(
    private val pluginDao: PluginDao,
    @BackupGson private val gson: Gson
) : BackupModuleHandler {

    override val section = BackupSection.AUDIO_SOURCE_PLUGINS

    override suspend fun export(): String = withContext(Dispatchers.IO) {
        gson.toJson(pluginDao.getPlugins())
    }

    override suspend fun countEntries(): Int = withContext(Dispatchers.IO) {
        pluginDao.getPlugins().size
    }

    override suspend fun snapshot(): String = export()

    override suspend fun restore(payload: String) = withContext(Dispatchers.IO) {
        val type = TypeToken.getParameterized(List::class.java, PluginEntity::class.java).type
        val plugins: List<PluginEntity> = gson.fromJson(payload, type) ?: emptyList()
        pluginDao.replaceAll(normalizeEnabledState(plugins))
    }

    override suspend fun rollback(snapshot: String) = restore(snapshot)

    private fun normalizeEnabledState(plugins: List<PluginEntity>): List<PluginEntity> {
        val enabledPluginId = plugins
            .filter { it.enabled }
            .maxByOrNull { it.updatedAt }
            ?.id
        return plugins.map { plugin ->
            plugin.copy(enabled = plugin.id == enabledPluginId)
        }
    }
}
