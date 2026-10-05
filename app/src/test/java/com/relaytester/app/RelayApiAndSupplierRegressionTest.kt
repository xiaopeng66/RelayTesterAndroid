package com.relaytester.app

import com.relaytester.app.core.model.BalanceHttpMethod
import com.relaytester.app.core.model.BalanceQueryMode
import com.relaytester.app.core.model.BalanceQueryTemplate
import com.relaytester.app.core.model.ModelCatalogEntry
import com.relaytester.app.core.model.ModelSource
import com.relaytester.app.core.model.RelayProtocol
import com.relaytester.app.core.model.SupplierProfile
import com.relaytester.app.core.model.TestSettings
import com.relaytester.app.core.network.BalanceScriptException
import com.relaytester.app.core.network.runScriptWithDeadline
import com.relaytester.app.core.network.BalanceApi
import com.relaytester.app.core.network.REDIRECT_CODES
import com.relaytester.app.core.network.RelayBaseUrl
import com.relaytester.app.core.network.forSameOriginRequests
import com.relaytester.app.core.network.sameOriginRedirect
import com.relaytester.app.core.storage.SupplierStore
import com.relaytester.app.core.storage.SupplierStoreState
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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

    // ---- Damage in the stored blob is reported, not swallowed -------------

    @Test
    fun aBlobThatCannotBeParsedIsReportedInsteadOfLookingLikeAFreshInstall() {
        // Truncated or overwritten by something that is not this app. The old read returned
        // an empty state with no signal, so the first save would have replaced the file —
        // and the user's suppliers with it — without anyone knowing it happened.
        val decoded = SupplierStore.decodeRawForTest("{ not json at all")

        assertTrue("必须报告解析失败", decoded.decodeFailed)
    }

    @Test
    fun aSectionWithTheWrongJsonTypeIsReported() {
        // The rest of the blob is fine, so this is the partial-loss shape: everything except
        // the damaged section comes back and the app has to say that something is missing.
        val encoded = SupplierStore.encodeStateForTest(
            SupplierStoreState(
                suppliers = listOf(supplier(id = "kept", disabled = false)),
                activeSupplierId = "kept",
            ),
        )
        val damaged = JSONObject(encoded).put("balanceTemplates", "not an array")

        val decoded = SupplierStore.decodeStateForTest(damaged)

        assertEquals("能读的部分照常载入", 1, decoded.suppliers.size)
        assertTrue("必须报告有段落损坏", decoded.decodeFailed)
    }

    @Test
    fun aSupplierListWhoseEntriesAllFailedIsReported() {
        // Shape is right, content is not: every entry lost its id. Same outcome as a
        // damaged section from the user's point of view — their suppliers are gone.
        val damaged = JSONObject()
            .put(
                "suppliers",
                JSONArray().put(JSONObject().put("name", "nameless")).put(JSONObject().put("baseUrl", "https://x")),
            )

        assertTrue(SupplierStore.decodeStateForTest(damaged).decodeFailed)
    }

    @Test
    fun anOrdinaryBlobAndAFreshInstallAreNotReportedAsDamaged() {
        // The other side of the rule: a false alarm on every launch would train the user to
        // ignore the one that matters. Empty arrays and absent optional sections are normal.
        val state = SupplierStoreState(
            suppliers = listOf(supplier(id = "one", disabled = false)),
            activeSupplierId = "one",
        )
        val decoded = SupplierStore.decodeStateForTest(JSONObject(SupplierStore.encodeStateForTest(state)))
        val fresh = SupplierStore.decodeRawForTest("{}")
        val blank = SupplierStore.decodeRawForTest("")

        assertFalse("正常配置不得误报", decoded.decodeFailed)
        assertFalse("新装（无内容）不得误报", fresh.decodeFailed)
        assertFalse("空串按新装处理", blank.decodeFailed)
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

    // ---- Redirect credential policy --------------------------------------

    @Test
    fun aSameOriginRedirectIsFollowed() {
        val base = "https://relay.example.com/v1/models".toHttpUrl()
        // Relative and absolute same-origin hops are both ordinary (a trailing-slash
        // rewrite, a moved endpoint) and cannot change who receives the API key.
        assertEquals("https://relay.example.com/v1/models/", sameOriginRedirect(base, "/v1/models/")?.toString())
        assertEquals(
            "https://relay.example.com/v2/models",
            sameOriginRedirect(base, "https://relay.example.com/v2/models")?.toString(),
        )
    }

    @Test
    fun aRedirectToAnotherHostIsRefused() {
        // This is the attack: a relay (or anyone who can answer for it) replies 302 to
        // its own host, and OkHttp's default would re-send the Authorization header there.
        val base = "https://relay.example.com/v1/models".toHttpUrl()
        assertNull(sameOriginRedirect(base, "https://attacker.example.com/steal"))
        // A look-alike subdomain is a different origin too.
        assertNull(sameOriginRedirect(base, "https://relay.example.com.evil.test/steal"))
    }

    @Test
    fun aSchemeDowngradeRedirectIsRefused() {
        // Plain http would put the API key on the wire in the clear.
        val base = "https://relay.example.com/v1/models".toHttpUrl()
        assertNull(sameOriginRedirect(base, "http://relay.example.com/v1/models"))
    }

    @Test
    fun aRedirectToAnotherPortIsRefused() {
        // Same host and scheme but a different port is a different server.
        val base = "https://relay.example.com/v1/models".toHttpUrl()
        assertNull(sameOriginRedirect(base, "https://relay.example.com:8443/v1/models"))
    }

    @Test
    fun aNonRedirectResponseCarriesNoDecision() {
        // Only the codes that mean "go elsewhere" are followed; 304 and friends answer
        // the request itself and must reach the caller as a normal response.
        assertTrue(301 in REDIRECT_CODES && 302 in REDIRECT_CODES && 303 in REDIRECT_CODES)
        assertTrue(307 in REDIRECT_CODES && 308 in REDIRECT_CODES)
        assertFalse(304 in REDIRECT_CODES)
        assertFalse(300 in REDIRECT_CODES)
    }

    @Test
    fun theProductionClientShapeDisablesOkHttpRedirectFollowing() {
        // The origin policy above only runs because both API clients turn OkHttp's own
        // redirect following off. If this regresses, a cross-origin 3xx is followed and
        // the API key leaks before any decision function is consulted.
        val shaped = OkHttpClient.Builder().build()
            .forSameOriginRequests(42, 42, 42, 42)

        assertFalse("必须关掉自动重定向，否则密钥会被发给 Location 里的主机", shaped.followRedirects)
        assertFalse("https→http 的降级跳转也必须关掉", shaped.followSslRedirects)
        assertEquals(42_000, shaped.connectTimeoutMillis)
        assertEquals(42_000, shaped.readTimeoutMillis)
        assertEquals(42_000, shaped.writeTimeoutMillis)
        assertEquals(42_000, shaped.callTimeoutMillis)
    }

    // ---- Balance script validation (static only) -------------------------

    @Test
    fun validatingAScriptTemplateNeverExecutesIt() {
        // The script throws the moment it is evaluated. Validation runs on the import
        // path, where the code comes from a file the user did not write, so a null
        // result here is the proof it was inspected, not run.
        val api = BalanceApi()
        val message = api.validateTemplate(
            scriptTemplate(
                """
                ({
                  request: { url: "/api/balance", method: "GET", headers: {} },
                  extractor: (json) => ({ remaining: 1 }),
                  boom: (() => { throw "executed" })()
                })
                """.trimIndent(),
            ),
        )

        assertNull("静态校验不得执行脚本", message)
    }

    @Test
    fun aScriptThatNeverReturnsDoesNotHoldTheQueryForever() {
        // The sandbox has no interrupt — the library exposes no stop hook, no instruction
        // budget and no memory ceiling — so without a deadline a script that never returns
        // parks the balance query on its IO thread for the life of the process, with the panel
        // sitting on "查询中" and nothing to cancel. The deadline is exercised through the seam
        // the evaluator uses, because the QuickJS native library does not load in this JVM
        // test source set; the hanging work has the shape a token filter cannot catch.
        val error = assertThrows(BalanceScriptException::class.java) {
            runScriptWithDeadline(timeoutMs = 100) {
                Thread.sleep(10_000)
                "never"
            }
        }

        assertTrue("超时必须被报出来：${error.message}", error.message.orEmpty().contains("超时"))
    }

    @Test
    fun theDeadlineStillDeliversWhatFinishedInTime() {
        // Three sides of the same rule: an answer that arrives inside the deadline comes back
        // unchanged, a script error keeps the message written for the user, and a foreign
        // failure (a stack overflow, a native allocation failure) is replaced by one that
        // says something the user could act on.
        assertEquals("ok", runScriptWithDeadline(timeoutMs = 1_000) { "ok" })

        val scriptError = assertThrows(BalanceScriptException::class.java) {
            runScriptWithDeadline(timeoutMs = 1_000) { throw BalanceScriptException("脚本缺少 request.url") }
        }
        assertEquals("脚本缺少 request.url", scriptError.message)

        val foreign = assertThrows(BalanceScriptException::class.java) {
            runScriptWithDeadline(timeoutMs = 1_000) { throw StackOverflowError("List is empty.") }
        }
        assertTrue(
            "外部异常的内部文案不得外泄：${foreign.message}",
            !foreign.message.orEmpty().contains("List is empty"),
        )
    }

    @Test
    fun theFunctionConstructorBypassIsRejected() {
        // `Function("wh" + "ile(1){}")()` has no loop keyword in the source, so the
        // keyword filter alone never sees it; the capital-F global is blocked separately.
        val api = BalanceApi()
        val message = api.validateTemplate(
            scriptTemplate("""({ request: { url: "/x" }, extractor: () => 1, hang: Function("wh" + "ile(1){}") })"""),
        )

        assertNotNull(message)
        assertTrue("必须点名被禁的构造器", message!!.contains("Function"))
    }

    @Test
    fun theGlobalThisHandleToFunctionIsRejected() {
        val api = BalanceApi()
        val message = api.validateTemplate(
            scriptTemplate("""({ request: { url: "/x" }, extractor: () => globalThis.Function("return 1") })"""),
        )

        assertNotNull(message)
        assertTrue(message!!.contains("globalThis"))
    }

    @Test
    fun anExtractorWrittenWithTheFunctionKeywordStillValidates() {
        // The `Function` global is blocked case-sensitively precisely so this, which is
        // an ordinary way to write the extractor, keeps working.
        val api = BalanceApi()
        val message = api.validateTemplate(
            scriptTemplate(
                """
                ({
                  request: { url: "/api/balance", method: "GET", headers: {} },
                  extractor: function (json) { return { remaining: json.quota }; }
                })
                """.trimIndent(),
            ),
        )

        assertNull(message)
    }

    @Test
    fun anUnsupportedPlaceholderInAScriptIsCaughtStatically() {
        val api = BalanceApi()
        val message = api.validateTemplate(
            scriptTemplate("""({ request: { url: "{{apikey}}" }, extractor: () => 1 })"""),
        )

        assertNotNull(message)
        assertTrue(message!!.contains("占位符"))
    }

    @Test
    fun anEmptyScriptIsRejected() {
        assertNotNull(BalanceApi().validateTemplate(scriptTemplate("   ")))
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

    private fun scriptTemplate(script: String): BalanceQueryTemplate = BalanceQueryTemplate(
        id = "script-template",
        name = "脚本模板",
        description = "",
        queryMode = BalanceQueryMode.SCRIPT,
        scriptCode = script,
        method = BalanceHttpMethod.GET,
        endpointTemplate = "",
        headers = emptyList(),
        availablePath = "",
    )

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
