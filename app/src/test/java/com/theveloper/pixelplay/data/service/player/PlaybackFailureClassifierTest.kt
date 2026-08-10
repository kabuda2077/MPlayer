package com.theveloper.pixelplay.data.service.player

import androidx.media3.common.PlaybackException
import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.plugin.PluginResolveException
import org.junit.Test

class PlaybackFailureClassifierTest {
    @Test
    fun missingAudioSource_isRecognizedAndNeverRetried() {
        val kind = PlaybackFailureClassifier.classify(
            PlaybackException(
                "source unavailable",
                PluginResolveException.NoEnabledPlugin(),
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED
            )
        )

        assertThat(kind).isEqualTo(PlaybackFailureKind.SOURCE_NOT_CONFIGURED)
        assertThat(
            PlaybackFailureClassifier.shouldRetryResolvedOnlineSource(
                PlaybackFailureKind.SOURCE_NOT_CONFIGURED,
                alreadyRetried = false,
            )
        ).isFalse()
    }

    @Test
    fun missingAudioSourceDuringPreResolution_isRecognizedThroughCauseChain() {
        val error = IllegalStateException(
            "pre-resolution failed",
            PluginResolveException.NoEnabledPlugin()
        )

        assertThat(PlaybackFailureClassifier.classifySourcePreparationFailure(error))
            .isEqualTo(PlaybackFailureKind.SOURCE_NOT_CONFIGURED)
    }

    @Test
    fun networkFailure_isNotTreatedAsDecoderFailure() {
        val error = PlaybackException(
            "offline",
            null,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
        )

        assertThat(PlaybackFailureClassifier.classify(error)).isEqualTo(PlaybackFailureKind.NETWORK)
    }

    @Test
    fun decoderFailure_isNeverAutomaticallyRetried() {
        val error = PlaybackException(
            "unsupported",
            null,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
        )
        val kind = PlaybackFailureClassifier.classify(error)

        assertThat(kind).isEqualTo(PlaybackFailureKind.DECODER)
        assertThat(PlaybackFailureClassifier.shouldRetryResolvedOnlineSource(kind, alreadyRetried = false)).isFalse()
    }

    @Test
    fun recoveryIdentityRequiresMediaRequestAndOriginalSourceToMatch() {
        assertThat(
            PlaybackRecoveryIdentity.matches(
                "song", "request-new", "qqmusic://mid",
                "song", "request-new", "qqmusic://mid"
            )
        ).isTrue()
        assertThat(
            PlaybackRecoveryIdentity.matches(
                "song", "request-new", "qqmusic://mid",
                "song", "request-old", "qqmusic://mid"
            )
        ).isFalse()
        assertThat(
            PlaybackRecoveryIdentity.matches(
                "song", "request-new", "qqmusic://mid",
                "song", "request-new", "netease://123"
            )
        ).isFalse()
        assertThat(
            PlaybackRecoveryIdentity.matches(
                "song", "request-new", "qqmusic://mid",
                "other", "request-new", "qqmusic://mid"
            )
        ).isFalse()
    }
}
