package com.theveloper.pixelplay.data.service

import android.content.ComponentName
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.TimeUnit
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MusicServiceStartupTest {

    @Test
    fun mediaControllerConnectsToColdStartedMusicService() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val sessionToken = SessionToken(
            context,
            ComponentName(context, MusicService::class.java),
        )
        val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        val controller = controllerFuture.get(15, TimeUnit.SECONDS)

        try {
            assertThat(controller.isConnected).isTrue()
        } finally {
            instrumentation.runOnMainSync(controller::release)
        }
    }
}
