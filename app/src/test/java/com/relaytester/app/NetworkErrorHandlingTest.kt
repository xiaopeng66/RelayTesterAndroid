package com.relaytester.app

import com.relaytester.app.core.model.BalanceHttpMethod
import com.relaytester.app.core.model.BalanceQueryTemplate
import com.relaytester.app.core.model.ErrorKind
import com.relaytester.app.core.model.BalanceTemplateHeader
import com.relaytester.app.core.network.BalanceApi
import com.relaytester.app.core.network.RelayApi
import com.relaytester.app.core.network.redactSecrets
import java.io.IOException
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
