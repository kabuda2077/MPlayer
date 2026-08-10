package com.theveloper.pixelplay.data.plugin

import com.theveloper.pixelplay.data.stream.CloudStreamSecurity
import com.theveloper.pixelplay.data.stream.ResolvedStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

@Singleton
class PluginStreamVerifier @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    suspend fun verify(stream: ResolvedStream) = withContext(Dispatchers.IO) {
        val request = PluginStreamRequestHeaders.applyTo(
            builder = Request.Builder().url(stream.url),
            headers = stream.headers
        )
            .header("Range", PROBE_RANGE)
            .get()
            .build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                if (response.code != 200 && response.code != 206) {
                    throw PluginResolveException.InvalidAudioSource(
                        "插件音源不可用：HTTP ${response.code}"
                    )
                }
                if (!CloudStreamSecurity.isSupportedAudioContentType(response.header("Content-Type"))) {
                    throw PluginResolveException.InvalidAudioSource("插件返回的内容不是音频")
                }
                if (!CloudStreamSecurity.isAcceptableContentLength(response.header("Content-Length"))) {
                    throw PluginResolveException.InvalidAudioSource("插件返回的音频大小无效")
                }
            }
        } catch (error: PluginResolveException) {
            throw error
        } catch (error: IOException) {
            throw PluginResolveException.InvalidAudioSource(
                "插件音源连接失败：${error.message ?: "网络错误"}"
            )
        }
    }

    private companion object {
        const val PROBE_RANGE = "bytes=0-0"
    }
}
