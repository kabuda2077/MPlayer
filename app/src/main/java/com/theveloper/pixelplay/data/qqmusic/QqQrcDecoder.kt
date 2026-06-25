package com.theveloper.pixelplay.data.qqmusic

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

internal object QqQrcDecoder {
    private val qrcKey = "!@#)(*$%123ZXC!@!@#)(NHL".toByteArray(Charsets.UTF_8)

    fun decrypt(encryptedHex: String): String {
        require(encryptedHex.isNotBlank()) { "Empty QRC payload" }

        val encrypted = encryptedHex.chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()

        val cipher = Cipher.getInstance("DESede/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(qrcKey, "DESede"))
        val decrypted = cipher.doFinal(encrypted)

        decompress(decrypted)?.let { return it }

        val fallback = decrypted.toString(Charsets.UTF_8).trim('\u0000')
        if (fallback.contains("[") || fallback.contains("<")) return fallback
        error("Unable to decompress QQ lyric payload")
    }

    private fun decompress(data: ByteArray): String? {
        val candidates = listOf(
            data,
            data.dropWhile { it.toInt() == 0 }.toByteArray()
        ).distinctBy { it.size }

        for (candidate in candidates) {
            inflate(candidate, nowrap = false)?.let { return it }
            inflate(candidate, nowrap = true)?.let { return it }
        }
        return null
    }

    private fun inflate(data: ByteArray, nowrap: Boolean): String? {
        val inflater = Inflater(nowrap)
        return try {
            inflater.setInput(data)
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count <= 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) return null
                } else {
                    output.write(buffer, 0, count)
                }
            }
            output.toString(Charsets.UTF_8.name())
        } catch (_: Exception) {
            null
        } finally {
            inflater.end()
        }
    }
}
