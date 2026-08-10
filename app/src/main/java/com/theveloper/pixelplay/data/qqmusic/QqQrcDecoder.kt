package com.theveloper.pixelplay.data.qqmusic

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater

internal object QqQrcDecoder {
    private const val ENCRYPT = 1
    private const val DECRYPT = 0

    private val qrcKey = "!@#)(*$%123ZXC!@!@#)(NHL".toByteArray(Charsets.UTF_8)

    fun decrypt(encryptedHex: String): String {
        require(encryptedHex.isNotBlank()) { "Empty QRC payload" }

        val encrypted = encryptedHexToBytes(encryptedHex)
        val decrypted = qrcDecrypt(encrypted, qrcKey)

        decompress(decrypted)?.let { return it }

        val fallback = decrypted.toString(Charsets.UTF_8).trim('\u0000')
        if (fallback.contains("[") || fallback.contains("<")) return fallback
        error("Unable to decompress QQ lyric payload")
    }

    private fun encryptedHexToBytes(value: String): ByteArray {
        val normalized = value.trim()
        require(normalized.length % 2 == 0) { "Invalid QRC hex payload length" }
        return ByteArray(normalized.length / 2) { index ->
            normalized.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun qrcDecrypt(encryptedData: ByteArray, key: ByteArray): ByteArray {
        val schedule = tripleDesKeySetup(key, DECRYPT)
        val output = ByteArrayOutputStream(encryptedData.size)

        var offset = 0
        while (offset < encryptedData.size) {
            val block = ByteArray(8)
            val length = minOf(8, encryptedData.size - offset)
            encryptedData.copyInto(block, destinationOffset = 0, startIndex = offset, endIndex = offset + length)
            output.write(tripleDesCrypt(block, schedule))
            offset += 8
        }

        return output.toByteArray()
    }

    private fun tripleDesKeySetup(key: ByteArray, mode: Int): Array<Array<IntArray>> {
        return if (mode == ENCRYPT) {
            arrayOf(
                keySchedule(key.copyOfRange(0, 8), ENCRYPT),
                keySchedule(key.copyOfRange(8, 16), DECRYPT),
                keySchedule(key.copyOfRange(16, 24), ENCRYPT)
            )
        } else {
            arrayOf(
                keySchedule(key.copyOfRange(16, 24), DECRYPT),
                keySchedule(key.copyOfRange(8, 16), ENCRYPT),
                keySchedule(key.copyOfRange(0, 8), DECRYPT)
            )
        }
    }

    private fun tripleDesCrypt(data: ByteArray, key: Array<Array<IntArray>>): ByteArray {
        var result = data
        for (index in 0 until 3) {
            result = crypt(result, key[index])
        }
        return result
    }

    private fun crypt(inputData: ByteArray, key: Array<IntArray>): ByteArray {
        val initial = initialPermutation(inputData)
        var s0 = initial[0]
        var s1 = initial[1]

        for (index in 0 until 15) {
            val previousS1 = s1
            s1 = f(s1, key[index]) xor s0
            s0 = previousS1
        }
        s0 = f(s1, key[15]) xor s0

        return inversePermutation(s0, s1)
    }

    private fun keySchedule(key: ByteArray, mode: Int): Array<IntArray> {
        val schedule = Array(16) { IntArray(6) }
        var c = 0
        var d = 0

        for (index in 0 until 28) {
            c = c or bitnum(key, KEY_PERM_C[index], 31 - index)
            d = d or bitnum(key, KEY_PERM_D[index], 31 - index)
        }

        for (round in 0 until 16) {
            val shift = KEY_ROUND_SHIFT[round]
            c = ((c shl shift) or (c ushr (28 - shift))) and 0xfffffff0.toInt()
            d = ((d shl shift) or (d ushr (28 - shift))) and 0xfffffff0.toInt()

            val target = if (mode == DECRYPT) 15 - round else round
            schedule[target].fill(0)

            for (index in 0 until 24) {
                schedule[target][index / 8] =
                    schedule[target][index / 8] or bitnumIntr(c, KEY_COMPRESSION[index], 7 - (index % 8))
            }
            for (index in 24 until 48) {
                schedule[target][index / 8] =
                    schedule[target][index / 8] or bitnumIntr(d, KEY_COMPRESSION[index] - 27, 7 - (index % 8))
            }
        }

        return schedule
    }

    private fun initialPermutation(inputData: ByteArray): IntArray {
        val s0 = (
            bitnum(inputData, 57, 31) or bitnum(inputData, 49, 30) or bitnum(inputData, 41, 29) or
                bitnum(inputData, 33, 28) or bitnum(inputData, 25, 27) or bitnum(inputData, 17, 26) or
                bitnum(inputData, 9, 25) or bitnum(inputData, 1, 24) or bitnum(inputData, 59, 23) or
                bitnum(inputData, 51, 22) or bitnum(inputData, 43, 21) or bitnum(inputData, 35, 20) or
                bitnum(inputData, 27, 19) or bitnum(inputData, 19, 18) or bitnum(inputData, 11, 17) or
                bitnum(inputData, 3, 16) or bitnum(inputData, 61, 15) or bitnum(inputData, 53, 14) or
                bitnum(inputData, 45, 13) or bitnum(inputData, 37, 12) or bitnum(inputData, 29, 11) or
                bitnum(inputData, 21, 10) or bitnum(inputData, 13, 9) or bitnum(inputData, 5, 8) or
                bitnum(inputData, 63, 7) or bitnum(inputData, 55, 6) or bitnum(inputData, 47, 5) or
                bitnum(inputData, 39, 4) or bitnum(inputData, 31, 3) or bitnum(inputData, 23, 2) or
                bitnum(inputData, 15, 1) or bitnum(inputData, 7, 0)
            )

        val s1 = (
            bitnum(inputData, 56, 31) or bitnum(inputData, 48, 30) or bitnum(inputData, 40, 29) or
                bitnum(inputData, 32, 28) or bitnum(inputData, 24, 27) or bitnum(inputData, 16, 26) or
                bitnum(inputData, 8, 25) or bitnum(inputData, 0, 24) or bitnum(inputData, 58, 23) or
                bitnum(inputData, 50, 22) or bitnum(inputData, 42, 21) or bitnum(inputData, 34, 20) or
                bitnum(inputData, 26, 19) or bitnum(inputData, 18, 18) or bitnum(inputData, 10, 17) or
                bitnum(inputData, 2, 16) or bitnum(inputData, 60, 15) or bitnum(inputData, 52, 14) or
                bitnum(inputData, 44, 13) or bitnum(inputData, 36, 12) or bitnum(inputData, 28, 11) or
                bitnum(inputData, 20, 10) or bitnum(inputData, 12, 9) or bitnum(inputData, 4, 8) or
                bitnum(inputData, 62, 7) or bitnum(inputData, 54, 6) or bitnum(inputData, 46, 5) or
                bitnum(inputData, 38, 4) or bitnum(inputData, 30, 3) or bitnum(inputData, 22, 2) or
                bitnum(inputData, 14, 1) or bitnum(inputData, 6, 0)
            )

        return intArrayOf(s0, s1)
    }

    private fun inversePermutation(s0: Int, s1: Int): ByteArray {
        val data = ByteArray(8)
        data[3] = (bitnumIntr(s1, 7, 7) or bitnumIntr(s0, 7, 6) or bitnumIntr(s1, 15, 5) or
            bitnumIntr(s0, 15, 4) or bitnumIntr(s1, 23, 3) or bitnumIntr(s0, 23, 2) or
            bitnumIntr(s1, 31, 1) or bitnumIntr(s0, 31, 0)).toByte()
        data[2] = (bitnumIntr(s1, 6, 7) or bitnumIntr(s0, 6, 6) or bitnumIntr(s1, 14, 5) or
            bitnumIntr(s0, 14, 4) or bitnumIntr(s1, 22, 3) or bitnumIntr(s0, 22, 2) or
            bitnumIntr(s1, 30, 1) or bitnumIntr(s0, 30, 0)).toByte()
        data[1] = (bitnumIntr(s1, 5, 7) or bitnumIntr(s0, 5, 6) or bitnumIntr(s1, 13, 5) or
            bitnumIntr(s0, 13, 4) or bitnumIntr(s1, 21, 3) or bitnumIntr(s0, 21, 2) or
            bitnumIntr(s1, 29, 1) or bitnumIntr(s0, 29, 0)).toByte()
        data[0] = (bitnumIntr(s1, 4, 7) or bitnumIntr(s0, 4, 6) or bitnumIntr(s1, 12, 5) or
            bitnumIntr(s0, 12, 4) or bitnumIntr(s1, 20, 3) or bitnumIntr(s0, 20, 2) or
            bitnumIntr(s1, 28, 1) or bitnumIntr(s0, 28, 0)).toByte()
        data[7] = (bitnumIntr(s1, 3, 7) or bitnumIntr(s0, 3, 6) or bitnumIntr(s1, 11, 5) or
            bitnumIntr(s0, 11, 4) or bitnumIntr(s1, 19, 3) or bitnumIntr(s0, 19, 2) or
            bitnumIntr(s1, 27, 1) or bitnumIntr(s0, 27, 0)).toByte()
        data[6] = (bitnumIntr(s1, 2, 7) or bitnumIntr(s0, 2, 6) or bitnumIntr(s1, 10, 5) or
            bitnumIntr(s0, 10, 4) or bitnumIntr(s1, 18, 3) or bitnumIntr(s0, 18, 2) or
            bitnumIntr(s1, 26, 1) or bitnumIntr(s0, 26, 0)).toByte()
        data[5] = (bitnumIntr(s1, 1, 7) or bitnumIntr(s0, 1, 6) or bitnumIntr(s1, 9, 5) or
            bitnumIntr(s0, 9, 4) or bitnumIntr(s1, 17, 3) or bitnumIntr(s0, 17, 2) or
            bitnumIntr(s1, 25, 1) or bitnumIntr(s0, 25, 0)).toByte()
        data[4] = (bitnumIntr(s1, 0, 7) or bitnumIntr(s0, 0, 6) or bitnumIntr(s1, 8, 5) or
            bitnumIntr(s0, 8, 4) or bitnumIntr(s1, 16, 3) or bitnumIntr(s0, 16, 2) or
            bitnumIntr(s1, 24, 1) or bitnumIntr(s0, 24, 0)).toByte()
        return data
    }

    private fun f(state: Int, key: IntArray): Int {
        val t1 = (
            bitnumIntl(state, 31, 0) or ((state and 0xf0000000.toInt()) ushr 1) or bitnumIntl(state, 4, 5) or
                bitnumIntl(state, 3, 6) or ((state and 0x0f000000) ushr 3) or bitnumIntl(state, 8, 11) or
                bitnumIntl(state, 7, 12) or ((state and 0x00f00000) ushr 5) or bitnumIntl(state, 12, 17) or
                bitnumIntl(state, 11, 18) or ((state and 0x000f0000) ushr 7) or bitnumIntl(state, 16, 23)
            )
        val t2 = (
            bitnumIntl(state, 15, 0) or ((state and 0x0000f000) shl 15) or bitnumIntl(state, 20, 5) or
                bitnumIntl(state, 19, 6) or ((state and 0x00000f00) shl 13) or bitnumIntl(state, 24, 11) or
                bitnumIntl(state, 23, 12) or ((state and 0x000000f0) shl 11) or bitnumIntl(state, 28, 17) or
                bitnumIntl(state, 27, 18) or ((state and 0x0000000f) shl 9) or bitnumIntl(state, 0, 23)
            )

        val lrgState = intArrayOf(
            ((t1 ushr 24) and 0xff) xor key[0],
            ((t1 ushr 16) and 0xff) xor key[1],
            ((t1 ushr 8) and 0xff) xor key[2],
            ((t2 ushr 24) and 0xff) xor key[3],
            ((t2 ushr 16) and 0xff) xor key[4],
            ((t2 ushr 8) and 0xff) xor key[5]
        )

        val mixed = (
            (SBOX[0][sboxBit(lrgState[0] ushr 2)] shl 28) or
                (SBOX[1][sboxBit(((lrgState[0] and 0x03) shl 4) or (lrgState[1] ushr 4))] shl 24) or
                (SBOX[2][sboxBit(((lrgState[1] and 0x0f) shl 2) or (lrgState[2] ushr 6))] shl 20) or
                (SBOX[3][sboxBit(lrgState[2] and 0x3f)] shl 16) or
                (SBOX[4][sboxBit(lrgState[3] ushr 2)] shl 12) or
                (SBOX[5][sboxBit(((lrgState[3] and 0x03) shl 4) or (lrgState[4] ushr 4))] shl 8) or
                (SBOX[6][sboxBit(((lrgState[4] and 0x0f) shl 2) or (lrgState[5] ushr 6))] shl 4) or
                SBOX[7][sboxBit(lrgState[5] and 0x3f)]
            )

        return (
            bitnumIntl(mixed, 15, 0) or bitnumIntl(mixed, 6, 1) or bitnumIntl(mixed, 19, 2) or
                bitnumIntl(mixed, 20, 3) or bitnumIntl(mixed, 28, 4) or bitnumIntl(mixed, 11, 5) or
                bitnumIntl(mixed, 27, 6) or bitnumIntl(mixed, 16, 7) or bitnumIntl(mixed, 0, 8) or
                bitnumIntl(mixed, 14, 9) or bitnumIntl(mixed, 22, 10) or bitnumIntl(mixed, 25, 11) or
                bitnumIntl(mixed, 4, 12) or bitnumIntl(mixed, 17, 13) or bitnumIntl(mixed, 30, 14) or
                bitnumIntl(mixed, 9, 15) or bitnumIntl(mixed, 1, 16) or bitnumIntl(mixed, 7, 17) or
                bitnumIntl(mixed, 23, 18) or bitnumIntl(mixed, 13, 19) or bitnumIntl(mixed, 31, 20) or
                bitnumIntl(mixed, 26, 21) or bitnumIntl(mixed, 2, 22) or bitnumIntl(mixed, 8, 23) or
                bitnumIntl(mixed, 18, 24) or bitnumIntl(mixed, 12, 25) or bitnumIntl(mixed, 29, 26) or
                bitnumIntl(mixed, 5, 27) or bitnumIntl(mixed, 21, 28) or bitnumIntl(mixed, 10, 29) or
                bitnumIntl(mixed, 3, 30) or bitnumIntl(mixed, 24, 31)
            )
    }

    private fun bitnum(data: ByteArray, bit: Int, shift: Int): Int {
        val byteIndex = (bit / 32) * 4 + 3 - ((bit % 32) / 8)
        return (((data[byteIndex].toInt() and 0xff) ushr (7 - (bit % 8))) and 1) shl shift
    }

    private fun bitnumIntr(value: Int, bit: Int, shift: Int): Int =
        ((value ushr (31 - bit)) and 1) shl shift

    private fun bitnumIntl(value: Int, bit: Int, shift: Int): Int =
        ((value shl bit) and 0x80000000.toInt()) ushr shift

    private fun sboxBit(value: Int): Int =
        (value and 32) or ((value and 31) ushr 1) or ((value and 1) shl 4)

    private fun decompress(data: ByteArray): String? {
        val candidates = listOf(
            data,
            data.dropWhile { it.toInt() == 0 }.toByteArray()
        ).distinctBy { it.size }

        for (candidate in candidates) {
            inflate(candidate, nowrap = false)?.let { return it }
            inflate(candidate, nowrap = true)?.let { return it }
            unzip(candidate)?.let { return it }
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

    private fun unzip(data: ByteArray): String? =
        runCatching {
            GZIPInputStream(ByteArrayInputStream(data)).use { input ->
                val output = ByteArrayOutputStream()
                input.copyTo(output)
                output.toString(Charsets.UTF_8.name())
            }
        }.getOrNull()

    private val KEY_ROUND_SHIFT = intArrayOf(1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1)
    private val KEY_PERM_C = intArrayOf(
        56, 48, 40, 32, 24, 16, 8, 0, 57, 49, 41, 33, 25, 17, 9, 1, 58, 50, 42, 34, 26, 18, 10, 2,
        59, 51, 43, 35
    )
    private val KEY_PERM_D = intArrayOf(
        62, 54, 46, 38, 30, 22, 14, 6, 61, 53, 45, 37, 29, 21, 13, 5, 60, 52, 44, 36, 28, 20, 12, 4,
        27, 19, 11, 3
    )
    private val KEY_COMPRESSION = intArrayOf(
        13, 16, 10, 23, 0, 4, 2, 27, 14, 5, 20, 9, 22, 18, 11, 3, 25, 7, 15, 6, 26, 19, 12, 1,
        40, 51, 30, 36, 46, 54, 29, 39, 50, 44, 32, 47, 43, 48, 38, 55, 33, 52, 45, 41, 49, 35, 28, 31
    )

    private val SBOX = arrayOf(
        intArrayOf(
            14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7, 0, 15, 7, 4, 14, 2, 13, 1,
            10, 6, 12, 11, 9, 5, 3, 8, 4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0,
            15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13
        ),
        intArrayOf(
            15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10, 3, 13, 4, 7, 15, 2, 8, 15,
            12, 0, 1, 10, 6, 9, 11, 5, 0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15,
            13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9
        ),
        intArrayOf(
            10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8, 13, 7, 0, 9, 3, 4, 6, 10,
            2, 8, 5, 14, 12, 11, 15, 1, 13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7,
            1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12
        ),
        intArrayOf(
            7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15, 13, 8, 11, 5, 6, 15, 0, 3,
            4, 7, 2, 12, 1, 10, 14, 9, 10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4,
            3, 15, 0, 6, 10, 10, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14
        ),
        intArrayOf(
            2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9, 14, 11, 2, 12, 4, 7, 13, 1,
            5, 0, 15, 10, 3, 9, 8, 6, 4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14,
            11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3
        ),
        intArrayOf(
            12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11, 10, 15, 4, 2, 7, 12, 9, 5,
            6, 1, 13, 14, 0, 11, 3, 8, 9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6,
            4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13
        ),
        intArrayOf(
            4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1, 13, 0, 11, 7, 4, 9, 1, 10,
            14, 3, 5, 12, 2, 15, 8, 6, 1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2,
            6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12
        ),
        intArrayOf(
            13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7, 1, 15, 13, 8, 10, 3, 7, 4,
            12, 5, 6, 11, 0, 14, 9, 2, 7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8,
            2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11
        )
    )
}
