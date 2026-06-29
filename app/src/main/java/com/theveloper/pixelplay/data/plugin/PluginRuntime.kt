package com.theveloper.pixelplay.data.plugin

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import com.theveloper.pixelplay.BuildConfig
import com.theveloper.pixelplay.data.database.PluginEntity
import com.whl.quickjs.android.QuickJSLoader
import com.whl.quickjs.wrapper.QuickJSContext
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import kotlin.coroutines.resume

@Singleton
class PluginRuntime @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) {
    private companion object {
        const val INIT_TIMEOUT_MS = 10_000L
        const val REQUEST_TIMEOUT_MS = 20_000L
        const val HTTP_REQUEST_TIMEOUT_MS = 15_000L
        const val MAX_HTTP_RESPONSE_BYTES = 2L * 1024L * 1024L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val thread = HandlerThread("PixelPluginRuntime").also { it.start() }
    private val handler = Handler(thread.looper)
    private val pendingPluginRequests = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val pendingHttpCalls = ConcurrentHashMap<String, okhttp3.Call>()

    private var jsContext: QuickJSContext? = null
    private var key: String = ""
    private var initDeferred: CompletableDeferred<Unit>? = null

    private val _state = MutableStateFlow(PluginRuntimeState())
    val state: StateFlow<PluginRuntimeState> = _state.asStateFlow()

    suspend fun load(plugin: PluginEntity) {
        destroyContextAndWait(clearState = false)
        _state.value = PluginRuntimeState(
            pluginId = plugin.id,
            pluginName = plugin.name,
            initializing = true
        )
        val deferred = CompletableDeferred<Unit>()
        initDeferred = deferred
        try {
            withContextOnJsThread {
                QuickJSLoader.init()
                key = UUID.randomUUID().toString()
                val context = QuickJSContext.create()
                jsContext = context
                context.setConsole(PluginConsole())
                createNativeBindings(context)
                context.evaluate(readPreloadScript())
                context.getGlobalObject()
                    .getJSFunction("lx_setup")
                    .call(key, plugin.id, plugin.name, plugin.description, plugin.version, plugin.author, plugin.homepage, plugin.script)
                context.evaluate(plugin.script)
            }
            withTimeout(INIT_TIMEOUT_MS) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            val message = "音源插件初始化失败"
            addLog("error", "Plugin init timed out")
            _state.update { it.copy(initializing = false, initialized = false, errorMessage = message) }
            destroyContext(clearState = false)
            throw PluginResolveException.InitFailed(message)
        } catch (e: Exception) {
            val message = e.message ?: "音源插件初始化失败"
            addLog("error", message)
            _state.update { it.copy(initializing = false, initialized = false, errorMessage = message) }
            destroyContext(clearState = false)
            throw PluginResolveException.InitFailed(message)
        }
    }

    suspend fun requestMusicUrl(
        requestKey: String,
        source: PluginSource,
        quality: String,
        info: PluginMusicInfo
    ): PluginAudioResolveResult {
        val runtimeState = state.value
        if (!runtimeState.initialized) throw PluginResolveException.InitFailed(runtimeState.errorMessage)
        val deferred = CompletableDeferred<JSONObject>()
        pendingPluginRequests[requestKey] = deferred

        val requestJson = JSONObject()
            .put("requestKey", requestKey)
            .put(
                "data",
                JSONObject()
                    .put("source", source.lxSource)
                    .put("action", "musicUrl")
                    .put(
                        "info",
                        JSONObject()
                            .put("type", quality)
                            .put("musicInfo", info.toJson())
                            .put("id", info.sourceSongId)
                            .put("songId", info.sourceSongId)
                            .put("songmid", info.pluginSongMid())
                            .put("songMid", info.pluginSongMid())
                            .put("mid", info.pluginSongMid())
                            .put("hash", info.pluginHash())
                            .put("name", info.title)
                            .put("singer", info.artist)
                            .put("albumName", info.album)
                    )
            )
        if (BuildConfig.DEBUG) {
            Timber.d(
                "Plugin musicUrl request source=${source.lxSource} quality=$quality " +
                    "id=${info.platformSongId} title=${info.title}"
            )
        }

        try {
            withContextOnJsThread {
                callJs("request", requestJson)
            }
            val result = withTimeout(REQUEST_TIMEOUT_MS) { deferred.await() }
            return parseAudioResult(result)
        } catch (e: TimeoutCancellationException) {
            pendingPluginRequests.remove(requestKey)
            throw PluginResolveException.RequestTimeout()
        } catch (e: PluginResolveException) {
            throw e
        } catch (e: Exception) {
            throw PluginResolveException.RequestFailed(e.message ?: "音源插件解析失败")
        } finally {
            pendingPluginRequests.remove(requestKey)
        }
    }

    fun cancelAllRequests() {
        pendingPluginRequests.values.forEach { it.cancel() }
        pendingPluginRequests.clear()
        pendingHttpCalls.values.forEach { it.cancel() }
        pendingHttpCalls.clear()
    }

    fun destroy() {
        destroyContext(clearState = true)
    }

    private fun createNativeBindings(context: QuickJSContext) {
        val global = context.getGlobalObject()
        global.setProperty("__pixel_native_call__") { args ->
            if (args.getOrNull(0) == key) handleNativeCall(args.getOrNull(1) as? String, args.getOrNull(2) as? String)
            null
        }
        global.setProperty("__pixel_native_request__") { args ->
            if (args.getOrNull(0) == key) handleHttpRequest(args.getOrNull(1) as? String)
            null
        }
        global.setProperty("__pixel_native_set_timeout__") { args ->
            if (args.getOrNull(0) == key) {
                val id = (args.getOrNull(1) as? Number)?.toInt() ?: 0
                val timeout = (args.getOrNull(2) as? Number)?.toLong() ?: 0L
                handler.postDelayed({
                    runCatching { callJs("timeout", id) }
                        .onFailure { addLog("error", "Timer callback failed: ${it.message}") }
                }, timeout)
            }
            null
        }
        global.setProperty("__pixel_native_md5__") { args ->
            val encoded = args.getOrNull(0) as? String ?: ""
            md5(URLDecoder.decode(encoded, StandardCharsets.UTF_8.name()))
        }
        global.setProperty("__pixel_native_b64__") { args ->
            Base64.encodeToString((args.getOrNull(0) as? String).orEmpty().toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
        }
        global.setProperty("__pixel_native_b64_to_bytes__") { args ->
            val bytes = Base64.decode((args.getOrNull(0) as? String).orEmpty(), Base64.NO_WRAP)
            bytes.joinToString(prefix = "[", postfix = "]") { (it.toInt() and 0xff).toString() }
        }
        global.setProperty("__pixel_native_aes_encrypt__") { args ->
            runCatching {
                aesEncrypt(
                    dataBase64 = args.getOrNull(0) as? String ?: "",
                    keyBase64 = args.getOrNull(1) as? String ?: "",
                    ivBase64 = args.getOrNull(2) as? String ?: "",
                    transformation = args.getOrNull(3) as? String ?: "AES/CBC/PKCS5Padding"
                )
            }.getOrDefault("")
        }
        global.setProperty("__pixel_native_rsa_encrypt__") { args ->
            runCatching {
                rsaEncrypt(
                    dataBase64 = args.getOrNull(0) as? String ?: "",
                    publicKeyBase64 = args.getOrNull(1) as? String ?: "",
                    transformation = args.getOrNull(2) as? String ?: "RSA/ECB/NoPadding"
                )
            }.getOrDefault("")
        }
    }

    private fun handleNativeCall(action: String?, data: String?) {
        when (action) {
            "init" -> handleInit(data)
            "response" -> handlePluginResponse(data)
            "cancelRequest" -> handleCancelHttpRequest(data)
            else -> addLog("warn", "Unknown plugin action: $action")
        }
    }

    private fun handleInit(data: String?) {
        val root = JSONObject(data ?: "{}")
        val status = root.optBoolean("status", false)
        if (!status) {
            val message = root.optString("errorMessage", "音源插件初始化失败")
            initDeferred?.completeExceptionally(PluginResolveException.InitFailed(message))
            _state.update { it.copy(initializing = false, initialized = false, errorMessage = message) }
            return
        }
        val capabilities = parseCapabilities(root.optJSONObject("info"))
        _state.update {
            it.copy(
                initializing = false,
                initialized = true,
                errorMessage = null,
                capabilities = capabilities
            )
        }
        initDeferred?.complete(Unit)
        addLog("info", "Plugin initialized")
    }

    private fun handlePluginResponse(data: String?) {
        val root = JSONObject(data ?: "{}")
        val requestKey = root.optString("requestKey")
        val target = pendingPluginRequests[requestKey] ?: return
        if (root.optBoolean("status", false)) {
            val rawResult = root.opt("result")
            val wrapped =
                if (rawResult == null || rawResult == JSONObject.NULL) {
                    JSONObject()
                } else if (rawResult is JSONObject) {
                    rawResult
                } else if (rawResult is String) {
                    JSONObject().put("url", rawResult)
                } else {
                    JSONObject()
                }
            target.complete(wrapped)
        } else {
            target.completeExceptionally(
                PluginResolveException.RequestFailed(root.optString("errorMessage", "音源插件解析失败"))
            )
        }
    }

    private fun handleHttpRequest(rawData: String?) {
        val request = JSONObject(rawData ?: "{}")
        val requestKey = request.optString("requestKey")
        val url = request.optString("url")
        val options = request.optJSONObject("options") ?: JSONObject()
        scope.launch {
            val responseJson = JSONObject().put("requestKey", requestKey)
            try {
                PluginAudioSourceValidator.validateUrl(url)
                val method = options.optString("method", "get").uppercase()
                val builder = Request.Builder().url(url)
                val headers = options.optJSONObject("headers")
                if (headers != null) {
                    headers.keys().forEach { headerName ->
                        builder.header(headerName, headers.optString(headerName))
                    }
                }
                val bodyText = options.opt("body").toRequestBodyText()
                if (BuildConfig.DEBUG) {
                    Timber.d(
                        "Plugin HTTP request method=$method url=$url " +
                            "bodyLength=${bodyText.length}"
                    )
                }
                if (method != "GET") {
                    val mediaType = headers
                        ?.optString("Content-Type")
                        ?.takeIf(String::isNotBlank)
                        ?: "application/octet-stream"
                    val body = bodyText.toRequestBody(mediaType.toMediaTypeOrNull())
                    builder.method(method, body)
                }
                val call = okHttpClient.newBuilder()
                    .callTimeout(HTTP_REQUEST_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .build()
                    .newCall(builder.build())
                pendingHttpCalls[requestKey] = call
                call.execute().use { response ->
                    val body = response.body
                    val bytes = body.byteStream().readBytesLimited(MAX_HTTP_RESPONSE_BYTES)
                    val bodyText = String(bytes, StandardCharsets.UTF_8)
                    val contentType = response.header("Content-Type").orEmpty()
                    responseJson.put(
                        "response",
                        JSONObject()
                            .put("statusCode", response.code)
                            .put("statusMessage", response.message)
                            .put("headers", JSONObject(response.headers.toMultimap().mapValues { it.value.joinToString(",") }))
                            .put("body", bodyText.toPluginResponseBody(contentType))
                    )
                }
            } catch (e: Exception) {
                responseJson.put("error", e.message ?: "request failed")
            } finally {
                pendingHttpCalls.remove(requestKey)
                handler.post {
                    runCatching { callJs("httpResponse", responseJson) }
                        .onFailure { addLog("error", "HTTP response callback failed: ${it.message}") }
                }
            }
        }
    }

    private fun handleCancelHttpRequest(rawData: String?) {
        val requestKey = JSONObject(rawData ?: "{}").optString("requestKey")
        pendingHttpCalls.remove(requestKey)?.cancel()
    }

    private fun parseAudioResult(raw: JSONObject): PluginAudioResolveResult {
        val candidate = raw.optJSONObject("data") ?: raw
        val headersObject = candidate.optJSONObject("headers")
        val headers = buildMap {
            if (headersObject != null) {
                headersObject.keys().forEach { key -> put(key, headersObject.optString(key)) }
            }
        }
        return PluginAudioResolveResult(
            url = candidate.optString("url"),
            headers = headers,
            quality = candidate.optString("quality", candidate.optString("type", "")).ifBlank { null },
            source = candidate.optString("source", "").ifBlank { null },
            expireAt = candidate.optLongOrNull("expireAt"),
            isTrial = candidate.optBoolean("isTrial", false),
            durationMs = candidate.optLongOrNull("durationMs"),
            bitrate = candidate.optIntOrNull("bitrate"),
            mimeType = candidate.optString("mimeType", "").ifBlank { null }
        )
    }

    private fun parseCapabilities(info: JSONObject?): PluginCapabilities {
        val sourcesObject = info?.optJSONObject("sources") ?: return PluginCapabilities()
        val sources = linkedMapOf<String, PluginSourceCapability>()
        sourcesObject.keys().forEach { rawSource ->
            val sourceObject = sourcesObject.optJSONObject(rawSource) ?: return@forEach
            val canonicalSource = PluginSource.fromAlias(rawSource)?.lxSource ?: rawSource
            val actions = sourceObject.optJSONArray("actions")?.toStringSet().orEmpty()
            val qualities = sourceObject.optJSONArray("qualitys")?.toStringSet()
                ?: sourceObject.optJSONArray("qualities")?.toStringSet()
                ?: emptySet()
            val current = sources[canonicalSource]
            sources[canonicalSource] = PluginSourceCapability(
                source = canonicalSource,
                actions = current?.actions.orEmpty() + actions,
                qualities = current?.qualities.orEmpty() + qualities.mapNotNullTo(linkedSetOf()) { quality ->
                    PluginQuality.canonicalize(quality) ?: quality
                }
            )
        }
        return PluginCapabilities(sources)
    }

    private fun callJs(action: String, data: Any? = null): Any? {
        val context = jsContext ?: throw IllegalStateException("QuickJS context is not ready")
        val json = when (data) {
            null -> null
            is JSONObject -> data.toString()
            is Number -> data.toString()
            is String -> data
            else -> JSONObject.wrap(data)?.toString() ?: "null"
        }
        return if (json == null) {
            context.getGlobalObject().getJSFunction("__pixel_native__").call(key, action)
        } else {
            context.getGlobalObject().getJSFunction("__pixel_native__").call(key, action, json)
        }
    }

    private suspend fun <T> withContextOnJsThread(block: () -> T): T =
        suspendCancellableCoroutine { continuation ->
            handler.post {
                try {
                    continuation.resume(block())
                } catch (e: Throwable) {
                    continuation.cancel(e)
                }
            }
        }

    private fun destroyContext(clearState: Boolean) {
        cancelAllRequests()
        handler.post {
            runCatching { jsContext?.destroy() }
            jsContext = null
            initDeferred = null
        }
        if (clearState) _state.value = PluginRuntimeState()
    }

    private suspend fun destroyContextAndWait(clearState: Boolean) {
        cancelAllRequests()
        withContextOnJsThread {
            runCatching { jsContext?.destroy() }
            jsContext = null
            initDeferred = null
        }
        if (clearState) _state.value = PluginRuntimeState()
    }

    private fun readPreloadScript(): String =
        context.assets.open("script/pixel-plugin-preload.js").bufferedReader().use { it.readText() }

    private fun addLog(level: String, message: String) {
        val trimmed = message.take(1024)
        _state.update { current ->
            current.copy(logs = (listOf(PluginLogEntry(System.currentTimeMillis(), level, trimmed)) + current.logs).take(80))
        }
        when (level) {
            "error" -> Timber.e(trimmed)
            "warn" -> Timber.w(trimmed)
            else -> Timber.d(trimmed)
        }
    }

    private inner class PluginConsole : QuickJSContext.Console {
        override fun log(info: String?) = addLog("log", info.orEmpty())
        override fun info(info: String?) = addLog("info", info.orEmpty())
        override fun warn(info: String?) = addLog("warn", info.orEmpty())
        override fun error(info: String?) = addLog("error", info.orEmpty())
    }
}

private fun PluginMusicInfo.toJson(): JSONObject {
    val hashValue = pluginHash()
    val songMidValue = pluginSongMid()
    return JSONObject()
        .put("id", sourceSongId)
        .put("songId", sourceSongId)
        .put("originId", id)
        .put("source", source.lxSource)
        .put("hash", hashValue)
        .put("songmid", songMidValue)
        .put("songMid", songMidValue)
        .put("mid", songMidValue)
        .put("name", title)
        .put("singer", artist)
        .put("albumName", album)
        .put("albumId", "")
        .put("interval", durationMs)
        .put("img", albumArtUrl)
        .put("pic", albumArtUrl)
        .put("title", title)
        .put("artist", artist)
        .put("album", album)
        .put("durationMs", durationMs)
        .put("albumArtUrl", albumArtUrl)
        .put("rawContentUri", rawContentUri)
        .put("platformSongId", platformSongId)
        .put("sourceSongId", sourceSongId)
        .put("sourceSongMid", sourceSongMid)
        .put("sourceSongHash", sourceSongHash)
}

private fun PluginMusicInfo.pluginHash(): String? =
    when (source) {
        PluginSource.KUGOU -> sourceSongHash ?: sourceSongId
        else -> sourceSongHash
    }

private fun PluginMusicInfo.pluginSongMid(): String =
    sourceSongMid ?: sourceSongId

private fun org.json.JSONArray.toStringSet(): Set<String> =
    (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }.toSet()

private fun JSONObject.optLongOrNull(name: String): Long? =
    if (has(name) && !isNull(name)) optLong(name) else null

private fun JSONObject.optIntOrNull(name: String): Int? =
    if (has(name) && !isNull(name)) optInt(name) else null

private fun String.toPluginResponseBody(contentType: String): Any {
    val trimmed = trim()
    val looksLikeJson = contentType.contains("json", ignoreCase = true) ||
        trimmed.startsWith("{") ||
        trimmed.startsWith("[")
    if (!looksLikeJson || trimmed.isEmpty()) return this
    return runCatching {
        if (trimmed.startsWith("{")) JSONObject(trimmed) else JSONArray(trimmed)
    }.getOrDefault(this)
}

private fun Any?.toRequestBodyText(): String =
    when (this) {
        null, JSONObject.NULL -> ""
        is JSONObject -> toString()
        is JSONArray -> toString()
        else -> toString()
    }

private fun java.io.InputStream.readBytesLimited(maxBytes: Long): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0L
    while (true) {
        val read = read(buffer)
        if (read == -1) break
        total += read
        if (total > maxBytes) throw IllegalStateException("Response body too large")
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

private fun md5(value: String): String {
    val bytes = MessageDigest.getInstance("MD5").digest(value.toByteArray(StandardCharsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}

private fun aesEncrypt(dataBase64: String, keyBase64: String, ivBase64: String, transformation: String): String {
    val data = Base64.decode(dataBase64, Base64.NO_WRAP)
    val key = Base64.decode(keyBase64, Base64.NO_WRAP)
    val cipher = Cipher.getInstance(transformation)
    val secretKey = SecretKeySpec(key, "AES")
    if (transformation.contains("/ECB/")) {
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
    } else {
        val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, IvParameterSpec(iv))
    }
    return Base64.encodeToString(cipher.doFinal(data), Base64.NO_WRAP)
}

private fun rsaEncrypt(dataBase64: String, publicKeyBase64: String, transformation: String): String {
    val data = Base64.decode(dataBase64, Base64.NO_WRAP)
    val keyBytes = Base64.decode(publicKeyBase64, Base64.NO_WRAP)
    val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(keyBytes))
    val cipher = Cipher.getInstance(transformation)
    cipher.init(Cipher.ENCRYPT_MODE, publicKey)
    return Base64.encodeToString(cipher.doFinal(data), Base64.NO_WRAP)
}
