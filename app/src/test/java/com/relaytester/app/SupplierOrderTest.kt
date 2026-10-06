package com.relaytester.app

import com.relaytester.app.core.model.RelayProtocol
import com.relaytester.app.core.model.SupplierProfile
import com.relaytester.app.core.model.TestSettings
import com.relaytester.app.core.network.BalanceApi
import com.relaytester.app.core.network.RelayApi
import com.relaytester.app.core.security.SecretStore
import com.relaytester.app.core.storage.SupplierStore
import com.relaytester.app.core.storage.SupplierStoreState
import com.relaytester.app.feature.tester.TesterUiState
import com.relaytester.app.feature.tester.TesterViewModel
import com.relaytester.app.feature.tester.supplierSlotUnderFinger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * Reordering the suppliers.
 *
 * The primitive is "put this supplier in the slot at index k", and that being a **position**
 * rather than a partner to swap with is the fix for the reported defect, so it is pinned
 * here rather than described: the drag resolves its target from where the finger is, and a
 * finger that has not moved names the same slot again. Under a positional primitive that
 * repeat is a no-op; under the relative "swap with that row" this replaced it named the
 * neighbour the row had just left and moved it straight back — two rows bouncing for as long
 * as the finger stayed put, which is what the report showed.
 *
 * What the file pins is therefore: which slot a move lands in, that a repeat of the same
 * target cannot undo it, that the state and the stored list agree, that a refused write
 * restores the order the user had, and that a target outside the list is clamped instead of
 * throwing.
 *
 * The panel itself (the finger-to-slot conversion, the animation) is a source contract in
 * DialogWindowContractTest and a device measurement; a JVM test has no pointer and no layout.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SupplierOrderTest {
    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---- The move lands in the slot it names ------------------------------

    @Test
    fun `a move puts the row in the slot it names`() {
        val store = RecordingStore()
        val subject = viewModel(store, "a", "b", "c")

        subject.moveSupplierTo(supplierId = "c", targetIndex = 0)

        assertEquals(listOf("c", "a", "b"), subject.uiState.value.suppliers.map { it.id })
        // 落盘跑在 Dispatchers.IO 上（真调度器），测试调度器不会等它；与
        // TesterPersistenceRaceTest 同一套写法：给一个真实的时限，等它真的写完。
        assertEquals(
            "落盘的顺序要和界面上看到的一致",
            listOf("c", "a", "b"),
            awaitSaved(store).map { it.id },
        )
    }

    @Test
    fun `moving down is the same primitive as moving up`() {
        val store = RecordingStore()
        val subject = viewModel(store, "a", "b", "c")

        subject.moveSupplierTo(supplierId = "a", targetIndex = 2)

        assertEquals(listOf("b", "c", "a"), subject.uiState.value.suppliers.map { it.id })
        assertEquals(listOf("b", "c", "a"), awaitSaved(store).map { it.id })
    }

    // ---- The bounce cannot happen any more --------------------------------

    @Test
    fun `a repeated event naming the same slot cannot move the row back`() {
        // 这正是用户报的缺陷的回归用例。拖动时每个指针事件都算一次「指尖在哪一格」，手指停住
        // 时这些事件反复报同一格；换位本身会让界面重排，落后于重排的事件也会报同一格。位置语义
        // 下「在第 0 格」重复说出就是不动；原来的相对语义（「和那一行换」）会把同一句话说成
        // 「回到刚才那行去」，两行于是来回替换。
        val store = RecordingStore()
        val subject = viewModel(store, "a", "b", "c")

        subject.moveSupplierTo(supplierId = "c", targetIndex = 0)
        assertEquals(listOf("c", "a", "b"), subject.uiState.value.suppliers.map { it.id })
        awaitSaved(store)
        val savesAfterFirstMove = store.saveCount

        // 手指停在原地时接连到来的一串事件。
        repeat(8) { subject.moveSupplierTo(supplierId = "c", targetIndex = 0) }
        settle()

        assertEquals(
            "同一格被重复点名时行必须留在那一格：两行来回替换就是从这里来的",
            listOf("c", "a", "b"),
            subject.uiState.value.suppliers.map { it.id },
        )
        assertEquals("停在原地的重复事件不该再落盘", savesAfterFirstMove, store.saveCount)
    }

    @Test
    fun `a move onto the slot the row already holds changes nothing and writes nothing`() {
        val store = RecordingStore()
        val subject = viewModel(store, "a", "b")

        subject.moveSupplierTo(supplierId = "a", targetIndex = 0)
        settle()

        assertEquals(listOf("a", "b"), subject.uiState.value.suppliers.map { it.id })
        assertFalse("原地移动不该落盘", store.saveCount > 0)
    }

    @Test
    fun `an index outside the list is clamped to its ends`() {
        // 拖到列表外面就是「放到最前／最后」：换算越出可见行是常事，接受它并夹住比让调用方
        // 自己保证范围更省事，也免得越界直接崩在 UI 事件里。
        val store = RecordingStore()
        val subject = viewModel(store, "a", "b", "c")

        subject.moveSupplierTo(supplierId = "a", targetIndex = 99)
        settle()
        assertEquals(listOf("b", "c", "a"), subject.uiState.value.suppliers.map { it.id })

        subject.moveSupplierTo(supplierId = "a", targetIndex = -3)
        settle()
        assertEquals(listOf("a", "b", "c"), subject.uiState.value.suppliers.map { it.id })
    }

    @Test
    fun `a move naming an id that is gone does nothing`() {
        // The drag resolves its target against the list when the event arrives. If the
        // supplier was deleted (or the list reloaded) in between, the honest outcome is
        // to do nothing — a positional move would shuffle rows the user never touched.
        val store = RecordingStore()
        val subject = viewModel(store, "a", "b")

        subject.moveSupplierTo(supplierId = "gone", targetIndex = 1)
        settle()

        assertEquals(listOf("a", "b"), subject.uiState.value.suppliers.map { it.id })
        assertFalse("无解的目标不该落盘", store.saveCount > 0)
    }

    // ---- A refused write puts the order back -------------------------------

    @Test
    fun `a move whose write fails is rolled back`() {
        val store = RecordingStore()
        val subject = viewModel(store, "a", "b", "c")
        store.failSaves = true

        subject.moveSupplierTo(supplierId = "c", targetIndex = 0)
        settle()

        assertEquals(
            "落盘失败后顺序必须回到用户排之前的样子",
            listOf("a", "b", "c"),
            subject.uiState.value.suppliers.map { it.id },
        )
    }

    @Test
    fun `a later move after a failed one still works`() {
        // The rollback restores the list, not a copy that the next move would append to:
        // a failed move must not corrupt the following one.
        val store = RecordingStore()
        val subject = viewModel(store, "a", "b", "c")
        store.failSaves = true
        subject.moveSupplierTo(supplierId = "c", targetIndex = 0)
        settle()
        store.failSaves = false

        // The refused save already consumed one count; wait for the one after it. Without the
        // baseline this assertion races the IO write and reads the empty list the refused
        // save left behind.
        val savesBefore = store.saveCount
        subject.moveSupplierTo(supplierId = "b", targetIndex = 0)

        assertEquals(listOf("b", "a", "c"), subject.uiState.value.suppliers.map { it.id })
        assertEquals(listOf("b", "a", "c"), awaitSaved(store, savesBefore).map { it.id })
    }

    // ---- Reordering is not allowed to race a run ---------------------------

    @Test
    fun `a running test keeps the supplier order from being rearranged`() {
        // Reordering rewrites the same stored profile list a run reads its targets from,
        // and the panel is reachable while a run is going. The move is refused outright
        // (like every other mutation in this view model) rather than queued behind it.
        val store = RecordingStore()
        val subject = viewModel(store, "a", "b", "c")
        setRunningForTest(subject)

        subject.moveSupplierTo(supplierId = "c", targetIndex = 0)
        settle()

        assertEquals(
            "运行中不得改动供应商顺序",
            listOf("a", "b", "c"),
            subject.uiState.value.suppliers.map { it.id },
        )
        assertFalse("运行中被拒绝的移动不该落盘", store.saveCount > 0)
    }

    // ---- A settled order survives a reload ---------------------------------

    @Test
    fun `the stored order is what a fresh read returns`() {
        val store = RecordingStore()
        val subject = viewModel(store, "a", "b", "c")
        subject.moveSupplierTo(supplierId = "c", targetIndex = 0)

        assertEquals(listOf("c", "a", "b"), awaitSaved(store).map { it.id })
    }

    // ---- Where the finger is, in slot numbers ------------------------------

    /**
     * The finger-to-slot mapping, with the numbers the device produced.
     *
     * Real defect this pins: the list reports item offsets from the start of its content,
     * but the finger was measured against the list's top edge, and the two differ by the
     * list's leading content padding — 8dp here, published as `viewportStartOffset = -21px`.
     * Leaving that term out judged the finger one padding too low, so every boundary fired
     * 21px early: on the device a finger resting on a slot boundary moved the row one slot
     * past it. These are the measured coordinates (root px, density 2.625, row 147px, pitch
     * 163px, 8dp = 21px), not invented ones.
     */
    @Test
    fun `the slot under the finger is measured from the same origin the list reports`() {
        // Row 0 sits at root y 573..720, the list's own top edge is 552, so the content
        // starts 21px below it: viewportStartOffset = -21.
        val rowTop = 573f
        val rowHeight = 147
        val pitch = 163f
        val listTop = 552f

        fun slotAt(fingerRootY: Float, last: Int = 6): Int = supplierSlotUnderFinger(
            fingerRootY = fingerRootY,
            listTopInRoot = listTop,
            viewportStartOffset = -21,
            anchorIndex = 0,
            anchorOffset = 0,
            anchorSize = rowHeight,
            pitch = pitch,
            lastIndex = last,
        )

        // The exact device reading: finger at root 809 while row 0 was the anchor -> slot 1.
        assertEquals(1, slotAt(809f))
        // Each row's centre must name that row, and nothing in between may drift.
        for (slot in 0..6) {
            val centre = rowTop + slot * pitch + rowHeight / 2f
            assertEquals("指尖落在第 $slot 行中心", slot, slotAt(centre))
        }
        // A finger resting just short of a boundary still names the row it is over. This is
        // the assertion the missing term failed: rows are 147px with a 16px gap, so the true
        // midline between row k and k+1 sits 155px below row k's top, and judging the finger
        // 21px too low pushed anything within 21px above that line into the next slot.
        val gap = pitch - rowHeight
        for (slot in 0..5) {
            val midline = rowTop + slot * pitch + rowHeight + gap / 2f
            assertEquals("指尖压在第 $slot / ${slot + 1} 行之间的中线偏上侧", slot, slotAt(midline - 4f))
            assertEquals("指尖压在中线偏下侧", slot + 1, slotAt(midline + 4f))
        }
    }

    @Test
    fun `a finger past either end of the list names the end slot`() {
        fun slotAt(fingerRootY: Float): Int = supplierSlotUnderFinger(
            fingerRootY = fingerRootY,
            listTopInRoot = 552f,
            viewportStartOffset = -21,
            anchorIndex = 0,
            anchorOffset = 0,
            anchorSize = 147,
            pitch = 163f,
            lastIndex = 6,
        )
        assertEquals("拖到列表上方＝放到最前", 0, slotAt(-5000f))
        assertEquals("拖到列表下方＝放到最后", 6, slotAt(5000f))
    }

    @Test
    fun `a scrolled list is judged against the anchor the layout reports`() {
        // Landscape with 7 suppliers: the window shows four rows and the anchor is not the
        // first. Device reading: anchor index 1 at offset -52 (the first visible row is
        // already half scrolled away), list top 205, viewportStartOffset -21, finger at
        // root 290 -> slot 2. A mapping that ignored the anchor's own offset would name a
        // slot relative to the window instead of to the list.
        fun slotAt(fingerRootY: Float, viewportStartOffset: Int): Int = supplierSlotUnderFinger(
            fingerRootY = fingerRootY,
            listTopInRoot = 205f,
            viewportStartOffset = viewportStartOffset,
            anchorIndex = 1,
            anchorOffset = -52,
            anchorSize = 147,
            pitch = 163f,
            lastIndex = 6,
        )
        assertEquals(2, slotAt(450f, -21))

        // And the regression itself: this finger sits a few px above the boundary between
        // slots 1 and 2 in the content frame, and the term is what decides which side of the
        // line the code thinks it is. Ignore the padding and the same finger is judged 21px
        // lower, i.e. past the line — the off-by-one whose device symptom was a row moved one
        // slot too far when the finger rested near a boundary.
        val justAboveTheLine = 324f
        assertEquals("带上内边距时指尖仍属第 1 格", 1, slotAt(justAboveTheLine, -21))
        assertEquals("漏掉内边距时同一指尖被算成第 2 格（这就是那个缺陷）", 2, slotAt(justAboveTheLine, 0))
    }

    // ---- Harness -----------------------------------------------------------

    /**
     * Waits for a write beyond [before] to land on the real IO dispatcher.
     *
     * The baseline matters: a save that was refused has already bumped the count by the time
     * this is called, so waiting for "any save" would return the previous write's contents —
     * which is exactly how the failed-then-retry test read an empty list under load.
     */
    private fun awaitSaved(store: RecordingStore, before: Int = 0): List<SupplierProfile> =
        kotlinx.coroutines.runBlocking {
            withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    while (store.saveCount <= before) {
                        kotlinx.coroutines.delay(5)
                    }
                    store.saved
                }
            }
        }

    /** Lets the coroutine that would write run to completion, so "nothing was written"
     *  is a fact rather than a race the assertion won. */
    private fun settle() {
        kotlinx.coroutines.runBlocking {
            withContext(Dispatchers.Default) { kotlinx.coroutines.delay(50) }
        }
    }

    /**
     * A store that keeps what it was last asked to write, and can refuse to write.
     *
     * Recording the whole state (not just the order) is what lets the assertions read the
     * stored list the way the app's own `read()` would.
     */
    private class RecordingStore(var failSaves: Boolean = false) : SupplierStore(null) {
        var saved: List<SupplierProfile> = emptyList()
            private set
        var saveCount = 0
            private set

        override suspend fun read() = SupplierStoreState(saved, saved.firstOrNull()?.id)

        override suspend fun save(state: SupplierStoreState) {
            saveCount += 1
            if (failSaves) throw IllegalStateException("磁盘写失败")
            saved = state.suppliers
        }
    }

    private fun viewModel(store: SupplierStore, vararg ids: String): TesterViewModel {
        val subject = TesterViewModel(
            supplierStore = store,
            secretStore = object : SecretStore {
                override fun put(value: String): String = "secret-1"
                override fun get(secretId: String): String = "api-key"
                override fun delete(secretId: String) = Unit
            },
            relayApiFactory = { RelayApi() },
            balanceApiFactory = { BalanceApi() },
            skipRestore = true,
        )
        seedProfiles(subject, ids.toList())
        return subject
    }

    private fun seedProfiles(subject: TesterViewModel, ids: List<String>) {
        val profilesField = TesterViewModel::class.java.getDeclaredField("profiles")
        profilesField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val profiles = profilesField.get(subject) as MutableList<SupplierProfile>
        ids.forEach { id ->
            profiles += SupplierProfile(
                id = id,
                name = id,
                baseUrl = "https://relay.test/v1",
                protocol = RelayProtocol.CHAT_COMPLETIONS,
                apiKeySecretId = "secret-$id",
                models = listOf("model-$id"),
                testSettings = TestSettings(),
            )
        }
        val stateField = TesterViewModel::class.java.getDeclaredField("_uiState")
        stateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(subject) as MutableStateFlow<TesterUiState>
        state.value = state.value.copy(
            isInitializing = false,
            suppliers = profiles.toList(),
            activeSupplierId = ids.firstOrNull(),
        )
    }

    /** Puts the panel in the state a run leaves it in, which is what the guard reads. */
    private fun setRunningForTest(subject: TesterViewModel) {
        val stateField = TesterViewModel::class.java.getDeclaredField("_uiState")
        stateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(subject) as MutableStateFlow<TesterUiState>
        state.value = state.value.copy(isRunning = true)
    }
}
