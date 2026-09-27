package com.example.lrcfetcher.lyrics.providers

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * Descifra las letras QRC de QQ Music: 3DES con la variante "no estándar" que usa el cliente
 * de QQ (DES de dominio público de Brad Conte con un orden de bytes y dos S-box alterados),
 * seguido de zlib. El algoritmo está documentado por la comunidad (QQMusicDecoder, LDDC).
 */
internal object QrcDecrypter {
    private val KEY = "!@#)(*\$%123ZXC!@!@#)(NHL".toByteArray(Charsets.US_ASCII)
    private const val ENCRYPT = 1
    private const val DECRYPT = 0

    private val SBOX = arrayOf(
        intArrayOf(
            14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7,
            0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8,
            4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0,
            15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13,
        ),
        intArrayOf(
            15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10,
            3, 13, 4, 7, 15, 2, 8, 15, 12, 0, 1, 10, 6, 9, 11, 5,
            0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15,
            13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9,
        ),
        intArrayOf(
            10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8,
            13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1,
            13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7,
            1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12,
        ),
        intArrayOf(
            7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15,
            13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9,
            10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4,
            3, 15, 0, 6, 10, 10, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14,
        ),
        intArrayOf(
            2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9,
            14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6,
            4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14,
            11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3,
        ),
        intArrayOf(
            12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11,
            10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8,
            9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6,
            4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13,
        ),
        intArrayOf(
            4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1,
            13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6,
            1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2,
            6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12,
        ),
        intArrayOf(
            13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7,
            1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2,
            7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8,
            2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11,
        ),
    )

    private val IP0 = intArrayOf(57, 49, 41, 33, 25, 17, 9, 1, 59, 51, 43, 35, 27, 19, 11, 3, 61, 53, 45, 37, 29, 21, 13, 5, 63, 55, 47, 39, 31, 23, 15, 7)
    private val IP1 = intArrayOf(56, 48, 40, 32, 24, 16, 8, 0, 58, 50, 42, 34, 26, 18, 10, 2, 60, 52, 44, 36, 28, 20, 12, 4, 62, 54, 46, 38, 30, 22, 14, 6)
    private val P_BITS = intArrayOf(15, 6, 19, 20, 28, 11, 27, 16, 0, 14, 22, 25, 4, 17, 30, 9, 1, 7, 23, 13, 31, 26, 2, 8, 18, 12, 29, 5, 21, 10, 3, 24)
    private val KEY_SHIFT = intArrayOf(1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1)
    private val KEY_PERM_C = intArrayOf(56, 48, 40, 32, 24, 16, 8, 0, 57, 49, 41, 33, 25, 17, 9, 1, 58, 50, 42, 34, 26, 18, 10, 2, 59, 51, 43, 35)
    private val KEY_PERM_D = intArrayOf(62, 54, 46, 38, 30, 22, 14, 6, 61, 53, 45, 37, 29, 21, 13, 5, 60, 52, 44, 36, 28, 20, 12, 4, 27, 19, 11, 3)
    private val KEY_COMPRESSION = intArrayOf(
        13, 16, 10, 23, 0, 4, 2, 27, 14, 5, 20, 9, 22, 18, 11, 3, 25, 7, 15, 6, 26, 19, 12, 1,
        40, 51, 30, 36, 46, 54, 29, 39, 50, 44, 32, 47, 43, 48, 38, 55, 33, 52, 45, 41, 49, 35, 28, 31,
    )

    private val schedule: Array<Array<IntArray>> by lazy {
        arrayOf(
            keySchedule(16, DECRYPT),
            keySchedule(8, ENCRYPT),
            keySchedule(0, DECRYPT),
        )
    }

    fun decryptHex(hex: String): String {
        val data = hexToBytes(hex.trim())
        val out = ByteArray(data.size - data.size % 8)
        val block = ByteArray(8)
        var i = 0
        while (i + 8 <= data.size) {
            System.arraycopy(data, i, block, 0, 8)
            var b = block
            for (k in 0 until 3) b = crypt(b, schedule[k])
            System.arraycopy(b, 0, out, i, 8)
            i += 8
        }
        return inflate(out)
    }

    /** Operación inversa (zlib + 3DES de QQ). Sólo se usa en pruebas para generar muestras. */
    internal fun encryptToHex(text: String): String {
        val deflater = java.util.zip.Deflater()
        deflater.setInput(text.toByteArray(Charsets.UTF_8))
        deflater.finish()
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!deflater.finished()) bos.write(buf, 0, deflater.deflate(buf))
        deflater.end()
        val plain = bos.toByteArray().let { it.copyOf((it.size + 7) / 8 * 8) }
        val enc = arrayOf(keySchedule(0, ENCRYPT), keySchedule(8, DECRYPT), keySchedule(16, ENCRYPT))
        val sb = StringBuilder()
        for (i in plain.indices step 8) {
            var b = plain.copyOfRange(i, i + 8)
            for (k in 0 until 3) b = crypt(b, enc[k])
            b.forEach { sb.append("%02X".format(it)) }
        }
        return sb.toString()
    }

    private fun inflate(bytes: ByteArray): String {
        val inflater = Inflater()
        inflater.setInput(bytes)
        val bos = ByteArrayOutputStream(bytes.size * 4)
        val buf = ByteArray(8192)
        try {
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                bos.write(buf, 0, n)
            }
        } finally {
            inflater.end()
        }
        return bos.toString("UTF-8")
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length / 2
        return ByteArray(len) { ((Character.digit(hex[it * 2], 16) shl 4) or Character.digit(hex[it * 2 + 1], 16)).toByte() }
    }

    private fun bitnum(a: ByteArray, b: Int, c: Int): Int =
        (((a[(b / 32) * 4 + 3 - (b % 32) / 8].toInt() and 0xFF) ushr (7 - b % 8)) and 1) shl c

    private fun bitnumIntr(a: Int, b: Int, c: Int): Int = ((a ushr (31 - b)) and 1) shl c

    private fun bitnumIntl(a: Int, b: Int, c: Int): Int = ((a shl b) and Int.MIN_VALUE) ushr c

    private fun sboxBit(a: Int): Int = (a and 32) or ((a and 31) ushr 1) or ((a and 1) shl 4)

    private fun crypt(input: ByteArray, key: Array<IntArray>): ByteArray {
        var s0 = 0
        var s1 = 0
        for (i in 0 until 32) {
            s0 = s0 or bitnum(input, IP0[i], 31 - i)
            s1 = s1 or bitnum(input, IP1[i], 31 - i)
        }
        for (idx in 0 until 15) {
            val prev = s1
            s1 = f(s1, key[idx]) xor s0
            s0 = prev
        }
        s0 = f(s1, key[15]) xor s0
        return inversePermutation(s0, s1)
    }

    private fun inversePermutation(s0: Int, s1: Int): ByteArray {
        val data = ByteArray(8)
        val order = intArrayOf(3, 2, 1, 0, 7, 6, 5, 4)
        for (k in 0 until 8) {
            val j = 7 - k
            data[order[k]] = (
                bitnumIntr(s1, j, 7) or bitnumIntr(s0, j, 6) or
                    bitnumIntr(s1, j + 8, 5) or bitnumIntr(s0, j + 8, 4) or
                    bitnumIntr(s1, j + 16, 3) or bitnumIntr(s0, j + 16, 2) or
                    bitnumIntr(s1, j + 24, 1) or bitnumIntr(s0, j + 24, 0)
                ).toByte()
        }
        return data
    }

    private fun f(state: Int, key: IntArray): Int {
        val t1 = bitnumIntl(state, 31, 0) or ((state and 0xf0000000.toInt()) ushr 1) or bitnumIntl(state, 4, 5) or
            bitnumIntl(state, 3, 6) or ((state and 0x0f000000) ushr 3) or bitnumIntl(state, 8, 11) or
            bitnumIntl(state, 7, 12) or ((state and 0x00f00000) ushr 5) or bitnumIntl(state, 12, 17) or
            bitnumIntl(state, 11, 18) or ((state and 0x000f0000) ushr 7) or bitnumIntl(state, 16, 23)
        val t2 = bitnumIntl(state, 15, 0) or ((state and 0x0000f000) shl 15) or bitnumIntl(state, 20, 5) or
            bitnumIntl(state, 19, 6) or ((state and 0x00000f00) shl 13) or bitnumIntl(state, 24, 11) or
            bitnumIntl(state, 23, 12) or ((state and 0x000000f0) shl 11) or bitnumIntl(state, 28, 17) or
            bitnumIntl(state, 27, 18) or ((state and 0x0000000f) shl 9) or bitnumIntl(state, 0, 23)

        val l0 = ((t1 ushr 24) and 0xff) xor key[0]
        val l1 = ((t1 ushr 16) and 0xff) xor key[1]
        val l2 = ((t1 ushr 8) and 0xff) xor key[2]
        val l3 = ((t2 ushr 24) and 0xff) xor key[3]
        val l4 = ((t2 ushr 16) and 0xff) xor key[4]
        val l5 = ((t2 ushr 8) and 0xff) xor key[5]

        val s = (SBOX[0][sboxBit(l0 ushr 2)] shl 28) or
            (SBOX[1][sboxBit(((l0 and 0x03) shl 4) or (l1 ushr 4))] shl 24) or
            (SBOX[2][sboxBit(((l1 and 0x0f) shl 2) or (l2 ushr 6))] shl 20) or
            (SBOX[3][sboxBit(l2 and 0x3f)] shl 16) or
            (SBOX[4][sboxBit(l3 ushr 2)] shl 12) or
            (SBOX[5][sboxBit(((l3 and 0x03) shl 4) or (l4 ushr 4))] shl 8) or
            (SBOX[6][sboxBit(((l4 and 0x0f) shl 2) or (l5 ushr 6))] shl 4) or
            SBOX[7][sboxBit(l5 and 0x3f)]

        var r = 0
        for (i in 0 until 32) r = r or bitnumIntl(s, P_BITS[i], i)
        return r
    }

    private fun keySchedule(offset: Int, mode: Int): Array<IntArray> {
        val key = KEY.copyOfRange(offset, KEY.size)
        val schedule = Array(16) { IntArray(6) }
        var c = 0
        var d = 0
        for (i in 0 until 28) {
            c = c or bitnum(key, KEY_PERM_C[i], 31 - i)
            d = d or bitnum(key, KEY_PERM_D[i], 31 - i)
        }
        for (i in 0 until 16) {
            val sh = KEY_SHIFT[i]
            c = ((c shl sh) or (c ushr (28 - sh))) and 0xfffffff0.toInt()
            d = ((d shl sh) or (d ushr (28 - sh))) and 0xfffffff0.toInt()
            val togen = if (mode == DECRYPT) 15 - i else i
            val row = IntArray(6)
            for (j in 0 until 24) row[j / 8] = row[j / 8] or bitnumIntr(c, KEY_COMPRESSION[j], 7 - (j % 8))
            for (j in 24 until 48) row[j / 8] = row[j / 8] or bitnumIntr(d, KEY_COMPRESSION[j] - 27, 7 - (j % 8))
            schedule[togen] = row
        }
        return schedule
    }
}
