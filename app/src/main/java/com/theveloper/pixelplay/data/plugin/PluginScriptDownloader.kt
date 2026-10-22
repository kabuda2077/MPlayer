package com.theveloper.pixelplay.data.plugin

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Fetches script text only; importing or checking never executes remote code. */
@Singleton
class PluginScriptDownloader @Inject constructor(client: OkHttpClient) {
    private val client = client.newBuilder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun download(url: String): String = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(PluginScriptContent.normalizeUrl(url))
            .header("Cache-Control", "no-cache").build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val script = response.use {
                        check(it.isSuccessful) { "下载失败：HTTP ${it.code}" }
                        val body = it.body
                        require(body.contentLength() <= PluginScriptContent.MAX_BYTES) { "插件文件不能超过 9 MB" }
                        PluginScriptContent.read(body.byteStream())
                    }
                    if (continuation.isActive) continuation.resume(script)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        })
    }
}

internal object PluginScriptContent {
    const val MAX_BYTES = 9_000_000L

    fun normalizeUrl(value: String): String {
        val url = value.trim().toHttpUrlOrNull()
            ?: throw IllegalArgumentException("请输入有效的 HTTP 或 HTTPS 脚本链接")
        require(url.username.isEmpty() && url.password.isEmpty()) { "链接不能包含用户名或密码" }
        return url.newBuilder().fragment(null).build().toString()
    }

    fun read(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size().toLong() + count <= MAX_BYTES) { "插件文件不能超过 9 MB" }
            output.write(buffer, 0, count)
        }
        return validate(output.toString(Charsets.UTF_8.name()))
    }

    fun validate(script: String): String {
        val normalized = script.removePrefix("\uFEFF").trim()
        require(normalized.isNotEmpty()) { "插件文件为空" }
        require(normalized.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "插件文件不能超过 9 MB" }
        require(!normalized.startsWith("<")) { "链接返回的是网页，请使用 JavaScript 脚本直链" }
        require(!normalized.startsWith("{") && !normalized.startsWith("[")) {
            "链接返回的不是音源脚本，请检查链接"
        }
        return normalized
    }
}

data class PluginUpdateCandidate(
    val pluginId: String,
    val previousScript: String,
    val sourceUrl: String,
    val script: String,
    val metadata: ImportedPluginMetadata
)
