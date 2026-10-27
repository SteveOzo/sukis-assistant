package com.sukis.assistant.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Genera el token "Sec-MS-GEC" (v2) que pide el servicio gratuito de voces neurales de Microsoft Edge.
 * Portado de la librería Python edge-tts 7.3.1 (src/edge_tts/drm.py). Si Microsoft lo cambia,
 * Sukis usa automáticamente la voz de Android como respaldo.
 */
object EdgeToken {
    const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    const val CHROMIUM_FULL_VERSION = "143.0.3650.75"
    const val CHROMIUM_MAJOR = "143"
    const val SEC_MS_GEC_VERSION = "2-$CHROMIUM_FULL_VERSION"
    private const val WIN_EPOCH = 11644473600L
    private const val M = 0xFFFFFFFFL

    private val SHA_INITIAL = longArrayOf(
        0x6A09E667, 0xBB67AE85, 0x3C6EF372, 0xA54FF53A, 0x510E527F, 0x9B05688C, 0x1F83D9AB, 0x5BE0CD19,
    )
    private val SHA_STATE = longArrayOf(
        0xFD9FBEA2, 0x91243CE7, 0xBE499094, 0xCD2E1E0A, 0x6C006F49, 0x04BD35AB, 0xFEB0A7E4, 0x03EFFBD2,
    )
    private val SHA_SCHEDULE = longArrayOf(
        0xEB24BF9E, 0x1558691F, 0xA0C09EE2, 0x0CFA32BA, 0xA622EE8C, 0xD1DD9491, 0xC0DD25A0,
        0xF9E70AB6, 0xF0C31573, 0x7058CE4E, 0x7188E606, 0x00E5AB33, 0xD258B060, 0x72766564,
    )
    private val K = longArrayOf(
        0x428A2F98, 0x71374491, 0xB5C0FBCF, 0xE9B5DBA5, 0x3956C25B, 0x59F111F1, 0x923F82A4, 0xAB1C5ED5,
        0xD807AA98, 0x12835B01, 0x243185BE, 0x550C7DC3, 0x72BE5D74, 0x80DEB1FE, 0x9BDC06A7, 0xC19BF174,
        0xE49B69C1, 0xEFBE4786, 0x0FC19DC6, 0x240CA1CC, 0x2DE92C6F, 0x4A7484AA, 0x5CB0A9DC, 0x76F988DA,
        0x983E5152, 0xA831C66D, 0xB00327C8, 0xBF597FC7, 0xC6E00BF3, 0xD5A79147, 0x06CA6351, 0x14292967,
        0x27B70A85, 0x2E1B2138, 0x4D2C6DFC, 0x53380D13, 0x650A7354, 0x766A0ABB, 0x81C2C92E, 0x92722C85,
        0xA2BFE8A1, 0xA81A664B, 0xC24B8B70, 0xC76C51A3, 0xD192E819, 0xD6990624, 0xF40E3585, 0x106AA070,
        0x19A4C116, 0x1E376C08, 0x2748774C, 0x34B0BCB5, 0x391C0CB3, 0x4ED8AA4A, 0x5B9CCA4F, 0x682E6FF3,
        0x748F82EE, 0x78A5636F, 0x84C87814, 0x8CC70208, 0x90BEFFFA, 0xA4506CEB, 0xBEF9A3F7, 0xC67178F2,
    )
    private const val SHA_ADJ = 0xC9DC06A7L
    private val K_BLOCK = K.copyOf().also { it[14] = SHA_ADJ }
    private const val SIGMA0_ADJ = 0x059C0B80L
    private const val SIGMA1_ADJ = 0x000B92C0L

    private val CS64 = arrayOf(
        longArrayOf(0xE82E91B1, 0xB32700D0), longArrayOf(0x44DE24C6, 0x88DFF51D),
        longArrayOf(0x3BEB65BC, 0x29514038), longArrayOf(0xC89C5933, 0xB328CBDF),
        longArrayOf(0x8D1EC211, 0xD405B4D5), longArrayOf(0x314EF5D5, 0x26E55F3C),
        longArrayOf(0xE92491B1, 0x607801C6), longArrayOf(0xF0A5B9D5, 0x82B2C1FB),
        longArrayOf(0x06064CF3, 0xFA51A3DA), longArrayOf(0xDA79FA60, 0x207CC464),
        longArrayOf(0xBED20560, 0x3C3C74F3), longArrayOf(0x89F9C9F8, 0xD2D14B61),
        longArrayOf(0x35FA38C6, 0xBB04A2C1), longArrayOf(0xCB49E3C6, 0xC5626167),
    )
    private const val CS64_FACTOR = 0x82893933L
    private const val CS64_ADJ0 = 0xF2F90B1AL
    private const val CS64_ADJ1 = 0x0B1A0000L

    private fun rol16(v: Long): Long {
        val x = v and M
        return ((x shl 16) or (x ushr 16)) and M
    }

    private fun sigma0(v: Long) = ((v ushr 7) xor (v shl 25) xor (v ushr 18) xor (v shl 14) xor (v ushr 3)) and M
    private fun sigma1(v: Long) = ((v ushr 17) xor (v shl 15) xor (v ushr 19) xor (v shl 13) xor (v ushr 10)) and M
    private fun rotr(v: Long, n: Int) = ((v ushr n) or (v shl (32 - n))) and M

    private fun rounds(state: LongArray, w: LongArray, firstRound: Int): LongArray {
        val k = if (firstRound == 14) K_BLOCK else K
        var a = state[0]; var b = state[1]; var c = state[2]; var d = state[3]
        var e = state[4]; var f = state[5]; var g = state[6]; var h = state[7]
        for (i in firstRound until 64) {
            val s1 = rotr(e, 6) xor rotr(e, 11) xor rotr(e, 25)
            val ch = g xor (e and (f xor g))
            val t1 = (h + s1 + ch + k[i] + w[i]) and M
            val s0 = rotr(a, 2) xor rotr(a, 13) xor rotr(a, 22)
            val maj = (a and b) or (c and (a or b))
            val t2 = (s0 + maj) and M
            h = g; g = f; f = e; e = (d + t1) and M
            d = c; c = b; b = a; a = (t1 + t2) and M
        }
        return longArrayOf(a, b, c, d, e, f, g, h)
    }

    private fun expand(w: MutableList<Long>, firstWord: Int = 16) {
        for (i in firstWord until 64) {
            val s1 = sigma1(w[i - 2])
            val s0 = sigma0(w[i - 15])
            w.add((s1 + w[i - 7] + s0 + w[i - 16]) and M)
        }
    }

    private fun be32(b: ByteArray, off: Int, len: Int): Long {
        var v = 0L
        for (i in 0 until len) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }

    private fun shaInit(data: ByteArray): LongArray {
        val w = MutableList(14) { 0L }
        w.add(be32(data, 0, 3))
        w.add(be32(data, 3, 4))
        for (i in 16 until 30) {
            var v = SHA_SCHEDULE[i - 16]
            if (i - 2 >= 14) v += if (i == 16) sigma1(w[i - 2]) xor SIGMA1_ADJ else sigma1(w[i - 2])
            if (i - 7 >= 14) v += w[i - 7]
            if (i - 15 >= 14) v += if (i == 29) sigma0(w[i - 15]) xor SIGMA0_ADJ else sigma0(w[i - 15])
            w.add(v and M)
        }
        w.add((sigma1(w[28]) + w[23] + sigma0(w[15]) + w[14] + SHA_ADJ - K[14]) and M)
        expand(w, 31)
        val working = rounds(SHA_STATE, w.toLongArray(), 14)
        return LongArray(8) { (SHA_INITIAL[it] + working[it]) and M }
    }

    private fun shaDigest(data: ByteArray): ByteArray {
        require(data.size >= 7)
        val length = 57 + data.size
        val pad = Math.floorMod(55 - length, 64)
        val tail = data.copyOfRange(7, data.size)
        val padded = ByteBuffer.allocate(tail.size + 1 + pad + 8).order(ByteOrder.BIG_ENDIAN)
        padded.put(tail).put(0x80.toByte())
        repeat(pad) { padded.put(0) }
        padded.putLong(length.toLong() * 8)
        val bytes = padded.array()
        var state = shaInit(data.copyOfRange(0, 7))
        var off = 0
        while (off < bytes.size) {
            val w = MutableList(16) { be32(bytes, off + it * 4, 4) }
            expand(w)
            val working = rounds(state, w.toLongArray(), 0)
            state = LongArray(8) { (state[it] + working[it]) and M }
            off += 64
        }
        val out = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
        state.forEach { out.putInt(it.toInt()) }
        return out.array()
    }

    private fun wordsLe4Even(data: ByteArray): List<Long> {
        val padLen = Math.floorMod(-data.size, 4)
        val padded = data + ByteArray(padLen)
        if (padded.isEmpty()) return emptyList()
        val bb = ByteBuffer.wrap(padded).order(ByteOrder.LITTLE_ENDIAN)
        val words = MutableList(padded.size / 4) { bb.getInt(it * 4).toLong() and M }
        if (words.size % 2 == 1) words.add(0L)
        return words
    }

    private fun cs64(data: ByteArray, digest: ByteArray): Pair<Long, Long> {
        val bb = ByteBuffer.wrap(digest).order(ByteOrder.LITTLE_ENDIAN)
        val keyA = (bb.getInt(0).toLong() and M) or 1L
        val keyB = (bb.getInt(4).toLong() and M) or 1L
        val factors = longArrayOf((keyA * CS64_FACTOR) and M, (keyB * CS64_FACTOR) and M)
        val terms = ArrayList<Long>()
        val offsets = ArrayList<Long>()
        CS64.forEachIndexed { i, c ->
            terms.add((c[0] * factors[i % 2]) and M)
            offsets.add(c[1])
        }
        wordsLe4Even(byteArrayOf(0) + data).forEachIndexed { i, word ->
            var term = word * (if (i % 2 == 0) keyA else keyB)
            var offset = rol16(word) * (if (i % 2 == 0) 0xEF0569FBL else 0xC31713DBL)
            if (i == 0) {
                term += CS64_ADJ0 * factors[0]
                offset += CS64_ADJ1
            }
            terms.add(term and M)
            offsets.add(offset and M)
        }
        var swapAcc = 0L; var swapLast = 0L; var state = 0L; var acc = 0L
        var i = 0
        while (i < terms.size) {
            val x = terms[i]; val y = terms[i + 1]
            var t = rol16((offsets[i] + x) and M)
            var q = (t * 0x76F50F34L) and M
            swapAcc = swapAcc + ((rol16(q) * 0xC3EFEA97L + q) and M)
            t = rol16((offsets[i + 1] + y) and M)
            q = (t * 0x1881CE3CL) and M
            swapLast = (rol16(q) * 0x35BD1EC9L + q) and M
            swapAcc = (swapAcc + swapLast) and M

            state = (state * keyA + x) and M
            state = (rol16(state) * 0xCF98B111L) and M
            state = (rol16(state) * 0x87085B9FL) and M
            state = (rol16(state) * 0x12CEB96DL) and M
            state = (rol16(state) * 0x257E1D83L) and M
            acc += state
            t = (state * keyB + y) and M
            t = (rol16(t) * 0xA27416F5L) and M
            t = (rol16(t) * 0xD38396FFL) and M
            state = (rol16((rol16(t) * 0x7C932B89L) and M) * 0xBFA49F69L) and M
            acc = (acc + state) and M
            i += 2
        }
        return (swapLast xor state) to (swapAcc xor acc)
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02X".format(it.toInt() and 0xFF) }

    /** Token para un instante dado (segundos Unix). */
    fun generate(unixSeconds: Long): String {
        var secs = unixSeconds + WIN_EPOCH
        secs -= secs % 300
        val ticks = secs * 10_000_000L
        val data = (ticks.toString() + TRUSTED_CLIENT_TOKEN).toByteArray(Charsets.US_ASCII)
        val digest = shaDigest(data)
        val (a, b) = cs64(data, digest)
        val le = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(a.toInt()).putInt(b.toInt()).array()
        return hex(le) + "-" + hex(digest)
    }
}
