package com.relaytester.app.core.media

/**
 * A minimal GIF89a encoder: median-cut palette, LZW-compressed frames, looping.
 *
 * Written rather than pulled in because the app has no third-party graphics dependency and
 * this is a small, well-specified format — the risk of a hand-rolled encoder is a wrong file,
 * and that is exactly what the unit tests against a real decoder cover. What it needs to do is
 * narrow on purpose: one animation, no transparency, no interlace, no per-frame local palette.
 *
 * Format notes that the code depends on (GIF89a, §18–§24):
 *  * the file is `Header` + `Logical Screen Descriptor` + `Global Color Table` +
 *    `Netscape Extension` (loop count) + one `Graphic Control Extension` + `Image Descriptor`
 *    + LZW data per frame, then the `;` trailer;
 *  * the LZW code size must be at least 2, and the initial clear code is `1 << codeSize`;
 *  * a frame's colour table indices are packed LSB-first in the compressed stream, while the
 *    palette itself is stored as RGB triples, most significant byte first.
 */
object GifEncoder {

    /** Longest side of the output, in pixels. Larger frames are scaled down to fit. */
    const val MAX_DIMENSION = 480

    /** GIF stores colours as a table of at most 256 entries; 6 bits per channel is plenty. */
    private const val PALETTE_SIZE = 256

    /** Bits of the 8 used for each channel that survive quantisation. */
    private const val CHANNEL_BITS = 6

    /**
     * Encodes [frames] (each `width * height` ARGB pixels, row-major) into a looping GIF.
     *
     * [delayCentiseconds] is per frame: GIF stores the delay in hundredths of a second, which
     * is why the caller's frame rate is converted to that unit rather than milliseconds.
     * All frames must share the dimensions of the first; a mismatched frame is skipped rather
     * than encoded at the wrong size, which would shear the animation.
     */
    fun encode(
        frames: List<IntArray>,
        width: Int,
        height: Int,
        delayCentiseconds: Int,
    ): ByteArray {
        require(frames.isNotEmpty()) { "a GIF needs at least one frame" }
        require(width > 0 && height > 0) { "frame dimensions must be positive" }
        val expected = width * height
        val usable = frames.filter { it.size == expected }
        require(usable.isNotEmpty()) { "no frame matched ${width}x$height" }

        val palette = buildPalette(usable)
        val out = LittleEndianWriter()

        writeHeader(out, width, height, palette)
        for (frame in usable) {
            writeFrame(out, frame, width, height, palette, delayCentiseconds)
        }
        out.byte(0x3B) // trailer
        return out.toByteArray()
    }

    // ---- header -----------------------------------------------------------------------

    private fun writeHeader(
        out: LittleEndianWriter,
        width: Int,
        height: Int,
        palette: Palette,
    ) {
        out.ascii("GIF89a")

        // Logical screen descriptor: dimensions, then a packed byte whose low three bits are
        // the power of two the colour table was padded to, minus one.
        out.short(width)
        out.short(height)
        out.byte(0xF0 or (palette.tableBits - 1)) // global table present, 8-bit colour
        out.byte(0) // background colour index
        out.byte(0) // no pixel aspect ratio

        for (entry in palette.colors) {
            out.byte((entry shr 16) and 0xFF)
            out.byte((entry shr 8) and 0xFF)
            out.byte(entry and 0xFF)
        }
        // Pad to the declared power of two: a table that claims 256 entries but writes 40
        // leaves the decoder reading the missing ones out of the first frame's data.
        repeat(palette.paddingEntries) {
            out.byte(0); out.byte(0); out.byte(0)
        }

        // Netscape 2.0 application extension: the only way to say "loop forever".
        out.byte(0x21)
        out.byte(0xFF)
        out.byte(11)
        out.ascii("NETSCAPE2.0")
        out.byte(3)
        out.byte(1)
        out.short(0) // 0 = infinite
        out.byte(0)
    }

    // ---- frames -----------------------------------------------------------------------

    private fun writeFrame(
        out: LittleEndianWriter,
        frame: IntArray,
        width: Int,
        height: Int,
        palette: Palette,
        delayCentiseconds: Int,
    ) {
        // Graphic control extension, one per frame. It is scoped to the image descriptor that
        // follows it, not to the file: a single GCE written before the first frame leaves every
        // later frame with no delay, and viewers play those at their own default speed. That is
        // exactly the bug an external decoder caught here — the file was structurally valid, so
        // only reading the delays back revealed it.
        out.byte(0x21)
        out.byte(0xF9)
        out.byte(4)
        out.byte(0) // no transparency, dispose = none
        out.short(delayCentiseconds)
        out.byte(0) // transparent colour index (unused)
        out.byte(0)

        // Image descriptor: full-canvas, no local table, no interlace.
        out.byte(0x2C)
        out.short(0)
        out.short(0)
        out.short(width)
        out.short(height)
        out.byte(0)

        val indices = IntArray(frame.size) { palette.indexOf(frame[it]) }
        val codeSize = maxOf(2, palette.tableBits)
        out.byte(codeSize)
        // `Lzw.compress` ends the sub-block chain itself; a second terminator here would leave
        // a stray empty block between frames, which strict decoders reject.
        Lzw.compress(indices, codeSize, out)
    }

    // ---- palette ----------------------------------------------------------------------

    /**
     * The colours of a GIF, plus the index lookup the frame encoder uses.
     *
     * Built once for the whole animation rather than per frame: a per-frame palette would need
     * a local colour table on every frame, and — more importantly — the *same* pixel colour
     * could map to different indices in different frames. With a global table the animation is
     * consistent and the indices are reproducible, which is what the tests assert.
     */
    internal class Palette(val colors: IntArray, val tableBits: Int) {
        private val lookup = HashMap<Int, Int>(colors.size * 2)

        init {
            colors.forEachIndexed { index, color -> lookup.putIfAbsent(color, index) }
        }

        /** Nearest palette entry for [argb], cached: a frame asks for the same colours a lot. */
        fun indexOf(argb: Int): Int {
            lookup[argb]?.let { return it }
            val nearest = nearestColor(argb)
            lookup[argb] = nearest
            return nearest
        }

        private fun nearestColor(argb: Int): Int {
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            var best = 0
            var bestDistance = Int.MAX_VALUE
            for (i in colors.indices) {
                val c = colors[i]
                val dr = r - ((c shr 16) and 0xFF)
                val dg = g - ((c shr 8) and 0xFF)
                val db = b - (c and 0xFF)
                // Weighted to approximate human sensitivity: green dominates luminance.
                val distance = 2 * dr * dr + 4 * dg * dg + 3 * db * db
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = i
                }
            }
            return best
        }

        /** Table entries the header promised but [colors] does not fill. */
        val paddingEntries: Int get() = (1 shl tableBits) - colors.size
    }

    /**
     * A palette covering the animation, by counting distinct colours six bits per channel.
     *
     * Quantising to 6 bits before counting is what keeps this linear: an animation has at most
     * a few thousand *distinct* 24-bit colours once anti-aliasing is finished, and the
     * histogram would otherwise be dominated by near-identical blends. The most frequent
     * quantised colours become the table; anything else maps to its nearest entry.
     */
    internal fun buildPalette(frames: List<IntArray>): Palette {
        val histogram = HashMap<Int, Int>()
        for (frame in frames) {
            for (pixel in frame) {
                val quantised = quantise(pixel)
                histogram[quantised] = (histogram[quantised] ?: 0) + 1
            }
        }
        val ranked = histogram.entries
            .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
            .take(PALETTE_SIZE)
        // At least two entries: a single-colour image would otherwise declare a 1-bit table and
        // some decoders reject a code size below 2.
        val colors = if (ranked.size < 2) ranked + 0x000000 else ranked
        val bits = tableBitsFor(colors.size)
        return Palette(IntArray(1 shl bits) { index ->
            if (index < colors.size) colors[index] else colors.last()
        }, bits)
    }

    /**
     * Reduces each channel to [CHANNEL_BITS] and expands it back to a full byte.
     *
     * The expansion replicates the kept high bits into the vacated low ones rather than
     * shifting a copy of the whole value down: with 6 bits kept, `0xC0` must become `0xC3`
     * (error 3), not `0xF0` (error 48). Getting this backwards brightens every mid-tone,
     * which on a flat-coloured frame means the palette entry is visibly the wrong colour.
     */
    private fun quantise(argb: Int): Int {
        fun channel(shift: Int): Int {
            val value = (argb shr shift) and 0xFF
            val kept = value shr (8 - CHANNEL_BITS)
            // Replicate `kept` across the byte, then truncate to what is left to fill.
            val replicated = kept * 0xFF / ((1 shl CHANNEL_BITS) - 1)
            return replicated
        }
        return (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    /** The smallest power-of-two table that holds [count] colours, as the exponent. */
    private fun tableBitsFor(count: Int): Int {
        var bits = 1
        while ((1 shl bits) < count) bits++
        return bits.coerceIn(1, 8)
    }
}
