package com.theveloper.pixelplay.data.repository

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

internal suspend fun <T> selectFirstAvailableByPriority(
    fetchers: List<suspend () -> T?>
): T? = coroutineScope {
    val requests = fetchers.map { fetch -> async { fetch() } }
    try {
        requests.forEachIndexed { index, request ->
            request.await()?.let { candidate ->
                requests.drop(index + 1).forEach { it.cancel() }
                return@coroutineScope candidate
            }
        }
        null
    } finally {
        requests.forEach { request ->
            if (request.isActive) request.cancel()
        }
    }
}
