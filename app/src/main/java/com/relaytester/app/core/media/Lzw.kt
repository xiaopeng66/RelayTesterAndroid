package com.relaytester.app.core.media

/**
 * GIF's variable-width LZW, writing into sub-blocks as the format requires.
 *
 * The GIF variant differs from the TIFF one in two ways that are easy to get wrong and are
 * both handled here: codes start at `minimumCodeSize + 1` bits and grow as the dictionary
 * fills, and the stream is reset with a clear code *before* the dictionary actually overflows
 * (at 4096 entries, one early is required — some decoders stop one short).
 *
 * Output is emitted as sub-blocks: each chunk is prefixed with its length (1–255) and the whole
 * run is terminated by a zero-length block. Writing the codes as one flat byte run produces a
 * file that looks almost right and fails in most decoders.
 */
internal object Lzw {

    private const val MAX_CODE = 4095

    fun compress(indices: IntArray, minimumCodeSize: Int, out: LittleEndianWriter) {
        val clearCode = 1 shl minimumCodeSize
        val endCode = clearCode + 1
        var codeSize = minimumCodeSize + 1
        var nextCode = endCode + 1
        var dictionary = HashMap<Int, Int>()

        val block = SubBlockWriter(out)
        val bits = BitPacker(block)

        fun resetDictionary() {
            dictionary = HashMap<Int, Int>()
            nextCode = endCode + 1
            codeSize = minimumCodeSize + 1
        }

        bits.write(clearCode, codeSize)
        resetDictionary()

        if (indices.isEmpty()) {
            bits.write(endCode, codeSize)
            bits.finish()
            block.finish()
            return
        }

        var prefix = indices[0]
        for (i in 1 until indices.size) {
            val next = indices[i]
            // The dictionary is keyed on (prefix code, next index) packed into one Int: prefix
            // fits in 12 bits and the index in 8, so no collision is possible.
            val key = (prefix shl 8) or next
            val found = dictionary[key]
            if (found != null) {
                prefix = found
                continue
            }
            bits.write(prefix, codeSize)
            if (nextCode <= MAX_CODE) {
                dictionary[key] = nextCode
                nextCode++
                // Grow *after* the dictionary has more entries than the current width can
                // address; growing early emits codes the decoder has not widened for yet.
                if (nextCode > (1 shl codeSize) && codeSize < 12) codeSize++
            } else {
                // The dictionary is full: start over so the decoder's is rebuilt in step.
                bits.write(clearCode, codeSize)
                resetDictionary()
            }
            prefix = next
        }
        bits.write(prefix, codeSize)
        bits.write(endCode, codeSize)
        bits.finish()
        block.finish()
    }

    /**
     * Packs codes LSB-first into bytes.
     *
     * GIF reads the compressed data least-significant-bit first, the opposite of most other LZW
     * containers. Getting this backwards still produces a valid-looking file whose frames
     * decode to noise.
     */
    private class BitPacker(private val out: SubBlockWriter) {
        private var accumulator = 0
        private var bitCount = 0

        fun write(code: Int, width: Int) {
            accumulator = accumulator or (code shl bitCount)
            bitCount += width
            while (bitCount >= 8) {
                out.byte(accumulator and 0xFF)
                accumulator = accumulator ushr 8
                bitCount -= 8
            }
        }

        fun finish() {
            if (bitCount > 0) {
                out.byte(accumulator and 0xFF)
                accumulator = 0
                bitCount = 0
            }
        }
    }

    /**
     * Splits a byte stream into the length-prefixed sub-blocks GIF requires.
     *
     * Buffers up to 255 bytes and flushes each as `[length][data…]`; [finish] writes the
     * terminating zero-length block. Without that terminator the decoder reads into the next
     * frame's descriptor as if it were pixel data.
     */
    private class SubBlockWriter(private val out: LittleEndianWriter) {
        private val buffer = ByteArray(255)
        private var size = 0

        fun byte(value: Int) {
            buffer[size++] = value.toByte()
            if (size == 255) flush()
        }

        private fun flush() {
            if (size == 0) return
            out.byte(size)
            for (i in 0 until size) out.byte(buffer[i].toInt() and 0xFF)
            size = 0
        }

        fun finish() {
            flush()
            out.byte(0)
        }
    }
}

/**
 * A growable output buffer in the byte order GIF expects.
 *
 * Deliberately not `ByteArrayOutputStream`: the multi-byte fields here (`short`) are written
 * little-endian while the *palette* is written byte-by-byte in RGB order, and keeping both in
 * one place makes that asymmetry explicit rather than dependent on an external helper.
 */
internal class LittleEndianWriter(initialCapacity: Int = 64 * 1024) {
    private var buffer = ByteArray(initialCapacity)
    private var size = 0

    fun byte(value: Int) {
        ensure(1)
        buffer[size++] = value.toByte()
    }

    fun short(value: Int) {
        ensure(2)
        buffer[size++] = (value and 0xFF).toByte()
        buffer[size++] = ((value shr 8) and 0xFF).toByte()
    }

    fun ascii(value: String) {
        ensure(value.length)
        for (char in value) buffer[size++] = char.code.toByte()
    }

    private fun ensure(extra: Int) {
        if (size + extra <= buffer.size) return
        var capacity = buffer.size * 2
        while (capacity < size + extra) capacity *= 2
        buffer = buffer.copyOf(capacity)
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)
}
