package com.theveloper.pixelplay.data.plugin

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.database.PluginDao
import com.theveloper.pixelplay.data.database.PluginEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class PluginRepositoryUpdateTest {
    private val dao = mockk<PluginDao>(relaxed = true)
    private val runtime = mockk<PluginRuntime>(relaxed = true)
    private val downloader = mockk<PluginScriptDownloader>()
    private lateinit var repository: PluginRepository
    private val oldScript = "/**\n * @name Test\n * @version 1\n */\nlx.on('request', () => {});"
    private val newScript = oldScript.replace("@version 1", "@version 2")
    private val plugin = PluginEntity("id", "Test", "", "", "1", "", true, oldScript, 1, 2, "https://example.com/source.js")

    @Before
    fun setUp() {
        every { runtime.state } returns MutableStateFlow(PluginRuntimeState())
        coEvery { dao.getPlugin("id") } returns plugin
        repository = PluginRepository(dao, runtime, downloader)
    }

    @Test
    fun onlineImportSavesSourceWithoutExecutingScript() = runTest {
        coEvery { downloader.download(any()) } returns newScript
        val imported = repository.importFromUrl(" https://example.com/source.js ")
        assertThat(imported.sourceUrl).isEqualTo(plugin.sourceUrl)
        assertThat(imported.version).isEqualTo("2")
        assertThat(imported.enabled).isFalse()
        coVerify(exactly = 0) { runtime.load(any()) }
    }

    @Test
    fun checkingUpdateDoesNotReplaceOrExecuteScript() = runTest {
        coEvery { downloader.download(any()) } returns newScript
        val update = repository.checkUpdate("id")!!
        assertThat(update.metadata.version).isEqualTo("2")
        coVerify(exactly = 0) { runtime.load(any()) }
        coVerify(exactly = 0) { dao.upsert(match { it.script != oldScript }) }
    }

    @Test
    fun unchangedScriptReturnsNoUpdate() = runTest {
        coEvery { downloader.download(any()) } returns oldScript
        assertThat(repository.checkUpdate("id")).isNull()
    }

    @Test
    fun confirmedUpdatePreservesIdentityAndEnabledState() = runTest {
        val written = slot<PluginEntity>()
        coEvery { dao.upsert(capture(written)) } returns Unit
        repository.applyUpdate(candidate())
        assertThat(written.captured.id).isEqualTo(plugin.id)
        assertThat(written.captured.createdAt).isEqualTo(plugin.createdAt)
        assertThat(written.captured.enabled).isTrue()
        assertThat(written.captured.script).isEqualTo(newScript)
        coVerifyOrder { runtime.load(match { it.script == newScript }); dao.upsert(any()) }
    }

    @Test
    fun failedActiveUpdateRestoresOldRuntimeWithoutReplacingStoredScript() = runTest {
        coEvery { runtime.load(match { it.script == newScript }) } throws IllegalStateException("bad script")
        val failure = runCatching { repository.applyUpdate(candidate()) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        coVerifyOrder {
            runtime.load(match { it.script == newScript })
            runtime.load(plugin)
        }
        coVerify(exactly = 0) { dao.upsert(any()) }
    }

    @Test
    fun staleCandidateCannotOverwritePluginChangedSinceCheck() = runTest {
        coEvery { dao.getPlugin("id") } returns plugin.copy(script = "changed")
        assertThat(runCatching { repository.applyUpdate(candidate()) }.isFailure).isTrue()
        coVerify(exactly = 0) { runtime.load(any()) }
        coVerify(exactly = 0) { dao.upsert(any()) }
    }

    private fun candidate() = PluginUpdateCandidate(plugin.id, oldScript, plugin.sourceUrl!!, newScript, PluginMetadataParser.parse(newScript))
}
