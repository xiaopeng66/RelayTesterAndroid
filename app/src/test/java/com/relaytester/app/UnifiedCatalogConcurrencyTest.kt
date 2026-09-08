package com.relaytester.app

import com.relaytester.app.core.model.ApiResult
import com.relaytester.app.core.model.ModelCatalogEntry
import com.relaytester.app.core.model.ModelSource
import com.relaytester.app.core.model.ModelTestResult
import com.relaytester.app.core.model.SupplierProfile
import com.relaytester.app.core.model.TestStatus
import com.relaytester.app.core.network.BalanceApi
import com.relaytester.app.core.network.RelayApi
import com.relaytester.app.core.security.SecretStore
import com.relaytester.app.core.storage.SupplierStore
import com.relaytester.app.core.storage.SupplierStoreState
import com.relaytester.app.feature.tester.TesterUiState
import com.relaytester.app.feature.tester.TesterViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

private class RecordingStore : SupplierStore(
    null,
) {
    override suspend fun read() = SupplierStoreState(emptyList(), null)
    override suspend fun save(state: SupplierStoreState) = Unit
}

private class RecordingApi : RelayApi() {
    val active = AtomicInteger()
    val peak = AtomicInteger()

    override suspend fun test(
        profile: SupplierProfile,
        apiKey: String,
        model: String,
        prompt: String,
        maxTokens: Int,
        timeoutSeconds: Int,
    ): ModelTestResult {
        val current = active.incrementAndGet()
        current.let { peak.updateAndGet { maxOf(it, current) } }
        kotlinx.coroutines.delay(25)
        active.decrementAndGet()
        return ModelTestResult(model = model, status = TestStatus.SUCCESS)
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class UnifiedCatalogConcurrencyTest {
    private val mainDispatcher = UnconfinedTestDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        scope.cancel()
    }

    @Test
    fun multipleSourceTestsRunConcurrentlyAndKeepIndependentResults() {
        val api = RecordingApi()
        val subject = TesterViewModel(
            supplierStore = RecordingStore(),
            secretStore = object : SecretStore {
                override fun put(value: String) = "secret-test"
                override fun get(secretId: String): String = "api-key"
                override fun delete(secretId: String) = Unit
            },
            relayApiFactory = { api },
            balanceApiFactory = { BalanceApi() },
            skipRestore = true,
        )
        val sources = listOf(
            ModelSource("supplier-1", "model-a"),
            ModelSource("supplier-1", "model-b"),
            ModelSource("supplier-1", "model-c"),
        )

        setInternalCatalogForTest(
            subject,
            ModelCatalogEntry(
                id = "parallel",
                name = "parallel",
                sources = sources,
            ),
        )
        setInternalProfilesForTest(
            subject,
            SupplierProfile.empty(1).copy(
                id = "supplier-1",
                name = "supplier-1",
                apiKeySecretId = "api-secret",
            ),
        )
        setUiStateForTest(
            subject,
            modelCatalog = listOf(
                ModelCatalogEntry(
                    id = "parallel",
                    name = "parallel",
                    sources = sources,
                ),
            ),
            suppliers = listOf(
                SupplierProfile.empty(1).copy(
                    id = "supplier-1",
                    name = "supplier-1",
                    apiKeySecretId = "api-secret",
                ),
            ),
        )
        subject.startUnifiedCatalogSourceTest("parallel", sources[0])
        subject.startUnifiedCatalogSourceTest("parallel", sources[1])

        kotlinx.coroutines.runBlocking {
            while (subject.uiState.value.unifiedTestingKeys.isNotEmpty()) {
                kotlinx.coroutines.delay(10)
            }
        }

        assertEquals(2, subject.uiState.value.unifiedResults.size)
        assertTrue(subject.uiState.value.unifiedResults.all { it.result.status == TestStatus.SUCCESS })
        assertTrue(api.peak.get() >= 2)
        assertFalse(subject.uiState.value.isUnifiedTesting)
    }

    @Suppress("UNCHECKED_CAST")
    private fun setInternalCatalogForTest(subject: TesterViewModel, entry: ModelCatalogEntry) {
        val field = TesterViewModel::class.java.getDeclaredField("modelCatalog")
        field.isAccessible = true
        val value = field.get(subject) as MutableList<ModelCatalogEntry>
        value += entry
    }

    @Suppress("UNCHECKED_CAST")
    private fun setInternalProfilesForTest(subject: TesterViewModel, profile: SupplierProfile) {
        val field = TesterViewModel::class.java.getDeclaredField("profiles")
        field.isAccessible = true
        val value = field.get(subject) as MutableList<SupplierProfile>
        value += profile
    }

    private fun setUiStateForTest(
        subject: TesterViewModel,
        modelCatalog: List<ModelCatalogEntry>,
        suppliers: List<SupplierProfile>,
    ) {
        val field = TesterViewModel::class.java.getDeclaredField("_uiState")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val state = field.get(subject) as MutableStateFlow<TesterUiState>
        state.value = state.value.copy(
            isInitializing = false,
            modelCatalog = modelCatalog,
            suppliers = suppliers,
        )
    }
}
