package com.theveloper.pixelplay.data.plugin

import okhttp3.Request

object PluginStreamRequestHeaders {
    const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10; Pixel 3) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/79.0.3945.79 Mobile Safari/537.36"

    fun applyTo(builder: Request.Builder, headers: Map<String, String>): Request.Builder {
        headers.forEach { (name, value) -> builder.header(name, value) }
        if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            builder.header("User-Agent", DEFAULT_USER_AGENT)
        }
        return builder
    }
}
