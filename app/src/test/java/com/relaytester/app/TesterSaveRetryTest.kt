package com.relaytester.app

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.relaytester.app.core.backup.ConfigurationBackup
import com.relaytester.app.core.backup.ConfigurationBackupCodec
import com.relaytester.app.core.backup.ConfigurationBackupSupplier
import com.relaytester.app.core.model.BalanceQueryResult
import com.relaytester.app.core.model.BalanceQueryTemplate
import com.relaytester.app.core.model.RelayProtocol
import com.relaytester.app.core.model.SupplierProfile
import com.relaytester.app.core.model.TestSettings
import com.relaytester.app.core.network.BalanceApi
import com.relaytester.app.core.security.SecretStore
import com.relaytester.app.core.storage.SupplierStore
import com.relaytester.app.core.storage.SupplierStoreState
import com.relaytester.app.feature.tester.SettingField
import com.relaytester.app.feature.tester.TesterViewModel
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TesterSaveRetryTest {
    @Test
    fun `an API key retry keeps its secret until commit and reports success`() = runBlocking {
        assertSuccessfulRetry(balance = false)
    }

    @Test
    fun `a PAT retry keeps its secret until commit and reports success`() = runBlocking {
        assertSuccessfulRetry(balance = true)
    }

    @Test
    fun `exhausted API key retries roll back and a later manual save can commit`() = runBlocking {
        assertExhaustedRetry(balance = false)
    }

    @Test
    fun `exhausted PAT retries roll back and a later manual save can commit`() = runBlocking {
        assertExhaustedRetry(balance = true)
    }

    @Test
    fun `a competing autosave retry cannot commit an API key that its owner later rolls back`() = runBlocking {
        assertCompetingRetry(balance = false)
    }

    @Test
    fun `a competing autosave retry cannot commit a PAT that its owner later rolls back`() = runBlocking {
        assertCompetingRetry(balance = true)
    }

    @Test
    fun `cancelling during a blocking API key put removes the uncommitted secret`() = runBlocking {
        assertCancelledPut(balance = false)
    }

    @Test
    fun `cancelling during a blocking PAT put removes the uncommitted secret`() = runBlocking {
        assertCancelledPut(balance = true)
    }

    @Test
    fun `cancelling after an API key commit keeps its referenced secret and finishes publication`() = runBlocking {
        assertCancelledAfterCommit(balance = false)
    }

    @Test
    fun `cancelling after a PAT commit keeps its referenced secret and finishes publication`() = runBlocking {
        assertCancelledAfterCommit(balance = true)
    }

    @Test
    fun `a storage-cancelled API key save rolls back before a later autosave`() = runBlocking {
        assertStorageCancellationRollsBack(balance = false)
    }

    @Test
    fun `a storage-cancelled PAT save rolls back before a later autosave`() = runBlocking {
        assertStorageCancellationRollsBack(balance = true)
    }

    @Test
    fun `cancelling import during a blocking put removes all uncommitted secrets`() = runBlocking {
        withHarness(failures = 0) { h ->
            h.prepareImport()
            // API creation has already completed when the second, PAT put is parked.
            val put = h.secrets.blockPut("fixture-imported-pat")
            h.vm.confirmConfigurationImport("")
            val operation = h.root.children.single()
            put.entered.await()
            assertTrue(h.secrets.snapshot().containsValue("fixture-imported-api"))

            operation.cancel()
            put.release.countDown()
            put.created.await()
            operation.join()
            joinChildren(h.root)

            assertTrue(operation.isCancelled)
            assertEquals(0, h.store.attempts.get())
            assertEquals(h.store.initial, h.store.read())
            assertEquals(h.store.initial.suppliers, h.vm.uiState.value.suppliers)
            assertEquals(h.store.initial.suppliers, h.vm.balanceUiState.value.suppliers)
            assertEquals(
                "Cancellation before commit must remove even a put that finished after cancellation",
                oldSecrets(),
                h.secrets.snapshot(),
            )
            assertFalse(h.vm.configurationBackupUiState.value.isMessageError)
        }
    }

    @Test
    fun `cancelling import after durable commit keeps both referenced secrets and publishes the import`() = runBlocking {
        withHarness(failures = 0) { h ->
            h.prepareImport()
            val commit = h.store.gates[0]
            val returnFromCommit = commit.blockReturnAfterCommit()
            h.vm.confirmConfigurationImport("")
            val operation = h.root.children.single()
            commit.entered.await()
            commit.release.complete(Unit)
            commit.committed.await()
            // The store has committed, but its caller has not received the acknowledgement.
            assertEquals("Imported", h.store.read().suppliers.single().name)
            operation.cancel()
            returnFromCommit.countDown()
            operation.join()
            joinChildren(h.root)

            val durable = h.store.read()
            val imported = durable.suppliers.single()
            assertTrue(operation.isCancelled)
            assertEquals(1, h.store.attempts.get())
            assertEquals("Imported", imported.name)
            assertEquals("imported-filter", durable.modelFilterKeyword)
            assertEquals("fixture-imported-api", h.secrets.get(requireNotNull(imported.apiKeySecretId)))
            assertEquals("fixture-imported-pat", h.secrets.get(requireNotNull(imported.balanceAccessTokenSecretId)))
            assertEquals(durable.suppliers, h.vm.uiState.value.suppliers)
            assertEquals(durable.suppliers, h.vm.balanceUiState.value.suppliers)
            assertNull(h.secrets.get("api-old"))
            assertNull(h.secrets.get("pat-old"))
            assertEquals(2, h.secrets.snapshot().size)
            assertFalse(h.vm.configurationBackupUiState.value.isMessageError)
        }
    }

    @Test
    fun `reordering during a failed credential save preserves both suppliers and their old credentials`() = runBlocking {
        val initial = initialState().let { state ->
            state.copy(
                suppliers = state.suppliers + state.suppliers.single().copy(
                    id = "supplier-two",
                    name = "Second",
                    apiKeySecretId = null,
                    balanceAccessTokenSecretId = null,
                    models = listOf("model-b"),
                ),
            )
        }
        withHarness(failures = 2, initial = initial) { h ->
            val saved = h.saveChangedCredential(balance = false)
            val staged = h.store.gates[0].entered.await().suppliers.first { it.id == "supplier-one" }
            val createdId = requireNotNull(staged.apiKeySecretId)
            h.vm.moveSupplierTo("supplier-one", 1)
            // A fixed implementation may queue the whole move, not just its write.
            h.main.fence()
            h.store.gates[0].release.complete(Unit)
            h.store.gates[1].entered.await()
            h.store.gates[1].release.complete(Unit)
            h.store.gates[2].entered.await()
            h.store.gates[2].release.complete(Unit)
            joinChildren(h.root)

            val durable = h.store.read().suppliers
            assertEquals(listOf("supplier-two", "supplier-one"), durable.map { it.id })
            assertEquals(initial.suppliers.reversed(), durable)
            assertEquals(durable, h.vm.uiState.value.suppliers)
            assertEquals("fixture-old-api", h.secrets.get("api-old"))
            assertEquals("fixture-old-pat", h.secrets.get("pat-old"))
            assertNull(h.secrets.get(createdId))
            assertTrue(h.dirty(balance = false))
            assertFalse(saved.isCompleted)
        }
    }

    @Test
    fun `committing an API key preserves newer input for the next save`() = runBlocking {
        assertNewInputSurvivesCommit(balance = false)
    }

    @Test
    fun `committing a PAT preserves newer input for the next save`() = runBlocking {
        assertNewInputSurvivesCommit(balance = true)
    }

    @Test
    fun `discarding API key edits while a save is writing restores the committed credential`() = runBlocking {
        assertDiscardDuringSave(balance = false)
    }

    @Test
    fun `discarding PAT edits while a save is writing restores the committed credential`() = runBlocking {
        assertDiscardDuringSave(balance = true)
    }

    @Test
    fun `an API key typed while discard is reading survives that read and the next save`() = runBlocking {
        assertNewInputSurvivesDiscardRead(balance = false)
    }

    @Test
    fun `a PAT typed while discard is reading survives that read and the next save`() = runBlocking {
        assertNewInputSurvivesDiscardRead(balance = true)
    }

    @Test
    fun `an API key typed after discard queues behind a write survives and can be saved`() = runBlocking {
        assertNewInputSurvivesQueuedDiscard(balance = false)
    }

    @Test
    fun `a PAT typed after discard queues behind a write survives and can be saved`() = runBlocking {
        assertNewInputSurvivesQueuedDiscard(balance = true)
    }

    @Test
    fun `a first supplier save reports success when global filters differ from legacy profile filters`() = runBlocking {
        val initial = initialState().copy(
            modelFilterKeyword = "gpt",
            quickFilterTerms = listOf("claude"),
        )
        withHarness(failures = 0, initial = initial) { h ->
            // Global filters are saved independently; legacy per-profile fields can
            // legitimately still be empty after the app is restored from disk.
            val saved = CompletableDeferred<Unit>()
            h.vm.saveCurrentSupplier { saved.complete(Unit) }
            h.store.gates[0].entered.await()
            h.store.gates[0].release.complete(Unit)
            joinChildren(h.root)

            assertEquals(1, h.store.attempts.get())
            assertEquals("gpt", h.store.read().modelFilterKeyword)
            assertEquals(listOf("claude"), h.store.read().quickFilterTerms)
            assertEquals("fixture-old-api", h.secrets.get(requireNotNull(h.store.read().suppliers.single().apiKeySecretId)))
            assertFalse(h.dirty(balance = false))
            assertTrue("Unchanged global filters must not make a committed supplier form look unsaved", saved.isCompleted)
        }
    }

    @Test
    fun `cancelling a configuration write propagates without retry or error message`() = runBlocking {
        withHarness(failures = 0, cancelFirst = true) { h ->
            val saved = CompletableDeferred<Unit>()
            h.vm.saveCurrentSupplier { saved.complete(Unit) }
            val operation = h.root.children.single()
            h.store.gates[0].entered.await()
            h.store.gates[0].release.complete(Unit)
            operation.join()
            joinChildren(h.root)

            assertTrue(operation.isCancelled)
            assertEquals(1, h.store.attempts.get())
            assertEquals(h.store.initial, h.store.read())
            assertFalse(saved.isCompleted)
            assertFalse(h.vm.uiState.value.isMessageError)
            assertFalse(h.vm.balanceUiState.value.isMessageError)
        }
    }

    private suspend fun assertSuccessfulRetry(balance: Boolean) {
        withHarness(failures = 1) { h ->
            val saved = h.saveChangedCredential(balance)
            val first = h.store.gates[0]
            val staged = first.entered.await().suppliers.single()
            val createdId = requireNotNull(secretId(staged, balance))
            assertEquals(newValue(balance), h.secrets.get(createdId))
            assertFalse(saved.isCompleted)
            first.release.complete(Unit)

            val retry = h.store.gates[1]
            assertEquals(staged, retry.entered.await().suppliers.single())
            assertEquals(h.store.initial, h.store.read())
            assertEquals("The staged credential must survive until the retry commits", newValue(balance), h.secrets.get(createdId))
            assertEquals(oldValue(balance), h.secrets.get(oldId(balance)))
            assertFalse("The editor cannot close before durable commit", saved.isCompleted)
            assertFalse("A recoverable first attempt must not be reported as a rejected save", h.vm.uiState.value.isMessageError)
            assertFalse(h.vm.balanceUiState.value.isMessageError)

            retry.release.complete(Unit)
            saved.await()
            joinChildren(h.root)
            val durable = h.store.read().suppliers.single()
            assertEquals(staged, durable)
            assertEquals(newValue(balance), h.secrets.get(requireNotNull(secretId(durable, balance))))
            assertNull("Only the superseded secret is deleted after success", h.secrets.get(oldId(balance)))
            assertEquals(durable, h.vm.uiState.value.suppliers.single())
            assertFalse(h.dirty(balance))
            assertEquals(2, h.store.attempts.get())
        }
    }

    private suspend fun assertExhaustedRetry(balance: Boolean) {
        withHarness(failures = 2) { h ->
            val rejected = h.saveChangedCredential(balance)
            val first = h.store.gates[0]
            val discardedId = requireNotNull(secretId(first.entered.await().suppliers.single(), balance))
            first.release.complete(Unit)
            val retry = h.store.gates[1]
            retry.entered.await()
            retry.release.complete(Unit)
            joinChildren(h.root)

            assertEquals(h.store.initial, h.store.read())
            assertEquals(h.store.initial.suppliers, h.vm.uiState.value.suppliers)
            assertEquals(oldValue(balance), h.secrets.get(oldId(balance)))
            assertNull(h.secrets.get(discardedId))
            assertFalse(rejected.isCompleted)
            assertTrue(h.dirty(balance))
            assertTrue(h.vm.uiState.value.isMessageError)
            assertTrue(h.vm.balanceUiState.value.isMessageError)
            assertEquals("The automatic retry budget is one extra attempt", 2, h.store.attempts.get())

            val saved = h.saveChangedCredential(balance)
            val later = h.store.gates[2]
            val staged = later.entered.await().suppliers.single()
            assertFalse(saved.isCompleted)
            assertEquals(h.store.initial, h.store.read())
            later.release.complete(Unit)
            saved.await()
            joinChildren(h.root)
            assertEquals(staged, h.store.read().suppliers.single())
            assertEquals(newValue(balance), h.secrets.get(requireNotNull(secretId(staged, balance))))
            assertNull(h.secrets.get(oldId(balance)))
            assertEquals(staged, h.vm.uiState.value.suppliers.single())
            assertFalse(h.dirty(balance))
            assertFalse(rejected.isCompleted)
            assertEquals(3, h.store.attempts.get())
        }
    }

    /**
     * A writer may retry while another save has created a credential but is waiting for
     * the configuration transaction. The retry must not borrow that pending credential:
     * its owner can still fail and delete it, leaving an otherwise successful write dangling.
     */
    private suspend fun assertCompetingRetry(balance: Boolean) {
        withHarness(failures = 0, failingAttempts = setOf(0, 2, 3)) { h ->
            h.vm.updateSetting(SettingField.KEYWORD, "concurrent-filter")
            h.store.gates[0].entered.await()
            val earlier = h.root.children.toSet()

            val saved = h.saveChangedCredential(balance)
            val credentialSave = h.root.children.single { it !in earlier }
            // Main.immediate has already run the save up to its first suspension. Join
            // any IO preparation it started, then fence Main so its continuation has
            // run. A transaction that locks BEFORE creating the secret has no child
            // here and proceeds immediately; the test never requires private staging.
            credentialSave.children.toList().forEach { it.join() }
            h.main.fence()
            assertFalse(saved.isCompleted)

            // Autosave: fail, then commit its retry. Credential save: exhaust both
            // attempts. Every failure is before commit, just like the existing tests.
            h.store.gates[0].release.complete(Unit)
            for (index in 1..3) {
                h.store.gates[index].entered.await()
                h.store.gates[index].release.complete(Unit)
            }
            joinChildren(h.root)

            val durable = h.store.read().suppliers.single()
            assertEquals(
                "A rejected credential must not remain on disk after its secret was deleted",
                oldValue(balance),
                h.secrets.get(requireNotNull(secretId(durable, balance))),
            )
            assertEquals(h.store.initial.suppliers, h.store.read().suppliers)
            assertEquals("concurrent-filter", h.store.read().modelFilterKeyword)
            assertEquals(h.store.initial.suppliers, h.vm.uiState.value.suppliers)
            assertEquals(oldValue(balance), h.secrets.get(oldId(balance)))
            assertTrue(h.dirty(balance))
            assertFalse(saved.isCompleted)
        }
    }

    private suspend fun assertCancelledPut(balance: Boolean) {
        withHarness(failures = 0) { h ->
            val put = h.secrets.blockPut(newValue(balance))
            val saved = h.saveChangedCredential(balance)
            val operation = h.root.children.single()
            put.entered.await()
            operation.cancel()
            put.release.countDown()
            val createdId = put.created.await()
            operation.join()
            joinChildren(h.root)

            assertTrue(operation.isCancelled)
            assertEquals(0, h.store.attempts.get())
            assertEquals(h.store.initial, h.store.read())
            assertEquals(h.store.initial.suppliers, h.vm.uiState.value.suppliers)
            assertNull("A blocking put can finish after cancellation, but its uncommitted record must not survive", h.secrets.get(createdId))
            assertEquals(oldSecrets(), h.secrets.snapshot())
            assertTrue(h.dirty(balance))
            assertFalse(saved.isCompleted)
            assertFalse(h.vm.uiState.value.isMessageError)
            assertFalse(h.vm.balanceUiState.value.isMessageError)
        }
    }

    private suspend fun assertCancelledAfterCommit(balance: Boolean) {
        withHarness(failures = 0) { h ->
            val commit = h.store.gates[0]
            val returnFromCommit = commit.blockReturnAfterCommit()
            val saved = h.saveChangedCredential(balance)
            val operation = h.root.children.single()
            val staged = commit.entered.await().suppliers.single()
            commit.release.complete(Unit)
            commit.committed.await()
            assertEquals(staged, h.store.read().suppliers.single())
            operation.cancel()
            returnFromCommit.countDown()
            operation.join()
            joinChildren(h.root)

            val durable = h.store.read().suppliers.single()
            assertTrue(operation.isCancelled)
            assertEquals(1, h.store.attempts.get())
            assertEquals(staged, durable)
            assertEquals(
                "Cancellation after commit must never delete the credential referenced on disk",
                newValue(balance),
                h.secrets.get(requireNotNull(secretId(durable, balance))),
            )
            assertEquals(durable, h.vm.uiState.value.suppliers.single())
            assertFalse(h.dirty(balance))
            assertNull("Committed replacement still retires the old credential", h.secrets.get(oldId(balance)))
            assertEquals(oldValue(!balance), h.secrets.get(oldId(!balance)))
            assertEquals(2, h.secrets.snapshot().size)
            assertFalse("The cancelled caller must not receive a success callback", saved.isCompleted)
            assertFalse(h.vm.uiState.value.isMessageError)
            assertFalse(h.vm.balanceUiState.value.isMessageError)
        }
    }

    private suspend fun assertStorageCancellationRollsBack(balance: Boolean) {
        withHarness(failures = 0, cancelFirst = true) { h ->
            val saved = h.saveChangedCredential(balance)
            val operation = h.root.children.single()
            val staged = h.store.gates[0].entered.await().suppliers.single()
            val createdId = requireNotNull(secretId(staged, balance))
            h.store.gates[0].release.complete(Unit)
            operation.join()
            joinChildren(h.root)
            assertTrue(operation.isCancelled)
            assertEquals("A storage CancellationException is not retried", 1, h.store.attempts.get())
            assertEquals(h.store.initial, h.store.read())
            assertFalse(saved.isCompleted)
            assertFalse(h.vm.uiState.value.isMessageError)
            assertFalse(h.vm.balanceUiState.value.isMessageError)

            // Observe rollback through a later public writer, not through private state.
            h.vm.updateSetting(SettingField.KEYWORD, "after-storage-cancel")
            h.store.gates[1].entered.await()
            h.store.gates[1].release.complete(Unit)
            joinChildren(h.root)
            assertEquals(
                "A later autosave must not commit a cancelled save's staged profile",
                h.store.initial.suppliers,
                h.store.read().suppliers,
            )
            assertEquals("after-storage-cancel", h.store.read().modelFilterKeyword)
            assertEquals(h.store.initial.suppliers, h.vm.uiState.value.suppliers)
            assertNull(h.secrets.get(createdId))
            assertEquals(oldSecrets(), h.secrets.snapshot())
            assertTrue(h.dirty(balance))
            assertFalse(saved.isCompleted)
        }
    }

    private suspend fun assertNewInputSurvivesCommit(balance: Boolean) {
        withHarness(failures = 0) { h ->
            h.saveChangedCredential(balance)
            val firstProfile = h.store.gates[0].entered.await().suppliers.single()
            val firstId = requireNotNull(secretId(firstProfile, balance))
            val newerValue = if (balance) "fixture-later-pat" else "fixture-later-api"
            if (balance) h.vm.updateBalanceAccessToken(newerValue) else h.vm.updateApiKey(newerValue)
            h.store.gates[0].release.complete(Unit)
            joinChildren(h.root)

            assertEquals(newValue(balance), h.secrets.get(requireNotNull(secretId(h.store.read().suppliers.single(), balance))))
            val currentInput = if (balance) {
                h.vm.balanceUiState.value.credentials.accessToken
            } else {
                requireNotNull(h.vm.uiState.value.draft).apiKey
            }
            assertEquals("Completion of an earlier save must not replace text typed while it was writing", newerValue, currentInput)
            assertTrue("Newer input has not been committed yet", h.dirty(balance))

            // Save again WITHOUT restoring the newer input from the fixture.
            val laterSaved = CompletableDeferred<Unit>()
            if (balance) {
                h.vm.saveBalanceCredentials { laterSaved.complete(Unit) }
            } else {
                h.vm.saveCurrentSupplier { laterSaved.complete(Unit) }
            }
            h.store.gates[1].entered.await()
            h.store.gates[1].release.complete(Unit)
            laterSaved.await()
            joinChildren(h.root)
            val durable = h.store.read().suppliers.single()
            assertEquals(newerValue, h.secrets.get(requireNotNull(secretId(durable, balance))))
            assertEquals(durable, h.vm.uiState.value.suppliers.single())
            assertNull(h.secrets.get(firstId))
            assertFalse(h.dirty(balance))
            assertEquals(2, h.secrets.snapshot().size)
        }
    }

    private suspend fun assertDiscardDuringSave(balance: Boolean) {
        withHarness(failures = 0) { h ->
            h.saveChangedCredential(balance)
            val staged = h.store.gates[0].entered.await().suppliers.single()
            val earlier = h.root.children.toSet()
            if (balance) h.vm.discardBalanceCredentialsChanges() else h.vm.discardCurrentSupplierChanges()

            // Drain any credential read already launched by discard. A corrected discard
            // may wait for the configuration transaction before reading anything; it has
            // no IO child here, so this never requires that unsafe pre-commit read.
            h.root.children.filter { it !in earlier }.toList().forEach { discard ->
                discard.children.toList().forEach { it.join() }
            }
            h.main.fence()
            h.store.gates[0].release.complete(Unit)
            joinChildren(h.root)

            val durable = h.store.read().suppliers.single()
            assertEquals(staged, durable)
            val durableId = requireNotNull(secretId(durable, balance))
            assertEquals(newValue(balance), h.secrets.get(durableId))
            val currentInput = if (balance) {
                h.vm.balanceUiState.value.credentials.accessToken
            } else {
                requireNotNull(h.vm.uiState.value.draft).apiKey
            }
            val currentId = if (balance) {
                h.vm.balanceUiState.value.credentials.accessTokenSecretId
            } else {
                requireNotNull(h.vm.uiState.value.draft).apiKeySecretId
            }
            assertEquals("Discard must reload the committed credential, not publish old plaintext under its new secret ID", newValue(balance), currentInput)
            assertEquals(durableId, currentId)
            assertFalse(h.dirty(balance))
            assertNull(h.secrets.get(oldId(balance)))
            assertEquals(durable, h.vm.uiState.value.suppliers.single())
            assertEquals(2, h.secrets.snapshot().size)
        }
    }

    private suspend fun assertNewInputSurvivesDiscardRead(balance: Boolean) {
        withHarness(failures = 0) { h ->
            if (balance) h.vm.updateBalanceAccessToken("fixture-unsaved-pat") else h.vm.updateApiKey("fixture-unsaved-api")
            val read = h.secrets.blockGet(oldId(balance))
            if (balance) h.vm.discardBalanceCredentialsChanges() else h.vm.discardCurrentSupplierChanges()
            val discard = h.root.children.single()
            read.entered.await()

            val laterValue = if (balance) "fixture-later-pat" else "fixture-later-api"
            if (balance) h.vm.updateBalanceAccessToken(laterValue) else h.vm.updateApiKey(laterValue)
            read.release.countDown()
            // Wait for the entire discard, not merely the state frame produced by typing.
            discard.join()
            joinChildren(h.root)
            assertEquals(h.store.initial, h.store.read())
            assertNewerCredentialCanBeSaved(h, balance, laterValue, gateIndex = 0)
        }
    }

    private suspend fun assertNewInputSurvivesQueuedDiscard(balance: Boolean) {
        withHarness(failures = 0) { h ->
            h.vm.updateSetting(SettingField.KEYWORD, "queued-filter")
            h.store.gates[0].entered.await()
            if (balance) h.vm.updateBalanceAccessToken("fixture-unsaved-pat") else h.vm.updateApiKey("fixture-unsaved-api")
            // The old implementation starts reading immediately; the fixed one can wait
            // behind the parked writer. Neither is allowed to publish over later input.
            val read = h.secrets.blockGet(oldId(balance))
            if (balance) h.vm.discardBalanceCredentialsChanges() else h.vm.discardCurrentSupplierChanges()
            val laterValue = if (balance) "fixture-later-pat" else "fixture-later-api"
            if (balance) h.vm.updateBalanceAccessToken(laterValue) else h.vm.updateApiKey(laterValue)
            // Do not wait for read.entered here: a stale queued discard should exit
            // before reading at all once it sees the newer edit after acquiring the lock.
            read.release.countDown()
            h.store.gates[0].release.complete(Unit)
            joinChildren(h.root)

            assertEquals(h.store.initial.suppliers, h.store.read().suppliers)
            assertEquals("queued-filter", h.store.read().modelFilterKeyword)
            assertNewerCredentialCanBeSaved(h, balance, laterValue, gateIndex = 1)
        }
    }

    private suspend fun assertNewerCredentialCanBeSaved(
        h: Harness,
        balance: Boolean,
        laterValue: String,
        gateIndex: Int,
    ) {
        val currentInput = if (balance) {
            h.vm.balanceUiState.value.credentials.accessToken
        } else {
            requireNotNull(h.vm.uiState.value.draft).apiKey
        }
        assertEquals("An older discard must not replace input typed after it was requested", laterValue, currentInput)
        assertTrue("The newer input is still unsaved", h.dirty(balance))

        // Save the surviving input without feeding the expected value into the VM again.
        val saved = CompletableDeferred<Unit>()
        if (balance) {
            h.vm.saveBalanceCredentials { saved.complete(Unit) }
        } else {
            h.vm.saveCurrentSupplier { saved.complete(Unit) }
        }
        h.store.gates[gateIndex].entered.await()
        h.store.gates[gateIndex].release.complete(Unit)
        joinChildren(h.root)
        val durable = h.store.read().suppliers.single()
        assertEquals(laterValue, h.secrets.get(requireNotNull(secretId(durable, balance))))
        assertEquals(durable, h.vm.uiState.value.suppliers.single())
        assertNull(h.secrets.get(oldId(balance)))
        assertEquals(oldValue(!balance), h.secrets.get(oldId(!balance)))
        assertEquals(2, h.secrets.snapshot().size)
        assertFalse(h.dirty(balance))
        assertTrue("Saving the newer input must report success", saved.isCompleted)
    }

    private suspend fun withHarness(
        failures: Int,
        cancelFirst: Boolean = false,
        failingAttempts: Set<Int>? = null,
        initial: SupplierStoreState = initialState(),
        block: suspend (Harness) -> Unit,
    ) {
        val main = SingleThreadMainDispatcher()
        val store = GatedStore(failures, cancelFirst, failingAttempts, initial)
        val secrets = MemorySecrets()
        val lifecycle = ViewModelStore()
        var root: Job? = null
        Dispatchers.setMain(main)
        try {
            withTimeout(10_000) {
                withContext(Dispatchers.Main) {
                    val vm = TesterViewModel(
                        supplierStore = store,
                        secretStore = secrets,
                        relayApiFactory = { error("Saving must not create a network client") },
                        balanceApiFactory = {
                            // Import validates templates locally; no test may query a supplier.
                            object : BalanceApi() {
                                override suspend fun query(
                                    profile: SupplierProfile,
                                    apiKey: String,
                                    accessToken: String,
                                    userId: String,
                                    template: BalanceQueryTemplate,
                                ): BalanceQueryResult = error("Persistence must not issue a balance request")
                            }
                        },
                    )
                    lifecycle.put("tester", vm)
                    val job = requireNotNull(vm.viewModelScope.coroutineContext[Job])
                    root = job
                    vm.uiState.first { !it.isInitializing && it.draft?.apiKey == "fixture-old-api" }
                    vm.balanceUiState.first { !it.isInitializing && it.credentials.accessToken == "fixture-old-pat" }
                    joinChildren(job)
                    block(Harness(vm, store, secrets, job, main))
                }
            }
        } finally {
            try {
                withContext(NonCancellable) {
                    secrets.releaseAll()
                    store.gates.forEach { it.releaseAll() }
                    withContext(main) { lifecycle.clear() }
                    withTimeout(5_000) { root?.join() }
                }
            } finally {
                Dispatchers.resetMain()
                main.close()
            }
        }
    }

    private class Harness(
        val vm: TesterViewModel,
        val store: GatedStore,
        val secrets: MemorySecrets,
        val root: Job,
        val main: SingleThreadMainDispatcher,
    ) {
        fun saveChangedCredential(balance: Boolean): CompletableDeferred<Unit> {
            val saved = CompletableDeferred<Unit>()
            if (balance) {
                vm.updateBalanceAccessToken("fixture-new-pat")
                vm.saveBalanceCredentials { saved.complete(Unit) }
            } else {
                vm.updateApiKey("fixture-new-api")
                vm.saveCurrentSupplier { saved.complete(Unit) }
            }
            return saved
        }

        fun dirty(balance: Boolean): Boolean = if (balance) {
            vm.balanceUiState.value.credentials.accessTokenDirty
        } else {
            requireNotNull(vm.uiState.value.draft).apiKeyDirty
        }

        suspend fun prepareImport() {
            val imported = ConfigurationBackupSupplier(
                id = "supplier-one",
                name = "Imported",
                baseUrl = "https://relay.test/v1",
                protocol = RelayProtocol.CHAT_COMPLETIONS,
                apiKey = "fixture-imported-api",
                models = listOf("model-imported"),
                testSettings = TestSettings(),
                balanceTemplateId = null,
                balanceAccessToken = "fixture-imported-pat",
                balanceUserId = "imported-user",
            )
            val backup = ConfigurationBackup(
                createdAt = 123L,
                activeSupplierId = imported.id,
                suppliers = listOf(imported),
                balanceTemplates = listOf(BalanceQueryTemplate.newApiDefault()),
                modelFilterKeyword = "imported-filter",
            )
            vm.selectConfigurationImportFile(ConfigurationBackupCodec.exportPlaintext(backup))
            vm.previewConfigurationImport("")
            vm.configurationBackupUiState.first { !it.isBusy && it.importPreview != null }
            root.children.toList().forEach { it.join() }
        }
    }

    private suspend fun joinChildren(root: Job) {
        while (true) {
            val children = root.children.toList()
            if (children.isEmpty()) return
            children.forEach { it.join() }
        }
    }

    /** Unlike a queued test dispatcher, nested Main.immediate launches run on this thread. */
    private class SingleThreadMainDispatcher : MainCoroutineDispatcher(), AutoCloseable {
        @Volatile private var owner: Thread? = null
        private val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "tester-save-retry-main").also { owner = it }
        }
        override val immediate: MainCoroutineDispatcher = object : MainCoroutineDispatcher() {
            override val immediate: MainCoroutineDispatcher get() = this
            override fun isDispatchNeeded(context: CoroutineContext): Boolean = Thread.currentThread() !== owner
            override fun dispatch(context: CoroutineContext, block: Runnable) { executor.execute(block) }
        }
        override fun dispatch(context: CoroutineContext, block: Runnable) { executor.execute(block) }
        suspend fun fence() {
            val reached = CompletableDeferred<Unit>()
            executor.execute { reached.complete(Unit) }
            reached.await()
        }
        override fun close() {
            executor.shutdown()
            check(executor.awaitTermination(5, TimeUnit.SECONDS)) { "Main dispatcher did not stop" }
        }
    }

    private class SaveGate {
        val entered = CompletableDeferred<SupplierStoreState>()
        val release = CompletableDeferred<Unit>()
        val committed = CompletableDeferred<Unit>()
        @Volatile private var returnAfterCommit: CountDownLatch? = null

        fun blockReturnAfterCommit(): CountDownLatch = CountDownLatch(1).also { returnAfterCommit = it }

        fun awaitReturnAfterCommit() {
            returnAfterCommit?.let {
                check(it.await(5, TimeUnit.SECONDS)) { "Committed save acknowledgement was not released" }
            }
        }

        fun releaseAll() {
            release.complete(Unit)
            returnAfterCommit?.countDown()
        }
    }

    /** Commits whole configurations; failures happen before commit, not after a side effect. */
    private class GatedStore(
        private val failures: Int,
        private val cancelFirst: Boolean,
        private val failingAttempts: Set<Int>?,
        val initial: SupplierStoreState,
    ) : SupplierStore(null) {
        @Volatile private var durable = initial
        private val edits = Mutex()
        val attempts = AtomicInteger()
        val gates = List(4) { SaveGate() }

        override suspend fun read(): SupplierStoreState = durable
        override suspend fun save(state: SupplierStoreState) = edits.withLock {
            val index = attempts.getAndIncrement()
            val gate = gates[index]
            gate.entered.complete(state)
            gate.release.await()
            if (index == 0 && cancelFirst) throw CancellationException("Fixture cancellation")
            if (failingAttempts?.contains(index) ?: (index < failures)) {
                throw IOException("Fixture failure before commit")
            }
            durable = state
            gate.committed.complete(Unit)
            // Models disk commit preceding delivery of the acknowledgement to Main.
            // Real parent cancellation here cannot undo the durable side effect.
            gate.awaitReturnAfterCommit()
        }
    }

    private class PutGate {
        val entered = CompletableDeferred<Unit>()
        val created = CompletableDeferred<String>()
        val release = CountDownLatch(1)
    }

    private class ReadGate {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
    }

    /** Keeps creation/deletion side effects, substituting only the blocking Keystore. */
    private class MemorySecrets : SecretStore {
        private val values = ConcurrentHashMap(oldSecrets())
        private val sequence = AtomicInteger()
        private val puts = ConcurrentHashMap<String, PutGate>()
        private val reads = ConcurrentHashMap<String, ReadGate>()

        fun blockPut(value: String): PutGate = PutGate().also { puts[value] = it }
        fun blockGet(secretId: String): ReadGate = ReadGate().also { reads[secretId] = it }
        fun snapshot(): Map<String, String> = values.toMap()
        fun releaseAll() {
            puts.values.forEach { it.release.countDown() }
            reads.values.forEach { it.release.countDown() }
        }

        override fun put(value: String): String {
            val gate = puts[value]
            gate?.let {
                it.entered.complete(Unit)
                check(it.release.await(5, TimeUnit.SECONDS)) { "Credential put was not released" }
            }
            val id = "created-${sequence.incrementAndGet()}"
            values[id] = value
            gate?.created?.complete(id)
            return id
        }
        override fun get(secretId: String): String? {
            reads[secretId]?.let { gate ->
                gate.entered.complete(Unit)
                check(gate.release.await(5, TimeUnit.SECONDS)) { "Credential read was not released" }
            }
            return values[secretId]
        }
        override fun delete(secretId: String) { values.remove(secretId) }
    }

    private companion object {
        fun oldSecrets() = mapOf("api-old" to "fixture-old-api", "pat-old" to "fixture-old-pat")
        fun oldId(balance: Boolean) = if (balance) "pat-old" else "api-old"
        fun oldValue(balance: Boolean) = if (balance) "fixture-old-pat" else "fixture-old-api"
        fun newValue(balance: Boolean) = if (balance) "fixture-new-pat" else "fixture-new-api"
        fun secretId(profile: SupplierProfile, balance: Boolean) =
            if (balance) profile.balanceAccessTokenSecretId else profile.apiKeySecretId

        fun initialState() = SupplierStoreState(
            suppliers = listOf(
                SupplierProfile(
                    id = "supplier-one",
                    name = "Original",
                    baseUrl = "https://relay.test/v1",
                    protocol = RelayProtocol.CHAT_COMPLETIONS,
                    apiKeySecretId = "api-old",
                    balanceAccessTokenSecretId = "pat-old",
                    models = listOf("model-a"),
                    testSettings = TestSettings(),
                ),
            ),
            activeSupplierId = "supplier-one",
        )
    }
}
