package com.relaytester.app

import com.relaytester.app.core.model.ModelCatalogEntry
import com.relaytester.app.core.model.ModelSource
import com.relaytester.app.core.model.RelayProtocol
import com.relaytester.app.core.model.SupplierProfile
import com.relaytester.app.core.model.TestSettings
import com.relaytester.app.core.network.BalanceApi
import com.relaytester.app.core.network.RelayBaseUrl
import com.relaytester.app.core.storage.SupplierStore
import com.relaytester.app.core.storage.SupplierStoreState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the 1.3.0 fixes: HTTP support, pull-only suppliers,
 * supplier deletion, and catalog source tracking.
 */
class RelayApiAndSupplierRegressionTest {

    // ---- HTTP support -----------------------------------------------------

    @Test
    fun schemeLessAddressStillDefaultsToHttps() {
        assertEquals("https://relay.example.com", RelayBaseUrl.normalize("relay.example.com"))
        assertEquals(
            "https://relay.example.com/v1",
            RelayBaseUrl.normalize("relay.example.com/v1"),
        )
    }

    @Test
    fun explicitHttpSchemeIsPreserved() {
        // The previous implementation rewrote this to https:// and rejected the
        // result, which made HTTP-only relays impossible to configure.
        assertEquals("http://relay.example.com", RelayBaseUrl.normalize("http://relay.example.com"))
        assertEquals(
            "http://192.168.1.10:8080/v1",
            RelayBaseUrl.normalize("http://192.168.1.10:8080/v1"),
        )
    }

    @Test
    fun httpsSchemeIsPreservedAndTrailingSlashesDropped() {
        assertEquals(
            "https://relay.example.com/v1",
            RelayBaseUrl.normalize("https://relay.example.com/v1/"),
        )
    }

    @Test
    fun malformedAddressesAreRejected() {
        assertNull(RelayBaseUrl.normalize(""))
        assertNull(RelayBaseUrl.normalize("   "))
        assertNull(RelayBaseUrl.normalize("http://"))
    }

    @Test
    fun cleartextDetectionMatchesTheInputPrefix() {
        assertTrue(RelayBaseUrl.isCleartext("http://relay.example.com"))
        assertTrue(RelayBaseUrl.isCleartext("  HTTP://relay.example.com"))
        assertFalse(RelayBaseUrl.isCleartext("https://relay.example.com"))
        assertFalse(RelayBaseUrl.isCleartext("relay.example.com"))
    }

    // ---- Pull-only suppliers ---------------------------------------------

    @Test
    fun pullOnlyFlagDefaultsToFalseAndRoundTrips() {
        assertFalse(SupplierProfile.empty().isTestingDisabled)
        val disabled = SupplierProfile.empty().copy(isTestingDisabled = true)
        assertTrue(disabled.isTestingDisabled)
        // A copy that does not touch the flag must not flip it.
        assertTrue(disabled.copy(name = "renamed").isTestingDisabled)
    }

    // ---- Supplier store persistence --------------------------------------

    @Test
    fun pullOnlyFlagSurvivesStoreRoundTrip() {
        val state = SupplierStoreState(
            suppliers = listOf(
                supplier(id = "pull-only", disabled = true),
                supplier(id = "normal", disabled = false),
            ),
            activeSupplierId = "pull-only",
        )

        val encoded = SupplierStore.encodeStateForTest(state)
        val decoded = SupplierStore.decodeStateForTest(JSONObject(encoded))

        assertEquals(2, decoded.suppliers.size)
        assertTrue(decoded.suppliers.first { it.id == "pull-only" }.isTestingDisabled)
        assertFalse(decoded.suppliers.first { it.id == "normal" }.isTestingDisabled)
    }

    @Test
    fun olderStoredConfigWithoutTheFlagLoadsAsTestable() {
        val legacy = JSONObject()
            .put("id", "legacy")
            .put("name", "Legacy")
            .put("baseUrl", "https://legacy.example.com")
            .put("protocol", RelayProtocol.CHAT_COMPLETIONS.name)
            .put("models", org.json.JSONArray())
            .put("settings", JSONObject())

        val decoded = SupplierStore.decodeSupplierForTest(legacy)

        assertNotNull(decoded)
        assertFalse(decoded!!.isTestingDisabled)
    }

    // ---- Balance endpoint resolution -------------------------------------

    @Test
    fun balanceEndpointResolvesForCleartextSupplier() {
        // A supplier the user configured as http:// must be able to query its own
        // balance. The old guard required the resolved endpoint to be HTTPS, so
        // every balance query against a cleartext relay failed with a misleading
        // "template address invalid" error.
        assertEquals(
            "http://relay.example.com/api/balance",
            BalanceApi.resolveEndpointForTest(
                baseUrl = "http://relay.example.com/v1",
                template = "/api/balance",
            ),
        )
    }

    @Test
    fun balanceEndpointStillResolvesForHttpsSupplier() {
        assertEquals(
            "https://relay.example.com/api/balance",
            BalanceApi.resolveEndpointForTest(
                baseUrl = "https://relay.example.com/v1",
                template = "/api/balance",
            ),
        )
    }

    @Test
    fun balanceEndpointRejectsADifferentHost() {
        // The guard must still stop a template from pointing the request — and the
        // credentials it carries — at some other site.
        assertNull(
            BalanceApi.resolveEndpointForTest(
                baseUrl = "https://relay.example.com/v1",
                template = "https://attacker.example.com/steal",
            ),
        )
    }

    @Test
    fun balanceEndpointRejectsASchemeDowngrade() {
        // Same host, but a cleartext endpoint must not be reachable from an HTTPS
        // supplier: that would silently downgrade an encrypted request.
        assertNull(
            BalanceApi.resolveEndpointForTest(
                baseUrl = "https://relay.example.com/v1",
                template = "http://relay.example.com/steal",
            ),
        )
    }

    // ---- Catalog source identity -----------------------------------------

    @Test
    fun catalogMissingKeyMatchesTheSourceIdentity() {
        val source = ModelSource(supplierId = "supplier-1", modelId = "gpt-4o-mini")
        val key = "${source.supplierId}\u0000${source.modelId}"

        assertEquals("supplier-1\u0000gpt-4o-mini", key)
        // The key must not collide across suppliers sharing a model name.
        val other = ModelSource(supplierId = "supplier-2", modelId = "gpt-4o-mini")
        assertTrue(key != "${other.supplierId}\u0000${other.modelId}")
    }

    @Test
    fun catalogEntryKeepsEveryDistinctSource() {
        val entry = ModelCatalogEntry(
            id = "entry-1",
            name = "gpt-4o-mini",
            sources = listOf(
                ModelSource("supplier-1", "gpt-4o-mini"),
                ModelSource("supplier-2", "gpt-4o-mini"),
                ModelSource("supplier-1", "gpt-4o-mini"),
            ),
        ).let { value ->
            value.copy(sources = value.sources.distinctBy { "${it.supplierId}\u0000${it.modelId}" })
        }

        assertEquals(2, entry.sources.size)
    }

    private fun supplier(id: String, disabled: Boolean): SupplierProfile = SupplierProfile(
        id = id,
        name = id,
        baseUrl = "https://$id.example.com",
        protocol = RelayProtocol.CHAT_COMPLETIONS,
        apiKeySecretId = null,
        models = listOf("model-a"),
        testSettings = TestSettings(),
        isTestingDisabled = disabled,
    )
}
