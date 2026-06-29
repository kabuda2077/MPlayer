package com.theveloper.pixelplay.data.download

import java.io.File
import java.io.FileInputStream

object AudioFormatSniffer {
    fun detectMimeType(file: File): String? {
        if (!file.exists() || file.length() < 4L) return null
        return runCatching {
            val header = ByteArray(64)
            val read = FileInputStream(file).use { it.read(header) }
            if (read <= 0) return@runCatching null
            detectMimeType(header, read)
        }.getOrNull()
    }

    private fun detectMimeType(bytes: ByteArray, length: Int): String? {
        fun ascii(offset: Int, value: String): Boolean {
            if (offset + value.length > length) return false
            return value.indices.all { index -> bytes[offset + index] == value[index].code.toByte() }
        }

        return when {
            ascii(0, "fLaC") -> "audio/flac"
            ascii(0, "ID3") -> "audio/mpeg"
            isMpegFrame(bytes, length) -> "audio/mpeg"
            ascii(0, "OggS") && containsAscii(bytes, length, "OpusHead") -> "audio/opus"
            ascii(0, "OggS") -> "audio/ogg"
            ascii(0, "RIFF") && ascii(8, "WAVE") -> "audio/wav"
            ascii(4, "ftyp") -> "audio/mp4"
            else -> null
        }
    }

    private fun isMpegFrame(bytes: ByteArray, length: Int): Boolean {
        if (length < 2) return false
        val first = bytes[0].toInt() and 0xFF
        val second = bytes[1].toInt() and 0xFF
        return first == 0xFF && (second and 0xE0) == 0xE0
    }

    private fun containsAscii(bytes: ByteArray, length: Int, value: String): Boolean {
        if (value.isEmpty() || value.length > length) return false
        for (offset in 0..(length - value.length)) {
            var match = true
            for (index in value.indices) {
                if (bytes[offset + index] != value[index].code.toByte()) {
                    match = false
                    break
                }
            }
            if (match) return true
        }
        return false
    }
}
