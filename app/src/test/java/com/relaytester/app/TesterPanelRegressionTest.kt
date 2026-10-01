package com.relaytester.app

import com.relaytester.app.core.model.ApiResult
import com.relaytester.app.core.model.BalanceHttpMethod
import com.relaytester.app.core.model.BalanceQueryResult
import com.relaytester.app.core.model.BalanceQueryTemplate
import com.relaytester.app.core.model.BalanceSnapshot
import com.relaytester.app.core.model.ModelCatalogEntry
import com.relaytester.app.core.model.ModelSource
import com.relaytester.app.core.model.ModelTestResult
import com.relaytester.app.core.model.SupplierProfile
import com.relaytester.app.core.model.TestSettings
import com.relaytester.app.core.model.TestStatus
import com.relaytester.app.core.network.BalanceApi
import com.relaytester.app.core.network.RelayApi
import com.relaytester.app.core.security.SecretStore
import com.relaytester.app.core.storage.SupplierStore
import com.relaytester.app.core.storage.SupplierStoreState
import com.relaytester.app.feature.tester.TesterUiState
import com.relaytester.app.feature.tester.TesterViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Covers the tester panel behaviour the concurrency test does not: what a stopped run
 * leaves behind, and how a supplier's live model directory is judged.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TesterPanelRegressionTest {
    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---- Starting a run is atomic ----------------------------------------

    @Test
    fun `a double tap on start does not launch two rounds`() {
        // The guard reads `isRunning`, but that is published only after the draft has
        // been persisted — a suspension point. The second tap of a double tap lands
        // inside that window, so without an atomic start both rounds dispatch and both
        // write their own completion state over the other's.
        val api = SlowApi(listOf("model-a"), holdMs = 60)
        val subject = viewModel(api)
        seedRunnableState(subject)

        subject.startTest()
        subject.startTest()
        runBlocking { delay(400) }

        assertEquals("双击只允许一轮", 1, api.calls.get())
    }
    // ---- Cancelling a run settles its rows -------------------------------

    @Test
    fun `cancelling a run leaves no row still claiming to be running`() {
        val subject = viewModel(ModelsApi(listOf("model-a")))
        setResultsForTest(
            subject,
            listOf(
                ModelTestResult("model-a", TestStatus.PENDING),
                ModelTestResult("model-b", TestStatus.SUCCESS, latencyMs = 5),
            ),
        )

        subject.cancelRun()

        val results = subject.uiState.value.results
        assertTrue(
            "取消后不得留下仍显示“进行中”的行",
            results.none { it.status == TestStatus.PENDING },
        )
        val cancelled = results.first { it.model == "model-a" }
        assertEquals(TestStatus.FAILED, cancelled.status)
        assertEquals("已取消", cancelled.error?.message)
        // A row that already had a verdict keeps it: cancelling is not a re-test.
        assertEquals(TestStatus.SUCCESS, results.first { it.model == "model-b" }.status)
    }

    // ---- Judging a supplier's model directory ----------------------------

    @Test
    fun `an empty directory is not evidence that models were delisted`() {
        // An endpoint that answers 200 with `[]` looks the same as one whose list
        // failed to populate. Judging it would mark every model of that site as gone.
        val subject = viewModel(ModelsApi(emptyList()))
        seedEntry(subject, ModelSource("supplier-1", "model-a"))

        subject.refreshModelCatalogEntry(ENTRY_ID)
        awaitCatalogIdle(subject)

        val state = subject.uiState.value
        assertTrue("空目录不得判为已下架", state.catalogMissingSources.isEmpty())
        assertFalse(
            "不得声称模型已不存在",
            state.message?.contains("已不存在") == true,
        )
    }

    @Test
    fun `a model absent from a non-empty directory is still reported as delisted`() {
        // The empty-directory rule must not disable the check altogether.
        val subject = viewModel(ModelsApi(listOf("model-b")))
        seedEntry(subject, ModelSource("supplier-1", "model-a"))

        subject.refreshModelCatalogEntry(ENTRY_ID)
        awaitCatalogIdle(subject)

        val state = subject.uiState.value
        assertTrue(
            "非空目录里找不到的模型仍须报为已下架",
            state.catalogMissingSources.isNotEmpty(),
        )
        assertTrue(state.message?.contains("已不存在") == true)
    }

    // ---- The balance fan-out is bounded ----------------------------------

    @Test
    fun `querying every balance runs at most eight sites at once`() {
        // One request per supplier, but the fan-out used to be unbounded: with a long
        // supplier list it opened a socket per site and queued them all on the same
        // small IO pool. Twelve sites held open long enough to overlap prove the cap.
        val api = TrackingBalanceApi(holdMs = 250)
        val subject = viewModel(balanceApi = api)
        seedBalanceState(subject, suppliers = 12)

        subject.queryAllBalances()
        runBlocking { delay(1_500) }

        assertEquals("每个站点恰好查询一次", 12, api.calls.get())
        assertTrue(
            "批量余额查询的在途请求数不得超过 8",
            api.maxInFlight <= 8,
        )
        assertTrue("上限应当真的被触到（否则判据无意义）", api.maxInFlight >= 2)
        assertFalse(subject.balanceUiState.value.isQuerying)
    }

    @Test
    fun `a double tap on query balance does not launch two queries`() {
        // The same publish-window race as startTest: isQuerying is published only
        // after persistDraft() has suspended on the Keystore write, so the second
        // tap of a double tap lands before any guard the flag could offer.
        val api = TrackingBalanceApi(holdMs = 200)
        val subject = viewModel(balanceApi = api)
        seedBalanceState(subject, suppliers = 1)

        subject.queryBalance()
        subject.queryBalance()
        runBlocking { delay(900) }

        assertEquals("双击只允许一次查询", 1, api.calls.get())
        assertFalse(subject.balanceUiState.value.isQuerying)
    }

    // ---- Harness ---------------------------------------------------------

    private fun viewModel(
        relayApi: RelayApi = ModelsApi(emptyList()),
        balanceApi: BalanceApi = BalanceApi(),
    ): TesterViewModel = TesterViewModel(
        supplierStore = SilentStore(),
        secretStore = object : SecretStore {
            override fun put(value: String): String = "secret-1"
            override fun get(secretId: String): String = "api-key"
            override fun delete(secretId: String) = Unit
        },
        relayApiFactory = { relayApi },
        balanceApiFactory = { balanceApi },
        skipRestore = true,
    )

    private fun seedEntry(subject: TesterViewModel, source: ModelSource) {
        setProfileForTest(subject)
        val catalogField = TesterViewModel::class.java.getDeclaredField("modelCatalog")
        catalogField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val catalog = catalogField.get(subject) as MutableList<ModelCatalogEntry>
        catalog += ModelCatalogEntry(id = ENTRY_ID, name = "entry", sources = listOf(source))
        val stateField = TesterViewModel::class.java.getDeclaredField("_uiState")
        stateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(subject) as MutableStateFlow<TesterUiState>
        state.value = state.value.copy(
            isInitializing = false,
            modelCatalog = catalog.toList(),
        )
    }

    private fun setProfileForTest(subject: TesterViewModel) {
        val field = TesterViewModel::class.java.getDeclaredField("profiles")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val profiles = field.get(subject) as MutableList<SupplierProfile>
        profiles += SupplierProfile(
            id = "supplier-1",
            name = "supplier-1",
            baseUrl = "https://relay.test/v1",
            protocol = com.relaytester.app.core.model.RelayProtocol.CHAT_COMPLETIONS,
            apiKeySecretId = "secret-1",
            models = listOf("model-a"),
            testSettings = TestSettings(),
        )
    }

    private fun setResultsForTest(subject: TesterViewModel, results: List<ModelTestResult>) {
        val field = TesterViewModel::class.java.getDeclaredField("_uiState")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val state = field.get(subject) as MutableStateFlow<TesterUiState>
        state.value = state.value.copy(isInitializing = false, results = results)
    }

    /**
     * A panel with [suppliers] configured sites (all pointing at a template that
     * references no credentials), the first one active with an editable draft — the
     * dirty API key makes persistDraft() really suspend, which is the window the
     * balance start guard has to close.
     */
    private fun seedBalanceState(subject: TesterViewModel, suppliers: Int) {
        val template = BalanceQueryTemplate(
            id = "tpl-1",
            name = "tpl",
            description = "",
            method = BalanceHttpMethod.GET,
            endpointTemplate = "{{baseUrl}}/balance",
            headers = emptyList(),
            availablePath = "data.available",
        )
        val profilesField = TesterViewModel::class.java.getDeclaredField("profiles")
        profilesField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val profiles = profilesField.get(subject) as MutableList<SupplierProfile>
        (1..suppliers).mapTo(profiles) { index ->
            SupplierProfile(
                id = "supplier-$index",
                name = "supplier-$index",
                baseUrl = "https://relay.test/v1",
                protocol = com.relaytester.app.core.model.RelayProtocol.CHAT_COMPLETIONS,
                apiKeySecretId = null,
                models = listOf("model-a"),
                testSettings = TestSettings(),
            )
        }
        val templatesField = TesterViewModel::class.java.getDeclaredField("balanceTemplates")
        templatesField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (templatesField.get(subject) as MutableList<BalanceQueryTemplate>) += template

        val stateField = TesterViewModel::class.java.getDeclaredField("_uiState")
        stateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(subject) as MutableStateFlow<TesterUiState>
        state.value = state.value.copy(
            isInitializing = false,
            draft = com.relaytester.app.feature.tester.SupplierDraft
                .from(profiles.first(), "api-key")
                .copy(apiKeyDirty = true),
            activeSupplierId = profiles.first().id,
            suppliers = profiles.toList(),
        )
        // BalanceUiState boots as "initializing" until a real restore clears it;
        // skipRestore never does, and balanceOperationBlocked() would refuse to run.
        val balanceStateField = TesterViewModel::class.java.getDeclaredField("_balanceUiState")
        balanceStateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val balanceState = balanceStateField.get(subject) as MutableStateFlow<com.relaytester.app.feature.tester.BalanceUiState>
        balanceState.value = balanceState.value.copy(isInitializing = false)
        // persistBalanceCredentials reads the ViewModel's own field, not the UI state.
        val activeIdField = TesterViewModel::class.java.getDeclaredField("activeSupplierId")
        activeIdField.isAccessible = true
        activeIdField.set(subject, profiles.first().id)
    }

    /** A panel with one real supplier, one ticked model, and an editable draft. */
    private fun seedRunnableState(subject: TesterViewModel) {
        val profile = SupplierProfile(
            id = "supplier-1",
            name = "supplier-1",
            baseUrl = "https://relay.test/v1",
            protocol = com.relaytester.app.core.model.RelayProtocol.CHAT_COMPLETIONS,
            apiKeySecretId = null,
            models = listOf("model-a"),
            // No inter-request delay: the runner's defaults (500–2000 ms) are scheduled
            // on the test dispatcher and would park the round before its first request.
            testSettings = TestSettings(delayMinMs = 0, delayMaxMs = 0, batchPauseMs = 0),
        )
        val field = TesterViewModel::class.java.getDeclaredField("profiles")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(subject) as MutableList<SupplierProfile>) += profile
        val stateField = TesterViewModel::class.java.getDeclaredField("_uiState")
        stateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val state = stateField.get(subject) as MutableStateFlow<TesterUiState>
        state.value = state.value.copy(
            isInitializing = false,
            // Dirty, so persisting the draft really suspends on the Keystore write:
            // that suspension is the window the start guard has to close.
            draft = com.relaytester.app.feature.tester.SupplierDraft
                .from(profile, "api-key")
                .copy(apiKeyDirty = true),
            activeSupplierId = profile.id,
            selectedModels = setOf("model-a"),
            suppliers = listOf(profile),
        )
    }

    private fun awaitCatalogIdle(subject: TesterViewModel) {
        runBlocking {
            var waited = 0L
            while (subject.uiState.value.catalogRefreshingEntryIds.isNotEmpty() && waited < 5_000) {
                delay(10)
                waited += 10
            }
        }
    }

    private class SilentStore : SupplierStore(null) {
        override suspend fun read() = SupplierStoreState(emptyList(), null)
        override suspend fun save(state: SupplierStoreState) = Unit
    }

    /** A relay whose model directory is exactly [models]. */
    private class ModelsApi(private val models: List<String>) : RelayApi() {
        override suspend fun fetchModels(
            profile: SupplierProfile,
            apiKey: String,
            timeoutSeconds: Int,
        ): ApiResult<List<String>> = ApiResult.Success(models)
    }

    /** A relay that answers every test after [holdMs], so a round stays in flight. */
    private class SlowApi(
        private val models: List<String>,
        private val holdMs: Long,
    ) : RelayApi() {
        val calls = java.util.concurrent.atomic.AtomicInteger(0)

        override suspend fun fetchModels(
            profile: SupplierProfile,
            apiKey: String,
            timeoutSeconds: Int,
        ): ApiResult<List<String>> = ApiResult.Success(models)

        override suspend fun test(
            profile: SupplierProfile,
            apiKey: String,
            model: String,
            prompt: String,
            maxTokens: Int,
            timeoutSeconds: Int,
        ): ModelTestResult {
            calls.incrementAndGet()
            delay(holdMs)
            return ModelTestResult(model = model, status = TestStatus.SUCCESS)
        }
    }

    /**
     * A balance endpoint that answers after [holdMs] and records how many queries were
     * in flight at once — the only way to observe the fan-out's concurrency cap.
     */
    private class TrackingBalanceApi(private val holdMs: Long) : BalanceApi() {
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        private val inFlight = java.util.concurrent.atomic.AtomicInteger(0)
        private val peak = java.util.concurrent.atomic.AtomicInteger(0)

        val maxInFlight: Int get() = peak.get()

        override suspend fun query(
            profile: SupplierProfile,
            apiKey: String,
            accessToken: String,
            userId: String,
            template: BalanceQueryTemplate,
        ): BalanceQueryResult {
            calls.incrementAndGet()
            val now = inFlight.incrementAndGet()
            peak.updateAndGet { previous -> maxOf(previous, now) }
            delay(holdMs)
            inFlight.decrementAndGet()
            return BalanceQueryResult.Success(
                BalanceSnapshot(
                    supplierId = profile.id,
                    templateId = template.id,
                    templateName = template.name,
                    availableRaw = 1.0,
                    unitLabel = "USD",
                    scaleDivisor = 1.0,
                    checkedAt = 0L,
                    latencyMs = 1,
                ),
            )
        }
    }

    private companion object {
        const val ENTRY_ID = "entry-1"
    }
}
