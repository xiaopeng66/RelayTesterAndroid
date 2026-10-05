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
 * The order that matters is the profiles list's own, so what is pinned here is the whole
 * road: which slot a move lands in, that the state and the stored list agree, that a
 * refused write restores the order the user had, and that a drag event that no longer
 * resolves against the live list does nothing instead of moving something else.
 *
 * The panel itself (the drag threshold, the animation) is measured on the device; a JVM
 * test has no pointer and no layout.
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

    // ---- The move lands where it says --------------------------------------

    @Test
    fun `a move takes the slot of the supplier it names`() {
        val store = RecordingStore()
        val subject = viewModel(store, "a", "b", "c")

        subject.moveSupplier(supplierId = "c", targetId = "a")

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

        subject.moveSupplier(supplierId = "a", targetId = "c")

        assertEquals(listOf("b", "c", "a"), subject.uiState.value.suppliers.map { it.id })
        assertEquals(listOf("b", "c", "a"), awaitSaved(store).map { it.id })
    }

    @Test
    fun `a move onto itself changes nothing and writes nothing`() {
        val store = RecordingStore()
        val subject = viewModel(store, "a", "b")

        subject.moveSupplier(supplierId = "a", targetId = "a")
        settle()

        assertEquals(listOf("a", "b"), subject.uiState.value.suppliers.map { it.id })
        assertFalse("原地移动不该落盘", store.saveCount > 0)
    }

    @Test
    fun `a move naming an id that is gone does nothing`() {
        // The drag resolves its target against the list when the event arrives. If the
        // supplier was deleted (or the list reloaded) in between, the honest outcome is
        // to do nothing — a positional move would shuffle rows the user never touched.
        val store = RecordingStore()
        val subject = viewModel(store, "a", "b")

        subject.moveSupplier(supplierId = "a", targetId = "gone")
        subject.moveSupplier(supplierId = "gone", targetId = "a")
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

        subject.moveSupplier(supplierId = "c", targetId = "a")
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
        // a failed swap must not corrupt the following one.
        val store = RecordingStore()
        val subject = viewModel(store, "a", "b", "c")
        store.failSaves = true
        subject.moveSupplier(supplierId = "c", targetId = "a")
        settle()
        store.failSaves = false

        subject.moveSupplier(supplierId = "b", targetId = "a")

        assertEquals(listOf("b", "a", "c"), subject.uiState.value.suppliers.map { it.id })
        assertEquals(listOf("b", "a", "c"), awaitSaved(store).map { it.id })
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

        subject.moveSupplier(supplierId = "c", targetId = "a")
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
        subject.moveSupplier(supplierId = "c", targetId = "a")

        assertEquals(listOf("c", "a", "b"), awaitSaved(store).map { it.id })
    }

    // ---- Harness -----------------------------------------------------------

    /** Waits for the write that runs on the real IO dispatcher to land. */
    private fun awaitSaved(store: RecordingStore): List<SupplierProfile> =
        kotlinx.coroutines.runBlocking {
            withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    while (store.saveCount == 0) {
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
