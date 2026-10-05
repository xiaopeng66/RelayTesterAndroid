package com.relaytester.app

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.relaytester.app.core.backup.ConfigurationBackup
import com.relaytester.app.core.backup.ConfigurationBackupCodec
import com.relaytester.app.core.backup.ConfigurationBackupSupplier
import com.relaytester.app.core.model.ApiResult
import com.relaytester.app.core.model.BalanceHttpMethod
import com.relaytester.app.core.model.BalanceQueryTemplate
import com.relaytester.app.core.model.BalanceSnapshot
import com.relaytester.app.core.model.ModelTestResult
import com.relaytester.app.core.model.RelayProtocol
import com.relaytester.app.core.model.SupplierProfile
import com.relaytester.app.core.model.TestSettings
import com.relaytester.app.core.model.TestStatus
import com.relaytester.app.core.network.BalanceApi
import com.relaytester.app.core.network.RelayApi
import com.relaytester.app.core.security.SecretRead
import com.relaytester.app.core.security.SecretStore
import com.relaytester.app.core.storage.SupplierStore
import com.relaytester.app.core.storage.SupplierStoreState
import com.relaytester.app.feature.tester.SettingField
import com.relaytester.app.feature.tester.TesterViewModel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Gates storage, Keystore writes and request cleanup; no private VM state is seeded. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TesterPersistenceRaceTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---- a credential that cannot be decrypted is not an empty credential -------------

    @Test
    fun `a stored key that no longer decrypts is reported instead of looking empty`() = runTest {
        withHarness(
            secrets = MemorySecrets(unreadable = setOf("api-old")),
            ready = { h -> h.vm.uiState.first { !it.isInitializing && it.message != null } },
        ) { h ->
            val state = h.vm.uiState.value
            assertEquals("", state.draft?.apiKey.orEmpty())
            assertTrue("解密失败没有说明原因：${state.message}", state.message.orEmpty().contains("无法解密"))
            assertTrue("解密失败不是错误态", state.isMessageError)
        }
    }

    @Test
    fun `a balance token that no longer decrypts is reported on the balance panel`() = runTest {
        withHarness(
            secrets = MemorySecrets(unreadable = setOf("pat-old")),
            ready = { h -> h.vm.balanceUiState.first { !it.isInitializing && it.message != null } },
        ) { h ->
            val balance = h.vm.balanceUiState.value
            assertEquals("", balance.credentials.accessToken)
            assertTrue(
                "解密失败没有说明原因：${balance.message}",
                balance.message.orEmpty().contains("无法解密"),
            )
            // The API key was readable, so the tester panel must not have been blamed.
            assertNull("另一个面板被误报：${h.vm.uiState.value.message}", h.vm.uiState.value.message)
        }
    }

    @Test
    fun `exporting a backup stops when a stored credential cannot be decrypted`() = runTest {
        withHarness(
            secrets = MemorySecrets(unreadable = setOf("api-old")),
            // The export refuses to run while anything is initializing, and an unreadable
            // credential never fills a field, so the wait is on both panels being idle.
            ready = { h ->
                h.vm.uiState.first { !it.isInitializing && !it.isSecretsHydrating && it.message != null }
                h.vm.balanceUiState.first { !it.isInitializing && !it.isSecretsHydrating }
            },
        ) { h ->
            h.vm.createConfigurationBackup(password = "fixture-password", encrypted = false)
            awaitReal {
                h.vm.configurationBackupUiState.first { !it.isBusy && it.message != null }
            }

            val backupState = h.vm.configurationBackupUiState.value
            assertTrue("读不出凭据却没有报错：${backupState.message}", backupState.isMessageError)
            assertTrue(
                "报错没说清是凭据读不出来：${backupState.message}",
                backupState.message.orEmpty().contains("无法解密"),
            )
            assertTrue(
                "报错没有点出供应商名字：${backupState.message}",
                backupState.message.orEmpty().contains("Original"),
            )
            // The dangerous outcome is a file that claims to be a backup while the credentials
            // in it are empty, so the payload must not exist at all.
            assertNull("读不出凭据却还是给出了备份文件", backupState.exportPayload)
        }
    }

    @Test
    fun `a healthy store still exports the credentials it holds`() = runTest {
        withHarness { h ->
            h.vm.createConfigurationBackup(password = "fixture-password", encrypted = false)
            awaitReal {
                h.vm.configurationBackupUiState.first { !it.isBusy && it.exportPayload != null }
            }
            val payload = h.vm.configurationBackupUiState.value.exportPayload
            assertTrue(
                "健康凭据没有进入备份",
                String(payload!!.encryptedBytes, Charsets.UTF_8).contains("fixture-old-api"),
            )
        }
    }

    @Test
    fun `a model refresh preserves a PAT saved while the response was pending`() = runTest {
        withHarness { h ->
            h.vm.refreshSupplierModels(SUPPLIER_ID)
            awaitReal { h.api.modelsEntered.await() }

            h.vm.updateBalanceAccessToken("fixture-new-pat")
            h.vm.saveBalanceCredentials()
            awaitReal {
                h.vm.balanceUiState.first {
                    !it.credentials.accessTokenDirty && it.credentials.accessToken == "fixture-new-pat"
                }
            }
            assertNull(h.secrets.get("pat-old"))

            h.api.modelsRelease.complete(Unit)
            awaitReal { h.vm.uiState.first { !it.isFetchingModels && it.draft?.models == listOf("model-fresh") } }

            val durable = h.store.state.suppliers.single()
            assertEquals("fixture-new-pat", h.secrets.get(requireNotNull(durable.balanceAccessTokenSecretId)))
            assertEquals("fixture-old-api", h.secrets.get(requireNotNull(durable.apiKeySecretId)))
            assertEquals(listOf("model-fresh"), durable.models)
            assertEquals(durable, h.vm.uiState.value.suppliers.single())
        }
    }

    @Test
    fun `overlapping API key and PAT saves preserve both new credentials`() = runTest {
        withHarness { h ->
            val patWrite = h.secrets.blockPut("fixture-new-pat")
            h.vm.updateBalanceAccessToken("fixture-new-pat")
            h.vm.saveBalanceCredentials()
            awaitReal { patWrite.entered.await() }

            h.vm.updateApiKey("fixture-new-api")
            h.vm.saveCurrentSupplier()
            runCurrent()
            // Both operations are pending before either is allowed to finish. Serializing
            // creation as well as replacement prevents either old credential reference
            // from being copied back after the other operation deletes it.
            val apiCreated = h.secrets.watchPut("fixture-new-api")
            val prematureApiWrite = awaitAbsent { apiCreated.await() }
            patWrite.release.countDown()
            awaitReal {
                h.vm.uiState.first { !it.draft!!.apiKeyDirty && it.draft.apiKey == "fixture-new-api" }
            }
            awaitReal { h.vm.balanceUiState.first { !it.credentials.accessTokenDirty } }

            val durable = h.store.state.suppliers.single()
            assertEquals("fixture-new-api", h.secrets.get(requireNotNull(durable.apiKeySecretId)))
            assertEquals("fixture-new-pat", h.secrets.get(requireNotNull(durable.balanceAccessTokenSecretId)))
            assertNull("A credential transaction must wait for the earlier transaction", prematureApiWrite)
        }
    }

    @Test
    fun `import waits for queued configuration writes and remains the durable configuration`() = runTest {
        withHarness { h ->
            h.prepareImport()
            val earlierSave = h.store.blockSave { it.modelFilterKeyword == "earlier" }
            h.vm.updateSetting(SettingField.KEYWORD, "earlier")
            awaitReal { earlierSave.entered.await() }
            h.vm.updateSetting(SettingField.KEYWORD, "queued")
            runCurrent()

            val importSave = h.store.watchSave { it.suppliers.single().name == "Imported" }
            h.vm.confirmConfigurationImport("")
            awaitReal { h.vm.configurationBackupUiState.first { it.isBusy } }
            // The gate owns the earlier DataStore transaction. Import cannot bypass the
            // VM's queue and leave an old configuration write behind its commit.
            val prematureImport = awaitAbsent { importSave.await() }
            earlierSave.release.complete(Unit)
            awaitReal { h.vm.configurationBackupUiState.first { !it.isBusy && it.importPreview == null } }

            assertEquals("Imported", h.store.state.suppliers.single().name)
            assertEquals("imported-filter", h.store.state.modelFilterKeyword)
            assertEquals(h.store.state.suppliers, h.vm.uiState.value.suppliers)
            assertEquals("fixture-imported-api", h.secrets.get(requireNotNull(h.store.state.suppliers.single().apiKeySecretId)))
            assertNull("Import must serialize with the configuration write already in flight", prematureImport)
        }
    }

    @Test
    fun `import waits for an in-flight credential creation before replacing and deleting secrets`() = runTest {
        withHarness { h ->
            h.prepareImport()
            val patWrite = h.secrets.blockPut("fixture-new-pat")
            h.vm.updateBalanceAccessToken("fixture-new-pat")
            h.vm.saveBalanceCredentials()
            awaitReal { patWrite.entered.await() }

            val importSave = h.store.watchSave { it.suppliers.single().name == "Imported" }
            h.vm.confirmConfigurationImport("")
            awaitReal { h.vm.configurationBackupUiState.first { it.isBusy } }
            val prematureImport = awaitAbsent { importSave.await() }
            patWrite.release.countDown()
            awaitReal { h.vm.configurationBackupUiState.first { !it.isBusy && it.importPreview == null } }
            // Join the operations started before import as well: the assertion must see
            // their final writes, rather than only the import's initial success frame.
            awaitReal { h.vm.viewModelScope.coroutineContext[Job]!!.children.toList().forEach { it.join() } }

            val durable = h.store.state.suppliers.single()
            assertEquals("Imported", durable.name)
            assertEquals("fixture-imported-api", h.secrets.get(requireNotNull(durable.apiKeySecretId)))
            assertEquals("fixture-imported-pat", h.secrets.get(requireNotNull(durable.balanceAccessTokenSecretId)))
            assertEquals(durable, h.vm.uiState.value.suppliers.single())
            assertNull("Import must wait for credential creation, commit and publication", prematureImport)
        }
    }

    @Test
    fun `a save queued behind template switching cannot restore the invalidated balance`() = runTest {
        withHarness { h ->
            val templateSave = h.store.blockSave { it.suppliers.single().balanceTemplateId == "template-two" }
            h.vm.selectBalanceTemplate("template-two")
            awaitReal { templateSave.entered.await() }

            val queuedSave = h.store.blockSave { it.modelFilterKeyword == "queued-filter" }
            h.vm.updateSetting(SettingField.KEYWORD, "queued-filter")
            runCurrent()
            templateSave.release.complete(Unit)
            awaitReal { queuedSave.entered.await() }
            queuedSave.release.complete(Unit)
            awaitReal { queuedSave.committed.await() }

            assertTrue("The template change invalidates the old on-screen result", h.vm.balanceUiState.value.balanceSnapshots.isEmpty())
            assertTrue("A later configuration save must not restore that old result on disk", h.store.state.balanceSnapshots.isEmpty())
            assertEquals("template-two", h.store.state.suppliers.single().balanceTemplateId)
        }
    }

    @Test
    fun `a cancelled request must finish cleanup before another run can start`() = runTest {
        withHarness { h ->
            h.vm.startTest()
            val first = awaitReal { h.api.testCalls.receive() }
            assertTrue(h.vm.uiState.value.isRunning)
            val cancelledJobs = h.vm.viewModelScope.coroutineContext[Job]!!.children.toList()

            h.vm.cancelRun()
            assertFalse(h.vm.uiState.value.isRunning)
            h.vm.startTest()
            runCurrent()
            val prematureRun = awaitAbsent { h.api.testCalls.receive() }
            assertNull("A cancelled but incomplete request still owns the run slot", prematureRun)

            first.release.complete(Unit)
            awaitReal { cancelledJobs.forEach { it.join() } }
            h.vm.startTest()
            val next = awaitReal { h.api.testCalls.receive() }
            assertTrue("The next run keeps its own cancel control after old cleanup", h.vm.uiState.value.isRunning)
            next.release.complete(Unit)
            awaitReal { h.vm.uiState.first { !it.isRunning && it.summary != null } }
            assertEquals(TestStatus.SUCCESS, h.vm.uiState.value.results.single().status)
            assertEquals(1, h.vm.uiState.value.summary!!.succeeded)
        }
    }

    private suspend fun TestScope.withHarness(
        secrets: MemorySecrets = MemorySecrets(),
        /** What the test waits for before it may drive the view model. */
        ready: suspend (Harness) -> Unit = { h ->
            h.vm.uiState.first { !it.isInitializing && it.draft?.apiKey == "fixture-old-api" }
            h.vm.balanceUiState.first { !it.isInitializing && it.credentials.accessToken == "fixture-old-pat" }
        },
        block: suspend (Harness) -> Unit,
    ) {
        val h = Harness(secrets)
        try {
            awaitReal { ready(h) }
            block(h)
        } finally {
            h.close()
            runCurrent()
            awaitReal { h.jobsAtClose.forEach { it.join() } }
        }
    }

    /** Real-time deadlines bound external IO gates; ordering comes from the gates. */
    private suspend fun <T> awaitReal(block: suspend () -> T): T = withContext(Dispatchers.Default) {
        withTimeout(5_000) { block() }
    }

    private suspend fun <T> awaitAbsent(block: suspend () -> T): T? = withContext(Dispatchers.Default) {
        withTimeoutOrNull(300) { block() }
    }

    private class Harness(secrets: MemorySecrets = MemorySecrets()) {
        val secrets = secrets
        val store = GatedStore(initialState())
        val api = GatedApi()
        private val lifecycle = ViewModelStore()
        val vm = TesterViewModel(store, secrets, { api }, { BalanceApi() })
        var jobsAtClose = emptyList<Job>()
            private set

        init {
            lifecycle.put("tester", vm)
        }

        suspend fun prepareImport() {
            val imported = ConfigurationBackupSupplier(
                id = SUPPLIER_ID,
                name = "Imported",
                baseUrl = "https://relay.test/v1",
                protocol = RelayProtocol.CHAT_COMPLETIONS,
                apiKey = "fixture-imported-api",
                models = listOf("model-imported"),
                testSettings = settings(),
                balanceTemplateId = "template-one",
                balanceAccessToken = "fixture-imported-pat",
                balanceUserId = "imported-user",
            )
            val backup = ConfigurationBackup(
                createdAt = 123L,
                activeSupplierId = SUPPLIER_ID,
                suppliers = listOf(imported),
                balanceTemplates = listOf(template("template-one")),
                modelFilterKeyword = "imported-filter",
            )
            vm.selectConfigurationImportFile(ConfigurationBackupCodec.exportPlaintext(backup))
            vm.previewConfigurationImport("")
            withContext(Dispatchers.Default) {
                withTimeout(5_000) { vm.configurationBackupUiState.first { !it.isBusy && it.importPreview != null } }
            }
        }

        fun close() {
            jobsAtClose = vm.viewModelScope.coroutineContext[Job]!!.children.toList()
            lifecycle.clear()
            secrets.releaseAll()
            store.releaseAll()
            api.releaseAll()
        }
    }

    private class PutGate {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
    }

    /** Keeps creation/deletion side effects, substituting only the blocking Keystore. */
    private class MemorySecrets(
        /**
         * Ids whose record exists but no longer decrypts — what a reset Keystore looks like
         * from the outside. Everything else behaves like a healthy store.
         */
        private val unreadable: Set<String> = emptySet(),
    ) : SecretStore {
        private val values = ConcurrentHashMap<String, String>().apply {
            put("api-old", "fixture-old-api")
            put("pat-old", "fixture-old-pat")
        }
        private val sequence = AtomicInteger()
        private val gates = ConcurrentHashMap<String, PutGate>()
        private val created = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

        fun blockPut(value: String): PutGate = PutGate().also { gates[value] = it }
        fun watchPut(value: String): CompletableDeferred<Unit> = created.computeIfAbsent(value) { CompletableDeferred() }

        override fun put(value: String): String {
            gates[value]?.let { gate ->
                gate.entered.complete(Unit)
                check(gate.release.await(5, TimeUnit.SECONDS)) { "Credential gate was not released" }
            }
            val id = "created-${sequence.incrementAndGet()}"
            values[id] = value
            watchPut(value).complete(Unit)
            return id
        }

        override fun get(secretId: String): String? = values[secretId]

        override fun read(secretId: String): SecretRead =
            if (secretId in unreadable) {
                SecretRead.Unreadable("测试构造：密钥库已被重置")
            } else {
                values[secretId]?.let(SecretRead::Found) ?: SecretRead.Absent
            }

        override fun delete(secretId: String) { values.remove(secretId) }
        fun releaseAll() { gates.values.forEach { it.release.countDown() } }
    }

    private class SaveGate(val matches: (SupplierStoreState) -> Boolean) {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val committed = CompletableDeferred<Unit>()
        var claimed = false
    }

    /** Like DataStore, commits whole configurations in a serialized edit transaction. */
    private class GatedStore(initial: SupplierStoreState) : SupplierStore(null) {
        @Volatile var state = initial
            private set
        private val edits = Mutex()
        private val gates = mutableListOf<SaveGate>()
        private val watches = mutableListOf<Pair<(SupplierStoreState) -> Boolean, CompletableDeferred<Unit>>>()

        fun blockSave(matches: (SupplierStoreState) -> Boolean): SaveGate = synchronized(gates) {
            SaveGate(matches).also { gates += it }
        }

        fun watchSave(matches: (SupplierStoreState) -> Boolean): CompletableDeferred<Unit> = synchronized(watches) {
            CompletableDeferred<Unit>().also { watches += matches to it }
        }

        override suspend fun read(): SupplierStoreState = state

        override suspend fun save(state: SupplierStoreState) {
            synchronized(watches) { watches.filter { it.first(state) }.forEach { it.second.complete(Unit) } }
            val gate = synchronized(gates) {
                gates.firstOrNull { !it.claimed && it.matches(state) }?.also { it.claimed = true }
            }
            edits.withLock {
                gate?.entered?.complete(Unit)
                gate?.release?.await()
                this.state = state
                gate?.committed?.complete(Unit)
            }
        }

        fun releaseAll() { synchronized(gates) { gates.forEach { it.release.complete(Unit) } } }
    }

    private class TestCall {
        val release = CompletableDeferred<Unit>()
    }

    private class GatedApi : RelayApi() {
        val modelsEntered = CompletableDeferred<Unit>()
        val modelsRelease = CompletableDeferred<Unit>()
        val testCalls = Channel<TestCall>(Channel.UNLIMITED)
        private val calls = mutableListOf<TestCall>()

        override suspend fun fetchModels(profile: SupplierProfile, apiKey: String, timeoutSeconds: Int): ApiResult<List<String>> {
            modelsEntered.complete(Unit)
            modelsRelease.await()
            return ApiResult.Success(listOf("model-fresh"))
        }

        override suspend fun test(
            profile: SupplierProfile,
            apiKey: String,
            model: String,
            prompt: String,
            maxTokens: Int,
            timeoutSeconds: Int,
        ): ModelTestResult {
            val call = TestCall().also { calls += it }
            testCalls.send(call)
            // A body read already executing on IO can outlive cancellation. It must not
            // release the VM's run slot until its continuation and cleanup finish.
            withContext(NonCancellable) { call.release.await() }
            currentCoroutineContext().ensureActive()
            return ModelTestResult(model, TestStatus.SUCCESS, latencyMs = 1L)
        }

        fun releaseAll() {
            modelsRelease.complete(Unit)
            calls.forEach { it.release.complete(Unit) }
        }
    }

    private companion object {
        const val SUPPLIER_ID = "supplier-one"

        fun settings() = TestSettings(delayMinMs = 0, delayMaxMs = 0, batchPauseMs = 0)

        fun template(id: String) = BalanceQueryTemplate(
            id = id,
            name = id,
            description = "",
            method = BalanceHttpMethod.GET,
            endpointTemplate = "/balance",
            headers = emptyList(),
            availablePath = "available",
            createdAt = 1L,
            updatedAt = 1L,
        )

        fun initialState(): SupplierStoreState = SupplierStoreState(
            suppliers = listOf(
                SupplierProfile(
                    id = SUPPLIER_ID,
                    name = "Original",
                    baseUrl = "https://relay.test/v1",
                    protocol = RelayProtocol.CHAT_COMPLETIONS,
                    apiKeySecretId = "api-old",
                    balanceAccessTokenSecretId = "pat-old",
                    balanceTemplateId = "template-one",
                    balanceUserId = "original-user",
                    models = listOf("model-a"),
                    testSettings = settings(),
                ),
            ),
            activeSupplierId = SUPPLIER_ID,
            balanceTemplates = listOf(template("template-one"), template("template-two")),
            balanceSnapshots = mapOf(
                SUPPLIER_ID to BalanceSnapshot(
                    supplierId = SUPPLIER_ID,
                    templateId = "template-one",
                    templateName = "template-one",
                    availableRaw = 42.0,
                    unitLabel = "USD",
                    scaleDivisor = 1.0,
                    checkedAt = 1L,
                    latencyMs = 1L,
                ),
            ),
        )
    }
}
