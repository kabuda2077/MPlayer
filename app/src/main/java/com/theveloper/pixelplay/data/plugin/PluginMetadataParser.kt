package com.theveloper.pixelplay.data.plugin

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PluginMetadataParser {
    private val blockCommentRegex = Regex("""^\s*/\*([\s\S]*?)\*/""")
    private val fieldRegex = Regex("""^\s*\*?\s*@([A-Za-z][A-Za-z0-9_-]*)\s+(.*)\s*$""")

    fun parse(script: String, now: Long = System.currentTimeMillis()): ImportedPluginMetadata {
        val fields = linkedMapOf<String, String>()
        val block = blockCommentRegex.find(script)?.groupValues?.getOrNull(1).orEmpty()
        block.lineSequence().forEach { line ->
            val match = fieldRegex.matchEntire(line)
            if (match != null) {
                fields[match.groupValues[1].lowercase()] = match.groupValues[2].trim()
            }
        }
        val fallbackName = "user_api_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))
        return ImportedPluginMetadata(
            name = fields["name"].orEmpty().ifBlank { fallbackName },
            description = fields["description"].orEmpty(),
            author = fields["author"].orEmpty(),
            version = fields["version"].orEmpty(),
            homepage = fields["homepage"].orEmpty()
        )
    }
}
