package com.relaytester.app

/**
 * A GIF89a reader written for the tests, so the encoder has an independent oracle.
 *
 * `javax.imageio` is not on this project's unit-test classpath (Android unit tests compile
 * against `android.jar`, and no image library is a declared dependency), so the decoder that
 * checks [com.relaytester.app.core.media.GifEncoder] cannot be borrowed from the JDK. Writing it
 * is also the better test: it re-derives the format from the specification rather than sharing
 * the encoder's assumptions, so a misunderstanding in the encoder does not get mirrored in the
 * check. The two implementations agreeing on a frame is real evidence; one implementation
 * agreeing with itself is not.
 *
 * It parses only what the encoder claims to emit — no local colour tables, no interlace, no
 * transparency — and throws on anything else rather than guessing, so an unexpected structure
 * fails the test instead of being silently tolerated.
 */
internal class GifReader private constructor(
    private val bytes: ByteArray,
    val width: Int,
    val height: Int,
    private val globalPalette: IntArray,
    private val frames: List<Frame>,
) {

    internal class Frame(val pixels: IntArray, val delayCentiseconds: Int)

    val frameCount: Int get() = frames.size

    fun frame(index: Int): Frame = frames[index]

    /** Colour at `(x, y)` in frame [index], as 0xRRGGBB. */
    fun pixel(index: Int, x: Int, y: Int): Int =
        frames[index].pixels[y * width + x] and 0xFFFFFF

    companion object {
        fun read(bytes: ByteArray): GifReader {
            val cursor = Cursor(bytes)

            val signature = cursor.ascii(6)
            require(signature == "GIF89a") { "not a GIF89a file: $signature" }

            val width = cursor.short()
            val height = cursor.short()
            val packed = cursor.byte()
            require(packed and 0x80 != 0) { "expected a global colour table" }
            val tableSize = 1 shl ((packed and 0x07) + 1)
            cursor.byte() // background colour index
            cursor.byte() // pixel aspect ratio

            val palette = IntArray(tableSize)
            for (i in 0 until tableSize) {
                palette[i] = (cursor.byte() shl 16) or (cursor.byte() shl 8) or cursor.byte()
            }

            var pendingDelay = 0
            val frames = ArrayList<Frame>()

            while (true) {
                when (val block = cursor.byte()) {
                    0x3B -> break // trailer
                    0x21 -> {
                        // Extension. The Graphic Control one carries the frame delay; the
                        // Netscape application extension carries the loop count, which is
                        // consumed and discarded here because no test reads it back.
                        val label = cursor.byte()
                        if (label == 0xF9) {
                            val blockSize = cursor.byte()
                            require(blockSize == 4) { "unexpected graphic control size $blockSize" }
                            val flags = cursor.byte()
                            require(flags and 0x01 == 0) { "encoder does not write transparency" }
                            pendingDelay = cursor.short()
                            cursor.byte() // transparent colour index
                            cursor.byte() // block terminator
                        } else {
                            cursor.skipSubBlocks()
                        }
                    }
                    0x2C -> {
                        val left = cursor.short()
                        val top = cursor.short()
                        val frameWidth = cursor.short()
                        val frameHeight = cursor.short()
                        val framePacked = cursor.byte()
                        require(framePacked and 0x80 == 0) { "encoder writes no local colour table" }
                        require(framePacked and 0x40 == 0) { "encoder does not interlace" }
                        require(left == 0 && top == 0) { "encoder writes full-canvas frames" }
                        require(frameWidth == width && frameHeight == height) {
                            "frame is ${frameWidth}x$frameHeight, screen is ${width}x$height"
                        }

                        val codeSize = cursor.byte()
                        val indices = LzwDecoder(cursor).decode(codeSize, width * height)
                        val pixels = IntArray(indices.size) { palette[indices[it]] }
                        frames += Frame(pixels, pendingDelay)
                        pendingDelay = 0
                    }
                    else -> error("unexpected block 0x${block.toString(16)} at ${cursor.position}")
                }
            }
            require(frames.isNotEmpty()) { "no frames in file" }
            return GifReader(bytes, width, height, palette, frames)
        }
    }

    /** Byte cursor with the GIF's little-endian words and length-prefixed sub-blocks. */
    private class Cursor(private val bytes: ByteArray) {
        var position = 0

        fun byte(): Int {
            require(position < bytes.size) { "unexpected end of file at $position" }
            return bytes[position++].toInt() and 0xFF
        }

        fun short(): Int = byte() or (byte() shl 8)

        fun ascii(length: Int): String {
            val out = StringBuilder(length)
            repeat(length) { out.append(byte().toChar()) }
            return out.toString()
        }

        fun skipSubBlocks() {
            while (true) {
                val size = byte()
                if (size == 0) return
                position += size
            }
        }
    }

    /**
     * The GIF flavour of LZW: codes grow from `minimumCodeSize + 1` bits, the dictionary resets
     * on the clear code, and a dictionary that fills to 4096 entries must be cleared.
     */
    private class LzwDecoder(private val cursor: Cursor) {

        fun decode(minimumCodeSize: Int, expectedPixels: Int): IntArray {
            val out = ArrayList<Int>(expectedPixels)
            val clearCode = 1 shl minimumCodeSize
            val endCode = clearCode + 1
            var codeSize = minimumCodeSize + 1

            var table = ArrayList<IntArray>()
            var nextCode = endCode + 1
            fun resetTable() {
                table = ArrayList<IntArray>()
                for (i in 0 until clearCode) table.add(intArrayOf(i))
                table.add(intArrayOf())
                table.add(intArrayOf())
                nextCode = endCode + 1
                codeSize = minimumCodeSize + 1
            }

            val reader = SubBlockReader()
            var previous: IntArray? = null

            // Run to the end code rather than stopping at the expected pixel count. Stopping
            // early leaves the sub-block terminator unread, and the frame reader then mistakes
            // that zero byte for the next block type — which is how this went wrong once
            // already. The bound is a guard against a corrupt stream, not the normal exit.
            var guard = expectedPixels + 4096
            while (true) {
                if (guard-- <= 0) error("LZW stream did not end within a plausible length")
                val code = reader.read(codeSize)
                if (code == endCode) break
                if (code == clearCode) {
                    resetTable()
                    previous = null
                    continue
                }

                val entry: IntArray = when {
                    code < table.size && table[code].isNotEmpty() -> table[code]
                    // The one code that is not yet in the table: it is the previous string plus
                    // the first character of itself, which is what makes LZW decodable.
                    code == table.size && previous != null -> previous + previous[0]
                    else -> error("bad LZW code $code (table ${table.size})")
                }
                out.addAll(entry.toList())
                require(out.size <= expectedPixels) {
                    "LZW stream produced ${out.size} pixels, expected $expectedPixels"
                }

                if (previous != null && nextCode < 4096) {
                    table.add(previous + entry[0])
                    nextCode++
                    // Widen when the next code to assign no longer fits the current width.
                    // A decoder lags the encoder by one entry (it cannot add its entry until
                    // the following code arrives), so its widening test is `==` where the
                    // encoder's is `>`. Using the encoder's comparison here reads the next
                    // code at the wrong width and the rest of the stream is noise.
                    if (nextCode == (1 shl codeSize) && codeSize < 12) codeSize++
                }
                previous = entry
            }
            // Drain the rest of the sub-block chain. The reader only pulls bytes it needs, so
            // after the end code the zero-length terminator is still sitting in the stream;
            // leaving it there makes the caller read it as the next block type.
            reader.drain()
            return out.toIntArray()
        }

        /** Reads the sub-block chain as a flat bit stream, LSB-first. */
        private inner class SubBlockReader {
            private var block: ByteArray = ByteArray(0)
            private var blockPosition = 0
            private var bitBuffer = 0
            private var bitCount = 0

            private fun nextByte(): Int {
                while (blockPosition >= block.size) {
                    val size = cursor.byte()
                    if (size == 0) error("LZW data ended before the expected pixel count")
                    block = ByteArray(size) { cursor.byte().toByte() }
                    blockPosition = 0
                }
                return block[blockPosition++].toInt() and 0xFF
            }

            fun read(width: Int): Int {
                while (bitCount < width) {
                    bitBuffer = bitBuffer or (nextByte() shl bitCount)
                    bitCount += 8
                }
                val value = bitBuffer and ((1 shl width) - 1)
                bitBuffer = bitBuffer ushr width
                bitCount -= width
                return value
            }

            /** Consumes the remainder of the current block and every block up to the terminator. */
            fun drain() {
                // Whatever is left of the block that is already loaded, then the chain.
                cursor.position += block.size - blockPosition
                blockPosition = block.size
                cursor.skipSubBlocks()
            }
        }
    }
}
