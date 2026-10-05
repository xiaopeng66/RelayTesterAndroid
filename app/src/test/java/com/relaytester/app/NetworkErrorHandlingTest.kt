package com.relaytester.app

import com.relaytester.app.core.model.BalanceHttpMethod
import com.relaytester.app.core.model.BalanceQueryResult
import com.relaytester.app.core.model.BalanceQueryTemplate
import com.relaytester.app.core.model.ErrorKind
import com.relaytester.app.core.model.BalanceTemplateHeader
import com.relaytester.app.core.model.RelayProtocol
import com.relaytester.app.core.model.SupplierProfile
import com.relaytester.app.core.model.TestSettings
import com.relaytester.app.core.network.BalanceApi
import com.relaytester.app.core.network.RelayApi
import com.relaytester.app.core.network.redactSecrets
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two places where an upstream's own words reach the user and the export: how a failed
 * response is classified, and what is stripped out of the message before it is stored.
 *
 * Both are about not misleading the user and not leaking the key they just typed.
 */
class NetworkErrorHandlingTest {
    // ---- Classifying a failed answer -------------------------------------

    @Test
    fun `an oversized answer is an upstream answer, not a broken link`() {
        // A plain IOException landed in the NETWORK bucket: the result row blamed the
        // connection for a body the site sent, and BatchTestRunner retried it — the largest
        // answers spent the whole retry budget and failed the same way every time.
        val api = RelayApi()

        val error = api.errorForThrowable(RelayApi.ResponseTooLargeException())

        assertEquals(ErrorKind.INVALID_RESPONSE, error.kind)
        assertTrue("必须说明原因", error.message.contains("响应体过大"))
    }

    @Test
    fun `a real transport failure is still a network error`() {
        // The other side of the rule above: the mapping must not have swallowed it.
        val api = RelayApi()

        val error = api.errorForThrowable(IOException("connection reset"))

        assertEquals(ErrorKind.NETWORK, error.kind)
    }

    // ---- Redacting the key a site hands back ------------------------------

    @Test
    fun `a key echoed in the upstream wording is redacted`() {
        // OpenAI's canonical wording puts prose between the field name and the value, so the
        // old field-name pattern (`api_key=`, `Bearer `) did not match and the submitted key
        // travelled into the result row, the error detail and the JSON export.
        val message = "Incorrect API key provided: sk-live-9f2b7c41d8e35a60. Check your key."

        val redacted = message.redactSecrets()

        assertTrue("裸密钥必须被抹掉", !redacted.contains("sk-live-9f2b7c41d8e35a60"))
        assertTrue("话术本身要留下", redacted.contains("Incorrect API key"))
    }

    @Test
    fun `the older field and header shapes are still redacted`() {
        val cases = mapOf(
            "api_key=abc123def456" to "abc123def456",
            "Authorization: Bearer abc123def456" to "abc123def456",
            "access_token: abc123def456" to "abc123def456",
            "x-api-key: abc123def456" to "abc123def456",
            "token is invalid: abc123def456" to "abc123def456",
        )

        cases.forEach { (message, secret) ->
            assertTrue(
                "必须抹掉 $secret（原文：$message）",
                !message.redactSecrets().contains(secret),
            )
        }
    }

    @Test
    fun `quoted credentials and json credential fields are redacted`() {
        val secret = "fixtureCredential12345"
        val messages = listOf(
            "Incorrect API key provided: '$secret'. Check the key.",
            "Incorrect API key provided: \"$secret\". Check the key.",
            "{\"api_key\": \"$secret\", \"message\": \"invalid\"}",
            "{\"access_token\":\"$secret\"}",
            "Authorization: Bearer '$secret'",
        )
        for (message in messages) {
            assertTrue("凭据仍在错误文本里：${message.redactSecrets()}", !message.redactSecrets().contains(secret))
        }
    }

    @Test
    fun `an ordinary message without a value is left alone`() {
        // The word "token"/"key" alone is not a credential: redacting these would mangle
        // the very explanations a user needs to fix their configuration.
        val messages = listOf(
            "token expired",
            "the key is invalid",
            "model not found",
            "响应体过大（超过 1024 KB）",
        )

        messages.forEach { message ->
            assertEquals(message, message.redactSecrets())
        }
    }

    @Test
    fun `a credential in a query string is redacted with its parameter name kept`() {
        // A relay that rejects a balance key usually quotes the URL it was given, and the
        // parameter name is how the user recognises which of his settings is being refused.
        val secret = "fixtureQueryKey1234"
        val messages = listOf(
            "GET https://relay.test/v1/balance?key=$secret was rejected",
            "https://relay.test/v1/balance?userId=7&access_token=$secret",
            "https://relay.test/v1/models?api_key=$secret#fragment",
        )
        for (message in messages) {
            val redacted = message.redactSecrets()
            assertTrue("查询串里的凭据没被抹掉：$redacted", !redacted.contains(secret))
            assertTrue("参数名被一起抹掉了：$redacted", redacted.contains("key=") || redacted.contains("token="))
        }
    }

    @Test
    fun `token shapes that name their own issuer are redacted`() {
        val secrets = listOf(
            "AIzaSyA1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q" to "AIza",
            "github_pat_11ABCDEFG0abcdefghijklmnop" to "github_pat_",
            "ghp_abcdefghijklmnopqrstuvwxyz012345" to "ghp_",
        )
        for ((secret, _) in secrets) {
            assertTrue(
                "自带前缀的 token 没被抹掉：$secret",
                !"the token $secret is revoked".redactSecrets().contains(secret),
            )
        }
    }

    @Test
    fun `a field name followed by an opaque value is redacted`() {
        // The upstream shapes that name a field and then print the value with no punctuation
        // around it were the uncovered case: nothing in the labelled patterns matched, and the
        // value is long enough to be a credential rather than a word.
        val secret = "9f2b7c41d8e35a60b1c2"
        val redacted = "the api key $secret was rejected".redactSecrets()
        assertTrue("裸值没被抹掉：$redacted", !redacted.contains(secret))
        assertTrue("解释用词被吃掉：$redacted", redacted.contains("api key"))
    }

    @Test
    fun `a sentence whose field value is an ordinary word is left alone`() {
        // The other side of the rule above: the minimum length is what keeps redaction from
        // mangling the explanations a user needs.
        val messages = listOf(
            "the key is invalid",
            "token expired, please renew",
            "password too short",
            "secret not set",
        )
        for (message in messages) {
            assertEquals(message, message.redactSecrets())
        }
    }

    // ---- The key never reaches OkHttp's own validation ---------------------

    @Test
    fun `a key that cannot go into a header is refused before okhttp can quote it`() {
        // OkHttp refuses a header value outside the printable range and its exception message
        // quotes the value, which is the key. Building the request happened outside the
        // caller's try, so that message reached the screen and the exported results. The
        // characters only arrive by accident — a tab or a full-width punctuation mark from a
        // paste — so the refusal has to say what is wrong without repeating the key.
        // OkHttp's own rule, which is what this has to agree with: tab is legal in a header
        // value, every other control character and everything past 0x7e is not.
        val api = RelayApi()
        val lineBreak = "sk-live-\nabc12345678"
        val fullWidth = "sk-live-ａｂｃ12345678"

        for (key in listOf(lineBreak, fullWidth)) {
            val problem = api.apiKeyProblem(key)
            assertNotNull("不能进请求头的 Key 必须被拒：$key", problem)
            assertTrue("提示里不能出现密钥本身：$problem", !problem!!.contains(key))
            assertTrue("提示要说明怎么改：$problem", problem.contains("重新粘贴"))
        }
    }

    @Test
    fun `an ordinary key is accepted and an empty one is not judged`() {
        val api = RelayApi()

        assertNull(api.apiKeyProblem("sk-live-9f2b7c41d8e35a60"))
        assertNull("空值由必填校验负责，不在这里报错", api.apiKeyProblem(""))
        assertNull("制表符本身是合法 header 字符", api.apiKeyProblem("k\t1"))
    }

    @Test
    fun `an exception that quotes the key is redacted before it reaches the screen`() {
        // The catch-all of errorForThrowable is the one place a *foreign* message arrives,
        // and some of those messages quote the request they were handed.
        val api = RelayApi()
        val secret = "sk-live-abc12345678"

        val error = api.errorForThrowable(
            IllegalArgumentException("Unexpected char 0x09 in Authorization value: Bearer $secret"),
        )

        assertTrue("凭据还在错误文案里：${error.message}", !error.message.contains(secret))
    }

    // ---- Which headers have to carry a credential placeholder -------------

    @Test
    fun `an ordinary header whose name contains key is accepted`() {
        // The rule was a substring test on "key", so `X-Idempotency-Key: abc` could not be
        // saved at all — the template was refused as if the user had hard-coded a secret.
        val error = BalanceApi().validateTemplate(
            template(headers = listOf(BalanceTemplateHeader("X-Idempotency-Key", "abc-123"))),
        )

        assertNull("普通请求头不应被当成凭据头", error)
    }

    @Test
    fun `a credential header still has to use a placeholder`() {
        // ...and the protection itself stays: a literal key in a real auth header is refused.
        val literal = BalanceApi().validateTemplate(
            template(headers = listOf(BalanceTemplateHeader("X-Auth-Token", "sk-live-1234567890"))),
        )
        val placeholder = BalanceApi().validateTemplate(
            template(headers = listOf(BalanceTemplateHeader("X-Auth-Token", "{{accessToken}}"))),
        )

        assertNotNull("认证头里的字面量密钥必须被拒绝", literal)
        assertNull("占位符写法必须被接受", placeholder)
    }

    // ---- The success marker is either configured or absent -----------------

    @Test
    fun `a success path without an expected value is refused instead of ignored`() {
        // `matchesSuccessFlag` returns true when either half is blank, so a path with no
        // expected value was a check that could never fail — the site's own `success:false`
        // was read as a successful balance.
        val api = BalanceApi()

        assertNotNull(
            "只有一半的成功标记必须被拒绝",
            api.validateTemplate(template(successPath = "success", successExpected = null)),
        )
        assertNotNull(
            "只有一半的成功标记必须被拒绝",
            api.validateTemplate(template(successPath = null, successExpected = "true")),
        )
        assertNull(
            "两半齐全时接受",
            api.validateTemplate(template(successPath = "success", successExpected = "true")),
        )
        assertNull(
            "两半都不填时也接受（等于不检查）",
            api.validateTemplate(template(successPath = null, successExpected = null)),
        )
    }

    // ---- A credential that cannot go into a header -----------------------

    @Test
    fun `a balance credential that cannot go into a header is named, not blamed on the template`() {
        // The balance path had no equivalent of the model-test key check: OkHttp threw from
        // `header()` with a message quoting the value, and the catch-all answered "请检查模板
        // 与供应商配置" — pointing the user at the one thing that was fine.
        val result = runBlocking {
            BalanceApi().query(
                profile = balanceProfile(),
                apiKey = "sk-live-\nabc12345678",
                accessToken = "",
                userId = "",
                template = template(headers = listOf(BalanceTemplateHeader("Authorization", "Bearer {{apiKey}}"))),
            )
        }

        val message = (result as BalanceQueryResult.Failure).message
        assertTrue("要点名凭据并说明怎么改：$message", message.contains("重新粘贴"))
        assertTrue("不能诬陷模板：$message", !message.contains("供应商配置"))
    }

    @Test
    fun `a template header the transport cannot send names that header`() {
        // The other half of the same rule: here the bad character is the user's own literal,
        // so the message has to point at the header rather than at a credential it never saw.
        val result = runBlocking {
            BalanceApi().query(
                profile = balanceProfile(),
                apiKey = "sk-live-9f2b7c41d8e35a60",
                accessToken = "",
                userId = "",
                template = template(headers = listOf(BalanceTemplateHeader("X-Note", "备注"))),
            )
        }

        val message = (result as BalanceQueryResult.Failure).message
        assertTrue("要点名那个请求头：$message", message.contains("X-Note"))
        assertNull(
            "同一份模板的纯 ASCII 值必须照常通过",
            BalanceApi().validateTemplate(
                template(headers = listOf(BalanceTemplateHeader("X-Note", "note-1"))),
            ),
        )
    }

    private fun balanceProfile(): SupplierProfile = SupplierProfile(
        id = "sup-balance",
        name = "balance",
        baseUrl = "https://relay.example/v1",
        protocol = RelayProtocol.CHAT_COMPLETIONS,
        apiKeySecretId = "secret-1",
        models = listOf("m"),
        testSettings = TestSettings(),
    )

    private fun template(
        headers: List<BalanceTemplateHeader> = emptyList(),
        successPath: String? = null,
        successExpected: String? = null,
    ): BalanceQueryTemplate = BalanceQueryTemplate(
        id = "tpl-1",
        name = "tpl",
        description = "",
        method = BalanceHttpMethod.GET,
        endpointTemplate = "{{baseUrl}}/balance",
        headers = headers,
        availablePath = "data.available",
        successPath = successPath,
        successExpectedValue = successExpected,
    )
}
