package com.theveloper.pixelplay.presentation.online

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.model.OnlineBrowsePlatform
import com.theveloper.pixelplay.data.model.RemotePlaylistReference
import com.theveloper.pixelplay.data.network.netease.NeteaseApiService
import com.theveloper.pixelplay.data.network.qqmusic.QqMusicApiService
import com.theveloper.pixelplay.data.recommendation.RecommendationJson
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject

data class OnlineCategoryUiState(
    val title: String = "",
    val subtitle: String = "",
    val categoryKey: String = "",
    val platform: OnlineBrowsePlatform = OnlineBrowsePlatform.NETEASE,
    val playlists: List<RemotePlaylistReference> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null
)

@HiltViewModel
class OnlineCategoryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val neteaseApiService: NeteaseApiService,
    private val qqMusicApiService: QqMusicApiService
) : ViewModel() {

    val title: String = savedStateHandle.get<String>("title").orEmpty().ifBlank { "探索分类" }
    val subtitle: String = savedStateHandle.get<String>("subtitle").orEmpty()
    val categoryKey: String = savedStateHandle.get<String>("categoryKey").orEmpty().ifBlank { title }

    private val initialPlatform: OnlineBrowsePlatform = runCatching {
        OnlineBrowsePlatform.valueOf(savedStateHandle.get<String>("platform").orEmpty())
    }.getOrDefault(OnlineBrowsePlatform.NETEASE)

    private val platformCache = mutableMapOf<OnlineBrowsePlatform, List<RemotePlaylistReference>>()

    private val _uiState = MutableStateFlow(
        OnlineCategoryUiState(
            title = title,
            subtitle = subtitle,
            categoryKey = categoryKey,
            platform = initialPlatform,
            isLoading = true
        )
    )
    val uiState: StateFlow<OnlineCategoryUiState> = _uiState.asStateFlow()

    init {
        loadPlaylists(initialPlatform)
    }

    fun switchPlatform(platform: OnlineBrowsePlatform) {
        if (_uiState.value.platform == platform && _uiState.value.playlists.isNotEmpty()) return
        _uiState.update { it.copy(platform = platform) }
        loadPlaylists(platform)
    }

    fun retry() {
        loadPlaylists(_uiState.value.platform, force = true)
    }

    private fun loadPlaylists(platform: OnlineBrowsePlatform, force: Boolean = false) {
        viewModelScope.launch {
            if (!force) {
                val cached = platformCache[platform]
                if (!cached.isNullOrEmpty()) {
                    _uiState.update { it.copy(platform = platform, playlists = cached, isLoading = false, error = null) }
                    return@launch
                }
            }

            _uiState.update { it.copy(platform = platform, isLoading = true, error = null) }

            try {
                val playlists = withContext(Dispatchers.IO) {
                    when (platform) {
                        OnlineBrowsePlatform.NETEASE -> fetchNeteaseCategoryPlaylists(categoryKey)
                        OnlineBrowsePlatform.QQ -> fetchQqCategoryPlaylists(categoryKey)
                    }
                }
                platformCache[platform] = playlists
                _uiState.update { it.copy(playlists = playlists, isLoading = false, error = null) }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load category playlists for platform=%s, key=%s", platform, categoryKey)
                _uiState.update { it.copy(isLoading = false, error = e.message ?: "加载歌单失败，请重试") }
            }
        }
    }

    private suspend fun fetchNeteaseCategoryPlaylists(key: String): List<RemotePlaylistReference> {
        val neteaseCat = when (key) {
            "华语流行" -> "华语"
            "欧美潮流" -> "欧美"
            "摇滚力量" -> "摇滚"
            "说唱嘻哈" -> "说唱"
            "轻松治愈" -> "治愈"
            "经典怀旧" -> "怀旧"
            "动漫游戏" -> "ACG"
            "电音派对" -> "电子"
            else -> key
        }
        val raw = neteaseApiService.getTopPlaylists(category = neteaseCat, limit = 30)
        val parsed = RecommendationJson.parseNeteasePlaylists(raw)
        return parsed.map { ref ->
            if (ref.subtitle.isNullOrBlank() && ref.songCount > 0) {
                ref.copy(subtitle = "${ref.songCount} 首歌曲")
            } else {
                ref
            }
        }
    }

    private suspend fun fetchQqCategoryPlaylists(key: String): List<RemotePlaylistReference> {
        val qqQuery = when (key) {
            "华语流行" -> "华语流行"
            "欧美潮流" -> "欧美流行"
            "摇滚力量" -> "摇滚"
            "说唱嘻哈" -> "说唱"
            "轻松治愈" -> "治愈轻音乐"
            "经典怀旧" -> "经典老歌"
            "动漫游戏" -> "ACG二次元"
            "电音派对" -> "电音EDM"
            else -> key
        }
        val raw = qqMusicApiService.searchPlaylists(keyword = qqQuery, page = 1, pageSize = 30)
        val root = JSONObject(raw)
        val items = root.optJSONObject("request")
            ?.optJSONObject("data")
            ?.optJSONObject("body")
            ?.optJSONArray("item_songlist")
            ?: JSONArray()

        return buildList {
            for (i in 0 until items.length()) {
                val it = items.optJSONObject(i) ?: continue
                val id = it.optString("dissid").takeIf { it.isNotBlank() } ?: continue
                val rawTitle = it.optString("dissname").ifBlank { "未命名歌单" }
                val cleanTitle = rawTitle.replace("<em>", "").replace("</em>", "")
                val logo = it.optString("logo")
                    .ifBlank { it.optString("imgurl") }
                    .takeIf { it.isNotBlank() }
                val songCount = it.optInt("songnum", 0)
                val listenNum = it.optLong("listennum", 0L)
                val subtitleStr = if (listenNum >= 10000) {
                    String.format(Locale.getDefault(), "%.1f万次播放", listenNum / 10000f)
                } else if (listenNum > 0) {
                    "${listenNum}次播放"
                } else if (songCount > 0) {
                    "${songCount} 首歌曲"
                } else {
                    "精选歌单"
                }
                add(
                    RemotePlaylistReference(
                        platform = OnlineBrowsePlatform.QQ,
                        remoteId = id,
                        title = cleanTitle,
                        subtitle = subtitleStr,
                        artworkUri = logo,
                        songCount = songCount
                    )
                )
            }
        }
    }
}
