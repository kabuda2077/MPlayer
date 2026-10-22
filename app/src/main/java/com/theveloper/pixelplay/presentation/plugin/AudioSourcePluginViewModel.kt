package com.theveloper.pixelplay.presentation.plugin

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.database.PluginEntity
import com.theveloper.pixelplay.data.plugin.PluginScriptContent
import com.theveloper.pixelplay.data.plugin.PluginUpdateCandidate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.theveloper.pixelplay.data.plugin.PluginQuality
import com.theveloper.pixelplay.data.plugin.PluginRepository
import com.theveloper.pixelplay.data.plugin.PluginRuntimeState
import com.theveloper.pixelplay.data.plugin.PluginResolveException
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AudioSourcePluginUiState(
    val plugins: List<PluginEntity> = emptyList(),
    val runtimeState: PluginRuntimeState = PluginRuntimeState(),
    val preferredQuality: PluginQuality = PluginQuality.HIGH,
    val busy: Boolean = false,
    val message: String? = null,
    val pendingUpdate: PluginUpdateCandidate? = null
)

@HiltViewModel
class AudioSourcePluginViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pluginRepository: PluginRepository,
    private val userPreferencesRepository: UserPreferencesRepository
) : ViewModel() {
    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    private val pendingUpdate = MutableStateFlow<PluginUpdateCandidate?>(null)

    private val pluginSettingsState = combine(
        pluginRepository.observePlugins(),
        pluginRepository.runtimeState,
        userPreferencesRepository.audioSourcePluginPreferredQualityFlow
    ) { plugins, runtimeState, preferredQualityValue ->
        PluginSettingsData(plugins, runtimeState, preferredQualityValue)
    }

    val uiState: StateFlow<AudioSourcePluginUiState> = combine(
        pluginSettingsState,
        busy,
        message,
        pendingUpdate
    ) { pluginSettings, busyValue, messageValue, updateValue ->
        val (plugins, runtimeState, preferredQualityValue) = pluginSettings
        AudioSourcePluginUiState(
            plugins = plugins,
            runtimeState = runtimeState,
            preferredQuality = preferredQualityValue,
            busy = busyValue,
            message = messageValue,
            pendingUpdate = updateValue
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AudioSourcePluginUiState())

    fun importPlugin(uri: Uri) {
        viewModelScope.launch {
            runBusy("插件导入失败") {
                val script = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)
                        ?.use(PluginScriptContent::read)
                        ?: error("无法读取插件文件")
                }
                val plugin = withContext(Dispatchers.IO) { pluginRepository.importScript(script) }
                message.value = "已导入 ${plugin.name}"
            }
        }
    }

    fun importFromUrl(url: String) {
        viewModelScope.launch {
            runBusy("在线导入失败") {
                val plugin = withContext(Dispatchers.IO) { pluginRepository.importFromUrl(url) }
                message.value = "已导入 ${plugin.name}"
            }
        }
    }

    fun checkUpdate(id: String, url: String? = null) {
        viewModelScope.launch {
            runBusy("检查更新失败") {
                pendingUpdate.value = null
                pendingUpdate.value = withContext(Dispatchers.IO) { pluginRepository.checkUpdate(id, url) }
                if (pendingUpdate.value == null) message.value = "与来源链接中的脚本一致，暂无更新"
            }
        }
    }

    fun applyUpdate() {
        val candidate = pendingUpdate.value ?: return
        viewModelScope.launch {
            runBusy("更新失败") {
                pluginRepository.applyUpdate(candidate)
                pendingUpdate.value = null
                message.value = "已更新 ${candidate.metadata.name}"
            }
        }
    }

    fun dismissUpdate() {
        if (!busy.value) pendingUpdate.value = null
    }

    fun enablePlugin(id: String) {
        viewModelScope.launch {
            try {
                pluginRepository.enablePlugin(id)
                message.value = "音源插件已启用"
            } catch (e: CancellationException) {
                throw e
            } catch (e: PluginResolveException) {
                message.value = e.message ?: "插件启用失败"
            } catch (e: Exception) {
                message.value = "插件启用失败：${e.message ?: "未知错误"}"
            }
        }
    }

    fun disablePlugin(id: String) {
        viewModelScope.launch {
            try {
                pluginRepository.disablePlugin(id)
                message.value = "音源插件已停用"
            } catch (e: CancellationException) {
                throw e
            } catch (e: PluginResolveException) {
                message.value = e.message ?: "插件停用失败"
            } catch (e: Exception) {
                message.value = "插件停用失败：${e.message ?: "未知错误"}"
            }
        }
    }

    fun deletePlugin(id: String) {
        viewModelScope.launch {
            runBusy("插件删除失败") {
                pluginRepository.deletePlugin(id)
                message.value = "音源插件已删除"
            }
        }
    }

    fun setPreferredQuality(quality: PluginQuality) {
        viewModelScope.launch {
            userPreferencesRepository.setAudioSourcePluginPreferredQuality(quality)
            message.value = "首选音质已设置为 ${quality.displayName}"
        }
    }

    fun clearMessage() {
        message.value = null
    }

    private suspend fun runBusy(prefix: String, block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: PluginResolveException) {
            message.value = e.message ?: prefix
        } catch (e: Exception) {
            message.value = "$prefix：${e.message ?: "未知错误"}"
        } finally {
            busy.value = false
        }
    }

    private data class PluginSettingsData(
        val plugins: List<PluginEntity>,
        val runtimeState: PluginRuntimeState,
        val preferredQuality: PluginQuality
    )
}
