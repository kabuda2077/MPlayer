package com.theveloper.pixelplay.data.service.player

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import com.theveloper.pixelplay.data.plugin.PluginResolveException
import com.theveloper.pixelplay.utils.MediaItemBuilder
import com.theveloper.pixelplay.utils.PlaybackTrace

enum class PlaybackFailureKind {
    SOURCE_NOT_CONFIGURED,
    SOURCE_EXPIRED,
    NETWORK,
    LOCAL_SOURCE,
    DECODER,
    FATAL
}

object PlaybackFailureClassifier {
    private val staleHttpCodes = setOf(401, 403, 404, 410)

    fun classify(error: PlaybackException): PlaybackFailureKind {
        classifySourcePreparationFailure(error)?.let { return it }
        val response = generateSequence<Throwable>(error) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .firstOrNull()
        classifyHttpResponse(response?.responseCode)?.let { return it }

        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> PlaybackFailureKind.NETWORK

            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> PlaybackFailureKind.LOCAL_SOURCE

            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> PlaybackFailureKind.DECODER

            else -> PlaybackFailureKind.FATAL
        }
    }

    fun classifySourcePreparationFailure(error: Throwable): PlaybackFailureKind? {
        return if (generateSequence(error) { it.cause }
                .any { it is PluginResolveException.NoEnabledPlugin }
        ) {
            PlaybackFailureKind.SOURCE_NOT_CONFIGURED
        } else {
            null
        }
    }

    fun shouldRetryResolvedOnlineSource(
        kind: PlaybackFailureKind,
        alreadyRetried: Boolean
    ): Boolean = !alreadyRetried && kind == PlaybackFailureKind.SOURCE_EXPIRED

    internal fun classifyHttpResponse(responseCode: Int?): PlaybackFailureKind? {
        if (responseCode in staleHttpCodes) {
            return PlaybackFailureKind.SOURCE_EXPIRED
        }
        return null
    }
}

internal object PlaybackRecoveryIdentity {
    fun matches(
        currentItem: MediaItem?,
        expectedMediaId: String,
        expectedRequestId: String?,
        expectedSourceUri: String
    ): Boolean {
        return matches(
            currentMediaId = currentItem?.mediaId,
            currentRequestId = PlaybackTrace.requestId(currentItem),
            currentSourceUri = currentItem?.mediaMetadata?.extras
                ?.getString(MediaItemBuilder.EXTERNAL_EXTRA_CONTENT_URI),
            expectedMediaId = expectedMediaId,
            expectedRequestId = expectedRequestId,
            expectedSourceUri = expectedSourceUri
        )
    }

    fun matches(
        currentMediaId: String?,
        currentRequestId: String?,
        currentSourceUri: String?,
        expectedMediaId: String,
        expectedRequestId: String?,
        expectedSourceUri: String
    ): Boolean {
        if (currentMediaId != expectedMediaId) return false
        if (expectedRequestId != null && currentRequestId != expectedRequestId) return false
        return currentSourceUri == expectedSourceUri
    }
}
