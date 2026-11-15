package com.arcu.attnd.domain

/** QR error-correction level (ISO/IEC 18004), with its two format bits. */
enum class QrEcc(val formatBits: Int) { LOW(1), MEDIUM(0), QUARTILE(3), HIGH(2) }

/**
 * A QR code symbol: [size] × [size] modules, `true` = dark. Row-major ([get] takes x = column, y = row).
 */
class QrCode internal constructor(val version: Int, val ecc: QrEcc, val mask: Int, private val modules: Array<BooleanArray>) {
    val size: Int get() = modules.size

    operator fun get(x: Int, y: Int): Boolean = modules[y][x]

    /** One string per row, `#` dark and `.` light (tests compare against a reference encoder). */
    fun rows(): List<String> = modules.map { row -> row.joinToString("") { if (it) "#" else "." } }

    companion object {
        /**
         * Encodes [text] (UTF-8, byte mode) at [ecc] in the smallest version 1–40 that holds it, with the
         * mask of the lowest penalty (or [forceMask] 0–7). Null when the text is too long for version 40.
         * The share block's QR (assistant spec §7): a plan link is ≤ ~1,550 bytes, version ≤ 31 at MEDIUM.
         */
        fun encode(text: String, ecc: QrEcc = QrEcc.MEDIUM, forceMask: Int? = null, forceVersion: Int? = null): QrCode? {
            val data = text.toByteArray(Charsets.UTF_8)
            val versions = forceVersion?.let { listOf(it) } ?: (1..40).toList()
            val version = versions.firstOrNull { v -> bitsNeeded(data.size, v) <= QrTables.dataCodewords(v, ecc) * 8 } ?: return null
            return QrBuilder(version, ecc).build(dataCodewords(data, version, ecc), forceMask)
        }

        private fun countBits(version: Int) = if (version <= 9) 8 else 16

        private fun bitsNeeded(bytes: Int, version: Int): Int =
            if (bytes >= (1 shl countBits(version))) Int.MAX_VALUE else 4 + countBits(version) + bytes * 8

        private fun dataCodewords(data: ByteArray, version: Int, ecc: QrEcc): IntArray {
            val capacity = QrTables.dataCodewords(version, ecc) * 8
            val bits = BitBuffer()
            bits.append(0b0100, 4)
            bits.append(data.size, countBits(version))
            data.forEach { bits.append(it.toInt() and 0xFF, 8) }
            bits.append(0, minOf(4, capacity - bits.size))
            bits.append(0, (8 - bits.size % 8) % 8)
            var pad = 0xEC
            while (bits.size < capacity) {
                bits.append(pad, 8)
                pad = pad xor 0xEC xor 0x11
            }
            return IntArray(bits.size / 8) { i -> (0 until 8).fold(0) { acc, b -> (acc shl 1) or (if (bits[i * 8 + b]) 1 else 0) } }
        }
    }
}

private class BitBuffer {
    private val bits = ArrayList<Boolean>()
    val size: Int get() = bits.size
    operator fun get(i: Int) = bits[i]
    fun append(value: Int, length: Int) {
        for (i in length - 1 downTo 0) bits += ((value ushr i) and 1) != 0
    }
}

internal object QrTables {
    private val ECC_PER_BLOCK = arrayOf(
        intArrayOf(-1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
        intArrayOf(-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28),
        intArrayOf(-1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30, 28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
        intArrayOf(-1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28, 30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
    )
    private val BLOCKS = arrayOf(
        intArrayOf(-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25),
        intArrayOf(-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49),
        intArrayOf(-1, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8, 8, 10, 12, 16, 12, 17, 16, 18, 21, 20, 23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68),
        intArrayOf(-1, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25, 25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77, 81),
    )

    fun eccPerBlock(version: Int, ecc: QrEcc) = ECC_PER_BLOCK[ecc.ordinal][version]
    fun blocks(version: Int, ecc: QrEcc) = BLOCKS[ecc.ordinal][version]

    fun rawDataModules(version: Int): Int {
        var result = (16 * version + 128) * version + 64
        if (version >= 2) {
            val align = version / 7 + 2
            result -= (25 * align - 10) * align - 55
            if (version >= 7) result -= 36
        }
        return result
    }

    fun dataCodewords(version: Int, ecc: QrEcc) = rawDataModules(version) / 8 - eccPerBlock(version, ecc) * blocks(version, ecc)

    fun alignmentPositions(version: Int): IntArray {
        if (version == 1) return IntArray(0)
        val count = version / 7 + 2
        val step = if (version == 32) 26 else (version * 4 + count * 2 + 1) / (count * 2 - 2) * 2
        val size = version * 4 + 17
        val result = IntArray(count)
        result[0] = 6
        var pos = size - 7
        for (i in count - 1 downTo 1) {
            result[i] = pos
            pos -= step
        }
        return result
    }
}

/** Reed–Solomon over GF(256) with the QR polynomial 0x11D. */
internal object ReedSolomon {
    fun multiply(x: Int, y: Int): Int {
        var z = 0
        for (i in 7 downTo 0) {
            z = (z shl 1) xor ((z ushr 7) * 0x11D)
            z = z xor (((y ushr i) and 1) * x)
        }
        return z
    }

    fun divisor(degree: Int): IntArray {
        val result = IntArray(degree)
        result[degree - 1] = 1
        var root = 1
        for (i in 0 until degree) {
            for (j in result.indices) {
                result[j] = multiply(result[j], root)
                if (j + 1 < result.size) result[j] = result[j] xor result[j + 1]
            }
            root = multiply(root, 0x02)
        }
        return result
    }

    fun remainder(data: IntArray, divisor: IntArray): IntArray {
        val result = IntArray(divisor.size)
        for (b in data) {
            val factor = b xor result[0]
            System.arraycopy(result, 1, result, 0, result.size - 1)
            result[result.size - 1] = 0
            for (i in result.indices) result[i] = result[i] xor multiply(divisor[i], factor)
        }
        return result
    }
}

private class QrBuilder(private val version: Int, private val ecc: QrEcc) {
    private val size = version * 4 + 17
    private val modules = Array(size) { BooleanArray(size) }
    private val function = Array(size) { BooleanArray(size) }

    fun build(data: IntArray, forceMask: Int?): QrCode {
        drawFunctionPatterns()
        drawCodewords(interleave(data))
        val mask = forceMask ?: (0..7).minBy { m ->
            applyMask(m)
            drawFormat(m)
            val penalty = penalty()
            applyMask(m)
            penalty
        }
        applyMask(mask)
        drawFormat(mask)
        return QrCode(version, ecc, mask, modules)
    }

    private fun set(x: Int, y: Int, dark: Boolean) {
        modules[y][x] = dark
        function[y][x] = true
    }

    private fun drawFunctionPatterns() {
        for (i in 0 until size) {
            set(6, i, i % 2 == 0)
            set(i, 6, i % 2 == 0)
        }
        finder(3, 3)
        finder(size - 4, 3)
        finder(3, size - 4)
        val positions = QrTables.alignmentPositions(version)
        val last = positions.size - 1
        for (i in positions.indices) for (j in positions.indices) {
            if ((i == 0 && j == 0) || (i == 0 && j == last) || (i == last && j == 0)) continue
            for (dy in -2..2) for (dx in -2..2) set(positions[i] + dx, positions[j] + dy, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1)
        }
        drawFormat(0)
        drawVersion()
    }

    private fun finder(x: Int, y: Int) {
        for (dy in -4..4) for (dx in -4..4) {
            val dist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
            val xx = x + dx
            val yy = y + dy
            if (xx in 0 until size && yy in 0 until size) set(xx, yy, dist != 2 && dist != 4)
        }
    }

    private fun drawFormat(mask: Int) {
        val data = (ecc.formatBits shl 3) or mask
        var rem = data
        repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
        val bits = ((data shl 10) or rem) xor 0x5412
        fun bit(i: Int) = ((bits ushr i) and 1) != 0
        for (i in 0..5) set(8, i, bit(i))
        set(8, 7, bit(6))
        set(8, 8, bit(7))
        set(7, 8, bit(8))
        for (i in 9 until 15) set(14 - i, 8, bit(i))
        for (i in 0 until 8) set(size - 1 - i, 8, bit(i))
        for (i in 8 until 15) set(8, size - 15 + i, bit(i))
        set(8, size - 8, true)
    }

    private fun drawVersion() {
        if (version < 7) return
        var rem = version
        repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
        val bits = (version shl 12) or rem
        for (i in 0 until 18) {
            val dark = ((bits ushr i) and 1) != 0
            val a = size - 11 + i % 3
            val b = i / 3
            set(a, b, dark)
            set(b, a, dark)
        }
    }

    private fun interleave(data: IntArray): IntArray {
        val blocks = QrTables.blocks(version, ecc)
        val eccLen = QrTables.eccPerBlock(version, ecc)
        val raw = QrTables.rawDataModules(version) / 8
        val shortBlocks = blocks - raw % blocks
        val shortLen = raw / blocks
        val divisor = ReedSolomon.divisor(eccLen)
        val list = ArrayList<IntArray>()
        var k = 0
        for (i in 0 until blocks) {
            val len = shortLen - eccLen + if (i < shortBlocks) 0 else 1
            val dat = data.copyOfRange(k, k + len)
            k += len
            val eccBytes = ReedSolomon.remainder(dat, divisor)
            val padded = if (i < shortBlocks) dat + 0 else dat
            list += padded + eccBytes
        }
        val result = ArrayList<Int>(raw)
        for (i in list[0].indices) for (j in list.indices) {
            if (i != shortLen - eccLen || j >= shortBlocks) result += list[j][i]
        }
        return result.toIntArray()
    }

    private fun drawCodewords(data: IntArray) {
        var i = 0
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (vert in 0 until size) for (j in 0..1) {
                val x = right - j
                val upward = ((right + 1) and 2) == 0
                val y = if (upward) size - 1 - vert else vert
                if (!function[y][x] && i < data.size * 8) {
                    modules[y][x] = ((data[i ushr 3] ushr (7 - (i and 7))) and 1) != 0
                    i++
                }
            }
            right -= 2
        }
    }

    private fun applyMask(mask: Int) {
        for (y in 0 until size) for (x in 0 until size) {
            val invert = when (mask) {
                0 -> (x + y) % 2 == 0
                1 -> y % 2 == 0
                2 -> x % 3 == 0
                3 -> (x + y) % 3 == 0
                4 -> (x / 3 + y / 2) % 2 == 0
                5 -> x * y % 2 + x * y % 3 == 0
                6 -> (x * y % 2 + x * y % 3) % 2 == 0
                else -> ((x + y) % 2 + x * y % 3) % 2 == 0
            }
            if (invert && !function[y][x]) modules[y][x] = !modules[y][x]
        }
    }

    private fun penalty(): Int {
        var result = 0
        fun line(get: (Int) -> Boolean) {
            var color = false
            var run = 0
            val history = IntArray(7)
            for (i in 0 until size) {
                if (get(i) == color) {
                    run++
                    if (run == 5) result += 3 else if (run > 5) result++
                } else {
                    addHistory(run, history)
                    if (!color) result += countPatterns(history) * 40
                    color = get(i)
                    run = 1
                }
            }
            if (color) {
                addHistory(run, history)
                run = 0
            }
            run += size
            addHistory(run, history)
            result += countPatterns(history) * 40
        }
        for (y in 0 until size) line { x -> modules[y][x] }
        for (x in 0 until size) line { y -> modules[y][x] }
        for (y in 0 until size - 1) for (x in 0 until size - 1) {
            val c = modules[y][x]
            if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) result += 3
        }
        val dark = modules.sumOf { row -> row.count { it } }
        val total = size * size
        val k = ((kotlin.math.abs(dark * 20L - total * 10L) + total - 1) / total).toInt() - 1
        return result + k * 10
    }

    private fun addHistory(length: Int, history: IntArray) {
        var run = length
        if (history[0] == 0) run += size
        System.arraycopy(history, 0, history, 1, history.size - 1)
        history[0] = run
    }

    private fun countPatterns(h: IntArray): Int {
        val n = h[1]
        val core = n > 0 && h[2] == n && h[3] == n * 3 && h[4] == n && h[5] == n
        return (if (core && h[0] >= n * 4 && h[6] >= n) 1 else 0) + (if (core && h[6] >= n * 4 && h[0] >= n) 1 else 0)
    }
}
