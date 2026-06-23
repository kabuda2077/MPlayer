package com.theveloper.pixelplay.data.plugin

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object PluginAudioSourceValidator {
    private const val MAX_URL_LENGTH = 4096
    private const val MAX_HEADERS = 32
    private const val MAX_HEADER_KEY_LENGTH = 128
    private const val MAX_HEADER_VALUE_LENGTH = 2048

    private val forbiddenHosts = setOf("localhost", "127.0.0.1", "0.0.0.0", "::1", "[::1]")
    private val forbiddenHeaderNames = setOf("host", "connection", "content-length", "transfer-encoding")

    fun validate(result: PluginAudioResolveResult, now: Long = System.currentTimeMillis()): PluginAudioResolveResult {
        validateUrl(result.url)
        validateHeaders(result.headers)
        val expireAt = result.expireAt
        if (expireAt != null && expireAt <= now) {
            throw PluginResolveException.InvalidAudioSource("插件返回的音源已过期")
        }
        return result
    }

    fun validateUrl(url: String) {
        if (url.isBlank() || url.length > MAX_URL_LENGTH) {
            throw PluginResolveException.InvalidAudioSource()
        }
        val httpUrl = url.toHttpUrlOrNull() ?: throw PluginResolveException.InvalidAudioSource()
        val scheme = httpUrl.scheme.lowercase()
        if (scheme != "http" && scheme != "https") {
            throw PluginResolveException.InvalidAudioSource()
        }
        val host = httpUrl.host.lowercase().removePrefix("[").removeSuffix("]")
        if (host in forbiddenHosts) throw PluginResolveException.InvalidAudioSource()
        if (host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".home") || host.endsWith(".internal")) {
            throw PluginResolveException.InvalidAudioSource()
        }
        if (isPrivateIpv4Literal(host) || isPrivateIpv6Literal(host)) {
            throw PluginResolveException.InvalidAudioSource()
        }
        if (httpUrl.username.isNotEmpty() || httpUrl.password.isNotEmpty()) {
            throw PluginResolveException.InvalidAudioSource()
        }
    }

    fun validateHeaders(headers: Map<String, String>) {
        if (headers.size > MAX_HEADERS) {
            throw PluginResolveException.InvalidAudioSource("插件返回的请求头过多")
        }
        headers.forEach { (key, value) ->
            if (key.isBlank() || key.length > MAX_HEADER_KEY_LENGTH || value.length > MAX_HEADER_VALUE_LENGTH) {
                throw PluginResolveException.InvalidAudioSource("插件返回了无效请求头")
            }
            if (key.any { it <= ' ' || it == ':' } || key.lowercase() in forbiddenHeaderNames) {
                throw PluginResolveException.InvalidAudioSource("插件返回了无效请求头")
            }
        }
    }

    private fun isPrivateIpv4Literal(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        val first = octets[0]
        val second = octets[1]
        return first == 0 ||
            first == 10 ||
            first == 127 ||
            (first == 169 && second == 254) ||
            (first == 172 && second in 16..31) ||
            (first == 192 && second == 168)
    }

    private fun isPrivateIpv6Literal(host: String): Boolean {
        if (!host.contains(':')) return false
        if (host == "::1" || host == "0:0:0:0:0:0:0:1") return true
        if (host.startsWith("fe8") || host.startsWith("fe9") ||
            host.startsWith("fea") || host.startsWith("feb")
        ) {
            return true
        }
        return host.startsWith("fc") || host.startsWith("fd")
    }
}
