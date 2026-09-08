package com.relaytester.app

import com.relaytester.app.core.backup.ConfigurationBackup
import com.relaytester.app.core.backup.ConfigurationBackupCodec
import com.relaytester.app.core.backup.ConfigurationBackupException
import com.relaytester.app.core.backup.ConfigurationBackupSupplier
import com.relaytester.app.core.model.ApiResult
import com.relaytester.app.core.model.BalanceQueryTemplate
import com.relaytester.app.core.model.BatchTestConfig
import com.relaytester.app.core.model.ErrorKind
import com.relaytester.app.core.model.ModelCatalogEntry
import com.relaytester.app.core.model.ModelSource
import com.relaytester.app.core.model.ModelTestResult
import com.relaytester.app.core.model.RelayProtocol
import com.relaytester.app.core.model.SupplierProfile
import com.relaytester.app.core.model.TestSettings
import com.relaytester.app.core.model.TestStatus
import com.relaytester.app.core.network.RelayApi
import com.relaytester.app.feature.tester.BatchTestRunner
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseRegressionTest {

    private class TrackingApi(
        private val latencyMs: Long = 5,
        private val failuresPerModel: Map<String, Int> = emptyMap(),
    ) : RelayApi() {
        val calls = AtomicInteger()
        val activeCalls = AtomicInteger()
        val peakActiveCalls = AtomicInteger()

        override suspend fun fetchModels(
            profile: SupplierProfile,
            apiKey: String,
            timeoutSeconds: Int,
        ): ApiResult<List<String>> {
            recordCall()
            return ApiResult.Success(listOf("model-a-pro", "model-b-lite", "model-a-pro", "model-c"))
        }

        override suspend fun test(
            profile: SupplierProfile,
            apiKey: String,
            model: String,
            prompt: String,
            maxTokens: Int,
            timeoutSeconds: Int,
        ): ModelTestResult {
            recordCall()
            val callIndex = calls.get()
            if (callIndex <= (failuresPerModel[model] ?: 0)) {
                return failedModel(model)
            }
            return successfulModel(model)
        }

        private fun recordCall() {
            calls.incrementAndGet()
            val active = activeCalls.incrementAndGet()
            active.let { current ->
                peakActiveCalls.updateAndGet { peak -> maxOf(peak, current) }
            }
            activeCalls.decrementAndGet()
        }

        private fun failedModel(model: String): ModelTestResult = ModelTestResult(
            model = model,
            status = TestStatus.FAILED,
            error = com.relaytester.app.core.model.TestError(ErrorKind.NETWORK, "forced failure"),
        )

        private fun successfulModel(model: String): ModelTestResult = ModelTestResult(
            model = model,
            status = TestStatus.SUCCESS,
            latencyMs = latencyMs,
        )
    }

    @Test
    fun batchRunnerDeduplicatesModelsRetriesCorrectlyAndKeepsProgressStable() = runTest {
        val api = TrackingApi(failuresPerModel = mapOf("model-a" to 2))
        val settings = TestSettings(
            concurrency = 6,
            retryCount = 2,
            delayMinMs = 0,
            delayMaxMs = 0,
            batchSize = 8,
            batchPauseMs = 0,
        )
        val subject = BatchTestRunner(api)
        val progressValues = mutableListOf<Int>()

        val summary = subject.run(
            config = BatchTestConfig(
                supplier = SupplierProfile.empty(),
                apiKey = "key",
                models = listOf("model-a", "model-b", "model-a", "model-c"),
                settings = settings,
            ),
            onResult = { _, done, _ -> progressValues += done },
        )

        assertEquals(listOf(1, 2, 3), progressValues.sorted())
        assertEquals(3, summary.total)
        assertEquals(3, summary.succeeded)
        assertEquals(0, summary.failed)
        assertEquals(5, api.calls.get())
        assertEquals(3, progressValues.toSet().size)
    }

    @Test
    fun multiSupplierSearchMetadataContainsOnlyDistinctMatchesPerSource() {
        val matches = listOf(
            CatalogSearchResult("supplier-1", "Supplier One", "gpt-4o-mini"),
            CatalogSearchResult("supplier-2", "Supplier Two", "gpt-4o-mini"),
            CatalogSearchResult("supplier-1", "Supplier One", "gpt-4o-mini"),
            CatalogSearchResult("supplier-1", "Supplier One", "gpt-4.1"),
        ).let { results ->
            results.distinctBy { it.supplierId + "\u0000" + it.modelId }
        }

        assertEquals(3, matches.size)
        assertEquals(2, matches.count { it.modelId == "gpt-4o-mini" })
    }

    @Test
    fun encryptedAndPlaintextBackupsRoundTripAndRejectMismatchedFormat() {
        val backup = buildBackup().let { value ->
            value.copy(
                suppliers = value.suppliers.map { supplier ->
                    supplier.copy(models = supplier.models.sorted())
                },
            )
        }
        val password = "correct horse battery"
        val encrypted = ConfigurationBackupCodec.encrypt(backup, password.toCharArray())
        val plaintext = ConfigurationBackupCodec.exportPlaintext(backup)

        assertEquals(1, JSONObject(String(encrypted)).optInt("version"))
        assertEquals(2, JSONObject(String(plaintext)).optInt("version"))

        val decrypted = ConfigurationBackupCodec.decrypt(encrypted, password.toCharArray())
        val parsed = ConfigurationBackupCodec.parse(plaintext)
        fun normalized(value: ConfigurationBackup) = value.copy(
            balanceTemplates = value.balanceTemplates.take(1).map { template ->
                template.copy(createdAt = 0L, updatedAt = 0L)
            },
        )
        assertEquals(normalized(backup), normalized(decrypted))
        assertEquals(normalized(backup), normalized(parsed))

        var savePlaintext = false
        try {
            ConfigurationBackupCodec.decrypt(plaintext, password.toCharArray())
            savePlaintext = true
        } catch (_: ConfigurationBackupException) {
        }
        assertTrue(!savePlaintext)
    }

    @Test
    fun tamperedEncryptedBackupsDoNotRestoreWithoutAnError() {
        val backup = buildBackup()
        val password = "correct horse battery"
        val encrypted = ConfigurationBackupCodec.encrypt(backup, password.toCharArray())
        val tampered = encrypted.copyOf().also { bytes ->
            val midpoint = bytes.size / 2
            bytes[midpoint] = (bytes[midpoint].toInt() xor 0x01).toByte()
        }

        var threw = false
        try {
            ConfigurationBackupCodec.decrypt(tampered, password.toCharArray())
        } catch (_: ConfigurationBackupException) {
            threw = true
        }
        assertTrue(threw)
    }

    private fun buildBackup(): ConfigurationBackup = ConfigurationBackup(
        createdAt = 123_456_789L,
        activeSupplierId = "supplier-1",
        suppliers = listOf(
            ConfigurationBackupSupplier(
                id = "supplier-1",
                name = "Primary",
                baseUrl = "https://relay.example.com",
                protocol = RelayProtocol.CHAT_COMPLETIONS,
                apiKey = "api-key",
                models = listOf("gpt-4o-mini", "gpt-4.1"),
                testSettings = TestSettings(prompt = "hello", quickFilterTerms = listOf("gpt")),
                balanceTemplateId = BalanceQueryTemplate.newApiDefault().id,
                balanceAccessToken = "access-token",
                balanceUserId = "42",
            ),
        ),
        balanceTemplates = listOf(BalanceQueryTemplate.newApiDefault()),
        modelCatalog = listOf(
            ModelCatalogEntry(
                id = "gpt-4o-mini",
                name = "gpt-4o-mini",
                sources = listOf(ModelSource("supplier-1", "gpt-4o-mini")),
            ),
        ),
        modelFilterKeyword = "gpt,4o",
        quickFilterTerms = listOf("gpt", "4o"),
    )

    private data class CatalogSearchResult(
        val supplierId: String,
        val supplierName: String,
        val modelId: String,
    )
}
