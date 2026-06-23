package com.theveloper.pixelplay.data.plugin

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PluginMetadataParserTest {
    @Test
    fun parsesHeaderMetadata() {
        val script = """
            /*
             * @name Test Source
             * @description Resolves music URLs
             * @author Pixel
             * @version 1.2.3
             * @homepage https://example.com
             */
            lx.send('inited', {})
        """.trimIndent()

        val metadata = PluginMetadataParser.parse(script)

        assertThat(metadata.name).isEqualTo("Test Source")
        assertThat(metadata.description).isEqualTo("Resolves music URLs")
        assertThat(metadata.author).isEqualTo("Pixel")
        assertThat(metadata.version).isEqualTo("1.2.3")
        assertThat(metadata.homepage).isEqualTo("https://example.com")
    }

    @Test
    fun fallsBackToTimestampedName() {
        val metadata = PluginMetadataParser.parse("lx.send('inited', {})", now = 1_735_689_600_000L)

        assertThat(metadata.name).matches("""user_api_\d{8}_\d{6}""")
    }
}
