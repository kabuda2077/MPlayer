package com.theveloper.pixelplay.presentation.plugin

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.database.PluginEntity
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
    val message: String? = null
)

@HiltViewModel
class AudioSourcePluginViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pluginRepository: PluginRepository,
    private val userPreferencesRepository: UserPreferencesRepository
) : ViewModel() {
    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    private val pluginSettingsState = combine(
        pluginRepository.observePlugins(),
        pluginRepository.runtimeState,
        userPreferencesRepository.audioSourcePluginPreferredQualityFlow
    ) { plugins, runtimeState, preferredQualityValue ->
        Triple(plugins, runtimeState, preferredQualityValue)
    }

    val uiState: StateFlow<AudioSourcePluginUiState> = combine(
        pluginSettingsState,
        busy,
        message
    ) { pluginSettings, busyValue, messageValue ->
        val (plugins, runtimeState, preferredQualityValue) = pluginSettings
        AudioSourcePluginUiState(
            plugins = plugins,
            runtimeState = runtimeState,
            preferredQuality = preferredQualityValue,
            busy = busyValue,
            message = messageValue
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AudioSourcePluginUiState())

    fun importPlugin(uri: Uri) {
        viewModelScope.launch {
            runBusy("插件导入失败") {
                val script = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    ?: error("无法读取插件文件")
                val plugin = pluginRepository.importScript(script)
                message.value = "已导入 ${plugin.name}"
            }
        }
    }

    fun enablePlugin(id: String) {
        viewModelScope.launch {
            runBusy("插件启用失败") {
                pluginRepository.enablePlugin(id)
                message.value = "音源插件已启用"
            }
        }
    }

    fun disablePlugin(id: String) {
        viewModelScope.launch {
            runBusy("插件停用失败") {
                pluginRepository.disablePlugin(id)
                message.value = "音源插件已停用"
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
        busy.value = true
        try {
            block()
        } catch (e: PluginResolveException) {
            message.value = e.message ?: prefix
        } catch (e: Exception) {
            message.value = "$prefix：${e.message ?: "未知错误"}"
        } finally {
            busy.value = false
        }
    }
}
