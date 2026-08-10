package com.theveloper.pixelplay.data.backup.module

import com.google.gson.GsonBuilder
import com.theveloper.pixelplay.data.database.LyricsDao
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class LyricsModuleHandlerTest {

    private val lyricsDao: LyricsDao = mockk(relaxed = true)
    private val handler = LyricsModuleHandler(
        lyricsDao = lyricsDao,
        gson = GsonBuilder().serializeNulls().create()
    )

    @Test
    fun `restore normalizes legacy song ids and preserves source-aware keys`() = runTest {
        val payload = """
            [
              {"songId":12,"content":"local","isSynced":false,"updatedAt":1},
              {"mediaKey":"uri:qqmusic://mid-1","songId":null,"content":"old","isSynced":true,"updatedAt":2},
              {"mediaKey":"uri:qqmusic://mid-1","songId":null,"content":"new","isSynced":true,"updatedAt":3}
            ]
        """.trimIndent()

        handler.restore(payload)

        coVerify(exactly = 1) {
            lyricsDao.replaceAll(match { restored ->
                restored.size == 2 &&
                    restored.any { it.mediaKey == "library:12" && it.songId == 12L } &&
                    restored.any {
                        it.mediaKey == "uri:qqmusic://mid-1" &&
                            it.songId == null &&
                            it.content == "new"
                    }
            })
        }
    }
}
