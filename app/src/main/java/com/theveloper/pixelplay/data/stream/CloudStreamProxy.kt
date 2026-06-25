package com.theveloper.pixelplay.data.stream

import android.net.Uri
import com.theveloper.pixelplay.BuildConfig
import com.theveloper.pixelplay.data.plugin.PluginResolveException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * Abstract base class for local HTTP proxy servers that stream cloud music audio.
 *
 * Subclasses define the route, ID type, validation, allowed hosts, and URL resolution.
 * The base class handles the local proxy lifecycle, URL caching, and OkHttp proxying
 * with security checks via [CloudStreamSecurity].
 *
 * @param K The song identifier type (e.g. [String] for QQ Music songMid, [Long] for Netease songId)
 */
abstract class CloudStreamProxy<K : Any>(
    private val okHttpClient: OkHttpClient
) {
    // ─── Subclass Configuration ────────────────────────────────────────

    protected abstract val allowedHostSuffixes: Set<String>
    protected abstract val cacheExpirationMs: Long
    protected abstract val proxyTag: String
    protected open val allowHttpWithoutHostAllowlist: Boolean = false

    /** Route path registered with the local server, e.g. "/qqmusic/{songMid}" */
    protected abstract val routePath: String
    /** The parameter name inside the route path, e.g. "songMid" */
    protected abstract val routeParamName: String
    /** URI scheme this proxy handles, e.g. "qqmusic" or "netease" */
    protected abstract val uriScheme: String
    /** URL path prefix for proxy URLs, e.g. "/qqmusic" or "/netease" */
    protected abstract val routePrefix: String

    /** Parse the raw route parameter string into the typed ID, or null if invalid */
    protected abstract fun parseRouteParam(value: String): K?
    /** Validate whether the given ID is acceptable */
    protected abstract fun validateId(id: K): Boolean
    /** Convert the ID to a string for use in URLs */
    protected abstract fun formatIdForUrl(id: K): String
    /** Resolve the actual streaming URL for the given song ID. */
    protected open suspend fun resolveStreamUrl(id: K): String? = null

    /** Resolve the actual streaming URL and request metadata for the given song ID. */
    protected open suspend fun resolveStream(id: K): ResolvedStream? =
        resolveStreamUrl(id)?.let(ResolvedStream::fromUrl)

    /** Cache key for resolved streams. Subclasses may include plugin/version/quality identity. */
    protected open fun cacheKeyFor(id: K): String = formatIdForUrl(id)

    /** Called whenever a stream is made available, including cache hits. */
    protected open fun onStreamAvailable(id: K, stream: ResolvedStream) = Unit

    // ─── Server State ──────────────────────────────────────────────────

    private var serverSocket: ServerSocket? = null
    private var actualPort: Int = 0
    private val proxyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startJob: Job? = null

    private val urlCache = ConcurrentHashMap<String, CachedStream>()
    private val inFlightResolves = ConcurrentHashMap<String, Deferred<ResolvedStream?>>()
    private val clientSockets = ConcurrentHashMap.newKeySet<Socket>()

    private data class CachedStream(val stream: ResolvedStream, val timestamp: Long, val expirationMs: Long) {
        fun isExpired(now: Long = System.currentTimeMillis()): Boolean {
            val declaredExpiry = stream.expireAt
            if (declaredExpiry != null && declaredExpiry <= now) return true
            return now - timestamp > expirationMs
        }
    }

    private data class LocalHttpRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>
    )

    // ─── Public API ────────────────────────────────────────────────────

    fun isReady(): Boolean = actualPort > 0

    fun startIfNeeded() {
        if (isReady() || startJob?.isActive == true) return
        start()
    }

    suspend fun awaitReady(timeoutMs: Long = 10_000L): Boolean {
        if (isReady()) return true
        val stepMs = 50L
        var elapsed = 0L
        while (elapsed < timeoutMs) {
            if (isReady()) return true
            delay(stepMs)
            elapsed += stepMs
        }
        return false
    }

    suspend fun ensureReady(timeoutMs: Long = 10_000L): Boolean {
        startIfNeeded()
        return awaitReady(timeoutMs)
    }

    fun getProxyUrl(id: K): String {
        if (actualPort == 0) return ""
        if (!validateId(id)) return ""
        return "http://127.0.0.1:$actualPort$routePrefix/${formatIdForUrl(id)}"
    }

    /**
     * Parse a cloud URI (e.g. "qqmusic://xxxx" or "netease://12345") and return
     * the local proxy URL. Returns null if the URI doesn't match this proxy's scheme.
     */
    fun resolveUri(uriString: String): String? {
        val uri = Uri.parse(uriString)
        if (uri.scheme != uriScheme) return null
        val rawId = extractIdFromUri(uri) ?: return null
        val id = parseRouteParam(rawId) ?: return null
        if (!validateId(id)) return null
        return getProxyUrl(id)
    }

    fun start() {
        startJob?.cancel()
        startJob = proxyScope.launch {
            var socket: ServerSocket? = null
            try {
                socket = ServerSocket(0)
                serverSocket = socket
                actualPort = socket.localPort
                Timber.d("$proxyTag started on port $actualPort")

                while (isActive && !socket.isClosed) {
                    val client = withContext(Dispatchers.IO) { socket.accept() }
                    clientSockets.add(client)
                    launch(Dispatchers.IO) {
                        try {
                            client.use { handleClient(it) }
                        } finally {
                            clientSockets.remove(client)
                        }
                    }
                }
            } catch (_: CancellationException) {
                Timber.d("$proxyTag start cancelled")
            } catch (_: SocketException) {
                if (isActive) {
                    Timber.d("$proxyTag server socket closed")
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to start $proxyTag")
            } finally {
                if (serverSocket === socket) {
                    serverSocket = null
                    actualPort = 0
                }
                runCatching { socket?.close() }
            }
        }
    }

    fun stop() {
        startJob?.cancel()
        startJob = null
        runCatching { serverSocket?.close() }
        clientSockets.forEach { socket -> runCatching { socket.close() } }
        clientSockets.clear()
        proxyScope.coroutineContext.cancelChildren()
        serverSocket = null
        actualPort = 0
        urlCache.clear()
        Timber.d("$proxyTag stopped")
    }

    // ─── Overridable Hooks ─────────────────────────────────────────────

    /** Extract the raw ID string from a parsed URI. Override for custom URI layouts. */
    protected open fun extractIdFromUri(uri: Uri): String? = uri.host

    // ─── Internal ──────────────────────────────────────────────────────

    protected suspend fun getOrFetchStreamUrl(id: K): String? = getOrFetchStream(id)?.url

    protected suspend fun getOrFetchStream(id: K): ResolvedStream? {
        val cacheKey = cacheKeyFor(id)
        urlCache[cacheKey]?.let { cached ->
            if (!cached.isExpired()) {
                onStreamAvailable(id, cached.stream)
                return cached.stream
            }
        }

        val newResolve = proxyScope.async(start = CoroutineStart.LAZY) {
            resolveAndCacheStream(id, cacheKey)
        }
        val activeResolve = inFlightResolves.putIfAbsent(cacheKey, newResolve)
        if (activeResolve != null) {
            newResolve.cancel()
            return activeResolve.await()
        }

        return try {
            newResolve.start()
            newResolve.await()
        } finally {
            inFlightResolves.remove(cacheKey, newResolve)
        }
    }

    private suspend fun resolveAndCacheStream(id: K, cacheKey: String): ResolvedStream? {
        return resolveStream(id)?.also { stream ->
            val effectiveKey = stream.cacheKeySuffix ?: cacheKey
            val expirationMs = stream.expireAt
                ?.let { (it - System.currentTimeMillis()).coerceAtLeast(0L) }
                ?: cacheExpirationMs
            val cached = CachedStream(stream, System.currentTimeMillis(), expirationMs)
            urlCache[cacheKey] = cached
            if (effectiveKey != cacheKey) {
                urlCache[effectiveKey] = cached
            }
            onStreamAvailable(id, stream)
        }
    }

    private suspend fun handleClient(socket: Socket) {
        socket.tcpNoDelay = true
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())
        val request = readRequest(input)
        if (request == null) {
            writeTextResponse(output, 400, "Bad Request", "Invalid request")
            return
        }
        if (request.method != "GET" && request.method != "HEAD") {
            writeTextResponse(output, 405, "Method Not Allowed", "Method not allowed")
            return
        }

        val id = extractRouteId(request.path)
        if (id == null || !validateId(id)) {
            writeTextResponse(output, 400, "Bad Request", "Invalid ID")
            return
        }

        try {
            if (BuildConfig.DEBUG) {
                Timber.d("$proxyTag request id=$id range=${request.headers["range"].orEmpty()}")
            }

            val rangeValidation = CloudStreamSecurity.validateRangeHeader(request.headers["range"])
            if (!rangeValidation.isValid) {
                writeTextResponse(output, 416, "Range Not Satisfiable", "Invalid range header")
                return
            }

            val stream = try {
                getOrFetchStream(id)
            } catch (e: Exception) {
                val status = if (e is PluginResolveException) 404 else 502
                val reason = if (status == 404) "Not Found" else "Bad Gateway"
                if (e is PluginResolveException) {
                    Timber.w("$proxyTag failed to resolve stream for id=$id: ${e.message}")
                } else {
                    Timber.w(e, "$proxyTag failed to resolve stream for id=$id")
                }
                writeTextResponse(output, status, reason, e.message ?: "Stream URL resolution failed")
                return
            }

            if (stream?.url.isNullOrBlank()) {
                Timber.w("$proxyTag no stream URL available for id=$id")
                writeTextResponse(output, 404, "Not Found", "No stream URL available")
                return
            }

            val streamUrl = stream.url
            if (BuildConfig.DEBUG) {
                Timber.d(
                    "$proxyTag resolved id=$id upstream=${describeUrl(streamUrl)} " +
                        "headers=${stream.headers.size} quality=${stream.quality.orEmpty()} " +
                        "trial=${stream.isTrial}"
                )
            }

            if (!CloudStreamSecurity.isSafeRemoteStreamUrl(
                    url = streamUrl,
                    allowedHostSuffixes = allowedHostSuffixes,
                    allowHttpForAllowedHosts = true,
                    allowHttpWithoutHostAllowlist = allowHttpWithoutHostAllowlist
                )
            ) {
                Timber.w("$proxyTag rejected upstream URL for id=$id url=$streamUrl")
                writeTextResponse(output, 502, "Bad Gateway", "Rejected upstream stream URL")
                return
            }

            proxyUpstream(
                id = id,
                stream = stream,
                rangeHeader = rangeValidation.normalizedHeader,
                headOnly = request.method == "HEAD",
                output = output
            )
        } catch (e: Exception) {
            if (!isExpectedClientDisconnect(e)) {
                Timber.w(e, "$proxyTag stream failed")
            }
        }
    }

    private fun readRequest(input: BufferedInputStream): LocalHttpRequest? {
        val requestLine = readAsciiLine(input)?.takeIf { it.isNotBlank() } ?: return null
        val parts = requestLine.split(' ', limit = 3)
        if (parts.size < 2) return null

        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = readAsciiLine(input) ?: return null
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            val name = line.substring(0, separator).trim().lowercase()
            val value = line.substring(separator + 1).trim()
            headers[name] = value
        }

        return LocalHttpRequest(
            method = parts[0].uppercase(),
            path = parts[1],
            headers = headers
        )
    }

    private fun readAsciiLine(input: BufferedInputStream): String? {
        val bytes = ArrayList<Byte>(128)
        while (true) {
            val read = input.read()
            if (read == -1) {
                return if (bytes.isEmpty()) null else bytes.toByteArray().toString(StandardCharsets.ISO_8859_1)
            }
            if (read == '\n'.code) {
                if (bytes.lastOrNull() == '\r'.code.toByte()) {
                    bytes.removeAt(bytes.lastIndex)
                }
                return bytes.toByteArray().toString(StandardCharsets.ISO_8859_1)
            }
            if (bytes.size >= MAX_HEADER_LINE_BYTES) return null
            bytes.add(read.toByte())
        }
    }

    private fun extractRouteId(path: String): K? {
        val route = path.substringBefore('?')
        val prefix = "$routePrefix/"
        if (!route.startsWith(prefix)) return null
        val rawId = route.removePrefix(prefix).substringBefore('/').takeIf { it.isNotBlank() } ?: return null
        val decoded = runCatching {
            URLDecoder.decode(rawId, StandardCharsets.UTF_8.name())
        }.getOrDefault(rawId)
        return parseRouteParam(decoded)
    }

    private fun proxyUpstream(
        id: K,
        stream: ResolvedStream,
        rangeHeader: String?,
        headOnly: Boolean,
        output: BufferedOutputStream
    ) {
        val requestBuilder = Request.Builder().url(stream.url)
        stream.headers.forEach { (name, value) ->
            requestBuilder.header(name, value)
        }
        rangeHeader?.let { requestBuilder.header("Range", it) }
        if (headOnly) {
            requestBuilder.head()
        }

        okHttpClient.newCall(requestBuilder.build()).execute().use { upstream ->
            if (upstream.code != 200 && upstream.code != 206) {
                val mapped = CloudStreamSecurity.mapUpstreamStatusToProxyStatus(upstream.code)
                Timber.w(
                    "$proxyTag upstream rejected id=$id code=${upstream.code} " +
                        "url=${describeUrl(stream.url)} contentType=${upstream.header("Content-Type").orEmpty()}"
                )
                writeTextResponse(
                    output = output,
                    statusCode = mapped.value,
                    reason = mapped.description,
                    body = "Upstream stream request failed"
                )
                return
            }

            val contentTypeHeader = upstream.header("Content-Type")
            if (!CloudStreamSecurity.isSupportedAudioContentType(contentTypeHeader)) {
                Timber.w(
                    "$proxyTag unsupported content type id=$id " +
                        "contentType=${contentTypeHeader.orEmpty()} url=${describeUrl(stream.url)}"
                )
                writeTextResponse(output, 502, "Bad Gateway", "Unsupported stream content type")
                return
            }

            val contentLength = upstream.header("Content-Length")
            if (!CloudStreamSecurity.isAcceptableContentLength(contentLength)) {
                writeTextResponse(output, 413, "Payload Too Large", "Stream content too large")
                return
            }

            val statusCode = if (upstream.code == 206) 206 else 200
            val reason = if (statusCode == 206) "Partial Content" else "OK"
            val headers = linkedMapOf(
                "Accept-Ranges" to (upstream.header("Accept-Ranges") ?: "bytes"),
                "Connection" to "close",
                "Content-Type" to (contentTypeHeader?.substringBefore(';')?.trim()?.takeIf { it.isNotBlank() }
                    ?: "application/octet-stream")
            )
            contentLength?.let { headers["Content-Length"] = it }
            upstream.header("Content-Range")?.let { headers["Content-Range"] = it }
            writeHeaders(output, statusCode, reason, headers)

            if (!headOnly) {
                val body = upstream.body
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                    }
                }
            }
            output.flush()
        }
    }

    private fun writeTextResponse(
        output: BufferedOutputStream,
        statusCode: Int,
        reason: String,
        body: String
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        writeHeaders(
            output = output,
            statusCode = statusCode,
            reason = reason,
            headers = linkedMapOf(
                "Connection" to "close",
                "Content-Type" to "text/plain; charset=utf-8",
                "Content-Length" to bytes.size.toString()
            )
        )
        output.write(bytes)
        output.flush()
    }

    private fun writeHeaders(
        output: BufferedOutputStream,
        statusCode: Int,
        reason: String,
        headers: Map<String, String>
    ) {
        val builder = StringBuilder()
            .append("HTTP/1.1 ")
            .append(statusCode)
            .append(' ')
            .append(reason)
            .append("\r\n")
        headers.forEach { (name, value) ->
            builder.append(name).append(": ").append(value).append("\r\n")
        }
        builder.append("\r\n")
        output.write(builder.toString().toByteArray(StandardCharsets.ISO_8859_1))
    }

    private fun isExpectedClientDisconnect(error: Exception): Boolean {
        if (error is SocketException) return true
        if (error is IOException) {
            val msg = error.message.orEmpty()
            return msg.contains("Broken pipe", ignoreCase = true) ||
                msg.contains("Connection reset", ignoreCase = true) ||
                msg.contains("Socket closed", ignoreCase = true)
        }
        val msg = error.toString()
        return msg.contains("ClosedChannelException") ||
            msg.contains("JobCancellationException")
    }

    private fun describeUrl(url: String): String {
        val parsed = runCatching { Uri.parse(url) }.getOrNull() ?: return "<invalid>"
        val scheme = parsed.scheme ?: "?"
        val host = parsed.host ?: "?"
        val path = parsed.encodedPath.orEmpty().take(96)
        return "$scheme://$host$path"
    }

    private companion object {
        const val MAX_HEADER_LINE_BYTES = 8 * 1024
    }
}
