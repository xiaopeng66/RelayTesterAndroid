package com.relaytester.app

import androidx.compose.ui.unit.dp
import com.relaytester.app.core.model.TestStatus
import com.relaytester.app.feature.tester.copyAlignmentOffset
import com.relaytester.app.feature.tester.statusChipLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The result row's action rail has one automatable invariant: where the copy icon ends up.
 *
 * The rail's last cell is a fixed [SLOT]-wide box flush with the content's right edge, and
 * the copy icon inside it is nudged by `copyAlignmentOffset` so its centre lands on the
 * status pill's text centre — the place the user asked for. The label width the caller
 * measures is the only input that varies with the font scale, so both ends of the range
 * are pinned here; the rest of the geometry (that the copy really does not move between a
 * success, a failed and a pending row) is measured on the device, the same way the earlier
 * rounds measured this rail.
 */
class TesterActionRailTest {

    /** One cell of the rail; identical to `RESULT_ACTION_SLOT` in the screen. */
    private val slot = 40.dp

    /** The pill's own horizontal padding; identical to `RESULT_PILL_INSET`. */
    private val inset = 8.dp

    @Test
    fun `the copy offset puts the icon centre on the pill text centre`() {
        // 1.0×: the measured layout width of 「可用」 is 22dp.
        val offset = copyAlignmentOffset(slot, inset, 22.dp)

        assertEquals(1.dp, offset)
        // Both measured from the content's right edge: the cell centre sits `slot/2` in, and
        // a positive offset moves the icon towards that edge, so the icon's centre is
        // `slot/2 - offset` — which has to be the pill text's centre, `inset + label/2`.
        assertEquals(inset + 22.dp / 2, slot / 2 - offset)
    }

    @Test
    fun `a doubled font scale moves the copy left and still keeps the icon inside the cell`() {
        val offset = copyAlignmentOffset(slot, inset, 44.dp)

        assertEquals((-10).dp, offset)
        assertEquals(inset + 44.dp / 2, slot / 2 - offset)

        // The icon is 16dp wide and its centre is `slot/2 - offset` from the right edge:
        // both its edges have to stay inside the 40dp cell, otherwise it draws over the
        // neighbouring chips or gets clipped by the button's circular state layer.
        val centreFromRight = slot / 2 - offset
        assertTrue(centreFromRight + 8.dp <= slot)
        assertTrue(centreFromRight - 8.dp >= 0.dp)
    }

    @Test
    fun `every two-character status shares the pill label the copy is aligned to`() {
        // The offset is computed once, from 「可用」. That is only right because the other
        // statuses that can appear next to a copy button are also two characters: same
        // word, same rendered width, so one x for all of them. 「等待中」 is the one
        // exception and it is pinned here so a future label change is a test failure
        // rather than an invisible misalignment on the device.
        assertEquals("可用", statusChipLabel(TestStatus.SUCCESS, isFetchedOnly = false))
        assertEquals("失败", statusChipLabel(TestStatus.FAILED, isFetchedOnly = false))
        assertEquals("待测", statusChipLabel(TestStatus.PENDING, isFetchedOnly = true))
        assertEquals("等待中", statusChipLabel(TestStatus.PENDING, isFetchedOnly = false))
    }
}
