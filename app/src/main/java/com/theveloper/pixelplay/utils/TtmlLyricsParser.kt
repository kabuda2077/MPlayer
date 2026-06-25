package com.theveloper.pixelplay.utils

import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.roundToInt
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.EntityResolver
import org.xml.sax.InputSource

internal object TtmlLyricsParser {
    private const val MAX_TTML_PARAGRAPHS = 5_000
    private const val TTML_METADATA_NS = "http://www.w3.org/ns/ttml#metadata"

    fun parseToEnhancedLrc(ttmlText: String): String? {
        return runCatching {
            val normalizedTtml = normalizeTtmlDocument(ttmlText)
            if (normalizedTtml.isBlank()) {
                return null
            }

            val builder = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isXIncludeAware = false
                setExpandEntityReferences(false)
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
                runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
            }.newDocumentBuilder().apply {
                setEntityResolver(EntityResolver { _, _ -> InputSource(StringReader("")) })
            }

            val document = builder.parse(InputSource(StringReader(normalizedTtml)))
            val paragraphNodes = document.getElementsByTagNameNS("*", "p")
            if (paragraphNodes.length == 0 || paragraphNodes.length > MAX_TTML_PARAGRAPHS) {
                return null
            }

            buildList<Pair<Int, String>> {
                for (index in 0 until paragraphNodes.length) {
                    val paragraph = paragraphNodes.item(index) as? Element ?: continue
                    val beginMs = resolveParagraphStartMs(paragraph) ?: continue
                    val serializedBody = normalizeParagraphBody(serializePrimaryChildren(paragraph))
                    if (serializedBody.isBlank()) continue
                    add(beginMs to "[${formatLrcTimestamp(beginMs)}]$serializedBody")
                    collectSupplementalLines(paragraph, role = "x-translation").forEach { translation ->
                        add(beginMs to "[${formatLrcTimestamp(beginMs)}]$translation")
                    }
                }
            }.sortedBy { it.first }
                .joinToString("\n") { it.second }
                .takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun normalizeTtmlDocument(raw: String): String {
        val withoutLeadingNoise = raw.trimStart { char ->
            char.isWhitespace() ||
                char == '\uFEFF' ||
                Character.getType(char).toByte() == Character.FORMAT
        }

        return if (withoutLeadingNoise.startsWith("<?xml", ignoreCase = true)) {
            withoutLeadingNoise.substringAfter("?>", missingDelimiterValue = withoutLeadingNoise).trimStart()
        } else {
            withoutLeadingNoise
        }
    }

    private fun resolveParagraphStartMs(paragraph: Element): Int? {
        parseTimeExpression(paragraph.getAttribute("begin"))?.let { return it }

        val spans = paragraph.getElementsByTagNameNS("*", "span")
        for (index in 0 until spans.length) {
            val span = spans.item(index) as? Element ?: continue
            parseTimeExpression(span.getAttribute("begin"))?.let { return it }
        }
        return null
    }

    private fun serializeChildren(node: Node): String {
        val builder = StringBuilder()
        var child = node.firstChild
        while (child != null) {
            builder.append(serializeNode(child))
            child = child.nextSibling
        }
        return builder.toString()
    }

    private fun serializePrimaryChildren(node: Node): String {
        val builder = StringBuilder()
        var child = node.firstChild
        while (child != null) {
            if (child !is Element || !isSupplementalSpan(child)) {
                builder.append(serializePrimaryNode(child))
            }
            child = child.nextSibling
        }
        return builder.toString()
    }

    private fun serializeNode(node: Node): String {
        return when (node.nodeType) {
            Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> sanitizeTextFragment(node.nodeValue.orEmpty())
            Node.ELEMENT_NODE -> serializeElement(node as Element)
            else -> ""
        }
    }

    private fun serializePrimaryNode(node: Node): String {
        return when (node.nodeType) {
            Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> sanitizeTextFragment(node.nodeValue.orEmpty())
            Node.ELEMENT_NODE -> {
                val element = node as Element
                if (isSupplementalSpan(element)) "" else serializePrimaryElement(element)
            }
            else -> ""
        }
    }

    private fun serializeElement(element: Element): String {
        return when (localNameOf(element)) {
            "br" -> "\n"
            "span" -> {
                val content = normalizeInlineText(serializeChildren(element))
                if (content.isBlank()) {
                    ""
                } else {
                    val beginMs = parseTimeExpression(element.getAttribute("begin"))
                    if (beginMs != null) "<${formatLrcTimestamp(beginMs)}>$content" else content
                }
            }
            else -> serializeChildren(element)
        }
    }

    private fun serializePrimaryElement(element: Element): String {
        return when (localNameOf(element)) {
            "br" -> "\n"
            "span" -> {
                val content = normalizeInlineText(serializePrimaryChildren(element))
                if (content.isBlank()) {
                    ""
                } else {
                    val beginMs = parseTimeExpression(element.getAttribute("begin"))
                    if (beginMs != null) "<${formatLrcTimestamp(beginMs)}>$content" else content
                }
            }
            else -> serializePrimaryChildren(element)
        }
    }

    private fun collectSupplementalLines(paragraph: Element, role: String): List<String> {
        val spans = paragraph.getElementsByTagNameNS("*", "span")
        return buildList {
            for (index in 0 until spans.length) {
                val span = spans.item(index) as? Element ?: continue
                if (spanRole(span) != role) continue
                val text = normalizeParagraphBody(extractPlainText(span))
                if (text.isNotBlank()) add(text)
            }
        }
    }

    private fun extractPlainText(node: Node): String {
        val builder = StringBuilder()
        var child = node.firstChild
        while (child != null) {
            when (child.nodeType) {
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> builder.append(sanitizeTextFragment(child.nodeValue.orEmpty()))
                Node.ELEMENT_NODE -> {
                    val element = child as Element
                    if (localNameOf(element) == "br") builder.append('\n') else builder.append(extractPlainText(element))
                }
            }
            child = child.nextSibling
        }
        return builder.toString()
    }

    private fun isSupplementalSpan(element: Element): Boolean =
        localNameOf(element) == "span" && spanRole(element).startsWith("x-")

    private fun spanRole(element: Element): String =
        element.getAttributeNS(TTML_METADATA_NS, "role")
            .ifBlank { element.getAttribute("ttm:role") }
            .trim()

    private fun localNameOf(element: Element): String {
        return element.localName?.lowercase()
            ?: element.tagName.substringAfterLast(':').lowercase()
    }

    private fun sanitizeTextFragment(raw: String): String {
        if (raw.isEmpty()) return raw

        val cleaned = raw
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .filterNot { char ->
                Character.getType(char).toByte() == Character.FORMAT ||
                    (Character.isISOControl(char) && char != '\n' && char != '\t')
            }

        if (cleaned.isEmpty()) return ""
        if (cleaned.all { it.isWhitespace() }) {
            return if (cleaned.any { it == '\n' || it == '\t' }) "" else " "
        }

        return cleaned.replace(Regex("\\s+"), " ")
    }

    private fun normalizeInlineText(value: String): String {
        return value
            .replace(Regex("[ ]{2,}"), " ")
            .replace(Regex(" *\n *"), "\n")
            .trim('\n')
    }

    private fun normalizeParagraphBody(value: String): String {
        return value
            .lineSequence()
            .map { line -> line.replace(Regex("[ ]{2,}"), " ").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .trim()
    }

    private fun parseTimeExpression(value: String?): Int? {
        val normalized = value?.trim().orEmpty()
        if (normalized.isEmpty()) return null

        if (normalized.endsWith("s", ignoreCase = true)) {
            val seconds = normalized.removeSuffix("s").toDoubleOrNull() ?: return null
            return (seconds * 1000).roundToInt()
        }

        normalized.toDoubleOrNull()?.let { seconds ->
            return (seconds * 1000).roundToInt()
        }

        val parts = normalized.split(':')
        return when (parts.size) {
            2 -> {
                val minutes = parts[0].toLongOrNull() ?: return null
                val (seconds, millis) = parseSecondsWithFraction(parts[1]) ?: return null
                (minutes * 60_000L + seconds * 1_000L + millis).toInt()
            }
            3 -> {
                val hours = parts[0].toLongOrNull() ?: return null
                val minutes = parts[1].toLongOrNull() ?: return null
                val (seconds, millis) = parseSecondsWithFraction(parts[2]) ?: return null
                (hours * 3_600_000L + minutes * 60_000L + seconds * 1_000L + millis).toInt()
            }
            else -> null
        }
    }

    private fun parseSecondsWithFraction(value: String): Pair<Long, Long>? {
        val parts = value.split('.', limit = 2)
        val seconds = parts[0].toLongOrNull() ?: return null
        val fraction = parts.getOrNull(1).orEmpty()
        if (fraction.isEmpty()) return seconds to 0L

        val digits = fraction.take(3).padEnd(3, '0')
        val millis = digits.toLongOrNull() ?: return null
        return seconds to millis
    }

    private fun formatLrcTimestamp(timeMs: Int): String {
        val totalSeconds = timeMs / 1_000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val hundredths = (timeMs % 1_000) / 10
        return String.format("%02d:%02d.%02d", minutes, seconds, hundredths)
    }
}
