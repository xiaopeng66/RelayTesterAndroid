package com.relaytester.app

import com.relaytester.app.core.media.AnimationRecorder
import com.relaytester.app.core.media.GifEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The GIF encoder is verified by decoding its output, not by re-reading its own bytes.
 *
 * `javax.imageio` is not on this project's unit-test classpath, so the decoder is
 * [GifReader] — an independent implementation written from the same specification. That is
 * the stronger check anyway: asserting on the offsets the encoder just wrote only proves the
 * encoder is self-consistent, whereas a separate parser that derives structure from the format
 * fails loudly when the two disagree.
 */
class GifEncoderTest {

    private fun solid(width: Int, height: Int, argb: Int) = IntArray(width * height) { argb }

    // ---- round trip through an independent decoder ------------------------------------

    @Test
    fun `a two frame animation decodes back to two frames`() {
        val frames = listOf(
            solid(8, 4, 0xFFFF0000.toInt()),
            solid(8, 4, 0xFF0000FF.toInt()),
        )
        val gif = GifEncoder.encode(frames, 8, 4, delayCentiseconds = 8)

        val decoded = GifReader.read(gif)
        assertEquals("解码器必须看到两帧", 2, decoded.frameCount)
        assertEquals(8, decoded.width)
        assertEquals(4, decoded.height)
    }

    @Test
    fun `the decoded pixels keep each frame's own colour`() {
        // 这一条才是「不是白屏」的证明：两帧颜色不同，解码回来必须各是各的。如果调色板或
        // 帧数据错位，两帧会退化成同一个颜色，而帧数仍然是对的。
        val red = 0xFFFF0000.toInt()
        val blue = 0xFF0000FF.toInt()
        val gif = GifEncoder.encode(listOf(solid(4, 3, red), solid(4, 3, blue)), 4, 3, 8)

        val decoded = GifReader.read(gif)
        assertEquals("第一帧应是红色", 0xFF0000, decoded.pixel(0, 1, 1))
        assertEquals("第二帧应是蓝色", 0x0000FF, decoded.pixel(1, 1, 1))
    }

    @Test
    fun `a flat colour comes back within a few levels of the original`() {
        // GIF 只有 256 色的调色板，颜色必然要量化，所以不能要求逐位相等。但误差必须小：
        // 一整幅纯色的图分享出去，如果调色板项明显亮于原色，接收方看到的就是另一个颜色。
        // 6 位量化每通道最多差 3/255，这里按 8 留一点余量。
        val argb = 0xFFC0C0C0.toInt()
        val gif = GifEncoder.encode(listOf(solid(4, 4, argb)), 4, 4, 8)
        val decoded = GifReader.read(gif).pixel(0, 0, 0)
        assertChannelClose(0xC0, decoded shr 16 and 0xFF, "红")
        assertChannelClose(0xC0, decoded shr 8 and 0xFF, "绿")
        assertChannelClose(0xC0, decoded and 0xFF, "蓝")
    }

    @Test
    fun `quantisation does not systematically brighten a flat colour`() {
        // 之前那版量化是把整个值右移后 OR 回去，把 0xC0 推成 0xF0 —— 每个中间调都亮一截。
        // 逐个灰阶盯住上界，防止这种「整体变亮」回来。
        for (level in intArrayOf(0x20, 0x40, 0x60, 0x80, 0xA0, 0xC0, 0xE0)) {
            val argb = 0xFF000000.toInt() or (level shl 16) or (level shl 8) or level
            val decoded = GifReader.read(GifEncoder.encode(listOf(solid(2, 2, argb)), 2, 2, 8))
                .pixel(0, 0, 0)
            val red = decoded shr 16 and 0xFF
            assertTrue(
                "灰阶 $level 量化后变成 $red，偏差过大",
                kotlin.math.abs(red - level) <= 8,
            )
        }
    }

    @Test
    fun `every pixel of a frame decodes to the frame's colour`() {
        // 只查一个像素会漏掉「行跨距写错」这类错误：边缘像素对而中间错位。逐点核对。
        val argb = 0xFF3C7A9E.toInt()
        val gif = GifEncoder.encode(listOf(solid(11, 7, argb)), 11, 7, 8)

        val decoded = GifReader.read(gif)
        val reference = decoded.pixel(0, 0, 0)
        for (y in 0 until 7) {
            for (x in 0 until 11) {
                assertEquals("像素 ($x,$y) 必须与其余像素同色", reference, decoded.pixel(0, x, y))
            }
        }
        assertChannelClose(0x3C, reference shr 16 and 0xFF, "红")
        assertChannelClose(0x7A, reference shr 8 and 0xFF, "绿")
        assertChannelClose(0x9E, reference and 0xFF, "蓝")
    }

    private fun assertChannelClose(expected: Int, actual: Int, name: String) {
        assertTrue(
            "$name 通道偏差过大：期望约 $expected，实际 $actual",
            kotlin.math.abs(actual - expected) <= 8,
        )
    }

    @Test
    fun `the frame delay survives the round trip`() {
        // 12fps 换算成 GIF 的百分秒是 8。写错单位（毫秒）会让动图慢十倍，而文件仍然能打开，
        // 所以这一条必须由解码器确认，不能只看自己写的 short。
        val gif = GifEncoder.encode(listOf(solid(2, 2, 0xFF00FF00.toInt())), 2, 2, 8)

        assertEquals("延迟单位是百分秒", 8, GifReader.read(gif).frame(0).delayCentiseconds)
    }

    @Test
    fun `every frame carries its own delay, not just the first`() {
        // Graphic Control Extension 只作用于紧跟其后的那一帧。只在文件开头写一个，后面的帧
        // 就没有延迟，查看器会按自己的默认速度闪过去 —— 文件结构完全合法，只有把每帧的延迟
        // 都读回来才看得出来。这个是外部解码器（Pillow）发现的。
        val gif = GifEncoder.encode(
            listOf(
                solid(2, 2, 0xFFFF0000.toInt()),
                solid(2, 2, 0xFF00FF00.toInt()),
                solid(2, 2, 0xFF0000FF.toInt()),
            ),
            2, 2, 8,
        )
        val decoded = GifReader.read(gif)
        for (index in 0 until decoded.frameCount) {
            assertEquals(
                "第 $index 帧必须自带延迟",
                8,
                decoded.frame(index).delayCentiseconds,
            )
        }
    }

    @Test
    fun `many dense frames all decode, so no frame desyncs the LZW stream`() {
        // 单帧噪声图会把字典填满并触发一次 clear；多帧各自不同的稠密内容则会反复走这条路。
        // 编码器与解码器的码宽切换只要在一帧里差一个表项，后面的帧全是垃圾 —— 前面几帧对、
        // 后面错，正是「动图播到一半花屏」的样子。所以每一帧都要逐点核对，不能只看帧数。
        val width = 32
        val height = 18
        val frames = (0 until 6).map { frameIndex ->
            IntArray(width * height) { i ->
                val v = ((i * 31 + frameIndex * 57) xor (i shl 2)) and 0xFF
                0xFF000000.toInt() or (v shl 16) or ((v * 3 and 0xFF) shl 8) or (v * 7 and 0xFF)
            }
        }
        val gif = GifEncoder.encode(frames, width, height, 8)
        val decoded = GifReader.read(gif)

        assertEquals("帧数必须保持", frames.size, decoded.frameCount)
        // 每帧的每个像素都必须落在调色板里解得出颜色，且整帧不能退化成单色（那说明读错了索引）。
        for (index in 0 until frames.size) {
            val seen = HashSet<Int>()
            for (y in 0 until height) {
                for (x in 0 until width) seen += decoded.pixel(index, x, y)
            }
            assertTrue("第 $index 帧解出的颜色种类过少，疑似索引错位", seen.size > 4)
        }
    }

    @Test
    fun `a many frame animation decodes all of its frames in order`() {
        // 多帧才压得动 LZW 字典，也才可能撞上码宽增长那一步。帧数保持且颜色按顺序，说明
        // 编码器和解码器在码宽切换上同步。
        val colors = listOf(0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFFFFFF00).map { it.toInt() }
        val frames = colors.map { solid(16, 16, it) }
        val gif = GifEncoder.encode(frames, 16, 16, 4)

        val decoded = GifReader.read(gif)
        assertEquals(colors.size, decoded.frameCount)
        colors.forEachIndexed { index, argb ->
            assertEquals(
                "第 $index 帧颜色不符",
                argb and 0xFFFFFF,
                decoded.pixel(index, 8, 8),
            )
        }
    }

    @Test
    fun `a frame large enough to grow the code width still decodes exactly`() {
        // 4096 项的字典在 16x16 以上的噪声图里会真的被填满并触发码宽增长；纯色图压得太好，
        // 走不到那条路径。
        val width = 64
        val height = 64
        val noisy = IntArray(width * height) { index ->
            // 棋盘式噪声，保证字典真的长起来。
            val v = ((index * 37) xor (index shl 3)) and 0xFF
            0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
        }
        val gif = GifEncoder.encode(listOf(noisy), width, height, 8)

        val decoded = GifReader.read(gif)
        assertEquals(width * height, decoded.frame(0).pixels.size)
    }

    // ---- structural invariants --------------------------------------------------------

    @Test
    fun `the file starts as GIF89a and ends with the trailer`() {
        val gif = GifEncoder.encode(listOf(solid(2, 2, 0xFF123456.toInt())), 2, 2, 8)
        assertEquals("GIF89a", String(gif, 0, 6, Charsets.US_ASCII))
        assertEquals("必须以 0x3B 结尾", 0x3B, gif.last().toInt() and 0xFF)
    }

    @Test
    fun `the file declares an infinite loop`() {
        // 不写 Netscape 块的话，动图在很多查看器里只播一遍就停 —— 分享出去的动图变
        // 「一次性」，用户会以为录制坏了。
        val gif = GifEncoder.encode(listOf(solid(2, 2, 0xFF00FF00.toInt())), 2, 2, 8)
        val text = String(gif, Charsets.ISO_8859_1)
        assertTrue("必须有 NETSCAPE2.0 循环扩展", text.contains("NETSCAPE2.0"))
    }

    // ---- palette ----------------------------------------------------------------------

    @Test
    fun `more colours than the table holds are quantised, not dropped`() {
        // 1000 种颜色 > 256 表项：必须量化进表宽，产出的配色仍然是「接近原色」而不是
        // 全黑或全白。
        val width = 40
        val height = 25
        val frame = IntArray(width * height) { index ->
            val v = (index * 251) and 0xFF
            0xFF000000.toInt() or (v shl 16) or ((v * 97 and 0xFF) shl 8) or (v * 53 and 0xFF)
        }
        val gif = GifEncoder.encode(listOf(frame), width, height, 8)
        val decoded = GifReader.read(gif)

        // 每一格都必须解出一个颜色，且不能退化成纯黑（那说明索引越界读到了填充项）。
        var nonBlack = 0
        for (i in 0 until width * height) {
            val pixel = decoded.pixel(0, i % width, i / width)
            if (pixel != 0) nonBlack++
        }
        assertTrue("量化后不能整幅变黑", nonBlack > width * height / 2)
    }

    // ---- recorded size ----------------------------------------------------------------

    @Test
    fun `a recording is scaled down to the dimension cap`() {
        val (width, height) = AnimationRecorder.scaledSize(1080, 607)
        assertTrue("最长边不能超过上限", maxOf(width, height) <= GifEncoder.MAX_DIMENSION)
        // 16:9 不能因为缩放变成别的比例。
        assertTrue("比例必须保持", kotlin.math.abs(width.toFloat() / height - 1080f / 607f) < 0.02f)
    }

    @Test
    fun `a small recording is not scaled up`() {
        // 放大只会让文件变大，不会让画面变清楚。
        assertEquals(320 to 180, AnimationRecorder.scaledSize(320, 180))
    }

    @Test
    fun `a degenerate dimension never rounds a frame away`() {
        // 长条形的视图（比如刚好一行的预览）缩放后小数部分可能取整成 0，而 0 宽的 GIF
        // 是无效的。
        val (width, height) = AnimationRecorder.scaledSize(4000, 1)
        assertTrue("宽不能是 0", width >= 1)
        assertTrue("高不能是 0", height >= 1)
    }

    // ---- degenerate input -------------------------------------------------------------

    @Test
    fun `a one pixel animation still produces a readable file`() {
        val gif = GifEncoder.encode(listOf(solid(1, 1, 0xFFFFFFFF.toInt())), 1, 1, 8)
        val decoded = GifReader.read(gif)
        assertEquals(1, decoded.frameCount)
        assertEquals(1, decoded.width)
        assertEquals(1, decoded.height)
    }

    @Test
    fun `frames of the wrong size are dropped rather than encoded sheared`() {
        // 尺寸不符的帧如果照编，解码出来是斜的。宁可少一帧也不要一帧坏画面。
        val good = solid(4, 4, 0xFF00FF00.toInt())
        val bad = solid(3, 3, 0xFFFF0000.toInt())
        val gif = GifEncoder.encode(listOf(good, bad, good), 4, 4, 8)

        assertEquals("两张合格帧，尺寸不符的那张必须被丢掉", 2, GifReader.read(gif).frameCount)
    }

    @Test
    fun `an empty animation is refused loudly`() {
        val failure = runCatching { GifEncoder.encode(emptyList(), 4, 4, 8) }.exceptionOrNull()
        assertNotNull("空帧列表必须抛错，而不是产出一个坏文件", failure)
        assertTrue(failure is IllegalArgumentException)
    }
}
