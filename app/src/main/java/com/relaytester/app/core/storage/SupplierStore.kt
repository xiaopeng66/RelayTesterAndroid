package com.relaytester.app.core.storage

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.relaytester.app.core.model.BalanceHttpMethod
import com.relaytester.app.core.model.BalanceQueryTemplate
import com.relaytester.app.core.model.BalanceTemplateHeader
import com.relaytester.app.core.model.RelayProtocol
import com.relaytester.app.core.model.SupplierProfile
import com.relaytester.app.core.model.TestSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.supplierDataStore by preferencesDataStore(name = "relay_tester_profiles")

data class SupplierStoreState(
    val suppliers: List<SupplierProfile>,
    val activeSupplierId: String?,
    val balanceTemplates: List<BalanceQueryTemplate> = listOf(BalanceQueryTemplate.newApiDefault()),
)

class SupplierStore(private val context: Context) {
    /**
     * DataStore is deliberately resolved at the call site rather than during
     * ViewModel construction. The first access can touch disk, so startup
     * callers invoke this from Dispatchers.IO before publishing UI state.
     */
    suspend fun read(): SupplierStoreState = context.supplierDataStore.data
        .map { preferences ->
            deserialize(preferences[SUPPLIERS_KEY], preferences[ACTIVE_SUPPLIER_KEY])
        }
        .first()

    suspend fun save(state: SupplierStoreState) {
        context.supplierDataStore.edit { preferences ->
            preferences[SUPPLIERS_KEY] = serialize(state)
            state.activeSupplierId?.let { preferences[ACTIVE_SUPPLIER_KEY] = it }
                ?: preferences.remove(ACTIVE_SUPPLIER_KEY)
        }
    }

    private fun serialize(state: SupplierStoreState): String {
        val root = JSONObject()
        val array = JSONArray()
        state.suppliers.forEach { supplier ->
            array.put(
                JSONObject()
                    .put("id", supplier.id)
                    .put("name", supplier.name)
                    .put("baseUrl", supplier.baseUrl)
                    .put("protocol", supplier.protocol.name)
                    .put("apiKeySecretId", supplier.apiKeySecretId)
                    .put("balanceAccessTokenSecretId", supplier.balanceAccessTokenSecretId)
                    .put("balanceUserId", supplier.balanceUserId)
                    .put("models", JSONArray(supplier.models))
                    .put("balanceTemplateId", supplier.balanceTemplateId)
                    .put(
                        "settings",
                        JSONObject()
                            .put("timeoutSeconds", supplier.testSettings.timeoutSeconds)
                            .put("concurrency", supplier.testSettings.concurrency)
                            .put("prompt", supplier.testSettings.prompt)
                            .put("keyword", supplier.testSettings.keyword)
                            .put("maxTokens", supplier.testSettings.maxTokens)
                            .put("retryCount", supplier.testSettings.retryCount)
                            .put("delayMinMs", supplier.testSettings.delayMinMs)
                            .put("delayMaxMs", supplier.testSettings.delayMaxMs)
                            .put("batchSize", supplier.testSettings.batchSize)
                            .put("batchPauseMs", supplier.testSettings.batchPauseMs),
                    ),
            )
        }
        val templateArray = JSONArray()
        state.balanceTemplates.forEach { template ->
            val headerArray = JSONArray()
            template.headers.forEach { header ->
                headerArray.put(
                    JSONObject()
                        .put("name", header.name)
                        .put("valueTemplate", header.valueTemplate),
                )
            }
            templateArray.put(
                JSONObject()
                    .put("id", template.id)
                    .put("name", template.name)
                    .put("description", template.description)
                    .put("method", template.method.name)
                    .put("endpointTemplate", template.endpointTemplate)
                    .put("headers", headerArray)
                    .put("requestBodyTemplate", template.requestBodyTemplate)
                    .put("availablePath", template.availablePath)
                    .put("usedPath", template.usedPath)
                    .put("totalPath", template.totalPath)
                    .put("deriveTotalFromAvailableAndUsed", template.deriveTotalFromAvailableAndUsed)
                    .put("currencyPath", template.currencyPath)
                    .put("planNamePath", template.planNamePath)
                    .put("unitLabel", template.unitLabel)
                    .put("scaleDivisor", template.scaleDivisor)
                    .put("successPath", template.successPath)
                    .put("successExpectedValue", template.successExpectedValue)
                    .put("builtIn", template.builtIn)
                    .put("createdAt", template.createdAt)
                    .put("updatedAt", template.updatedAt),
            )
        }
        return root
            .put("suppliers", array)
            .put("balanceTemplates", templateArray)
            .toString()
    }

    private fun deserialize(raw: String?, activeSupplierId: String?): SupplierStoreState {
        val root = runCatching { JSONObject(raw ?: "{}") }.getOrDefault(JSONObject())
        val suppliers = runCatching {
            val array = root.optJSONArray("suppliers") ?: JSONArray()
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id")
                    if (id.isBlank()) continue
                    add(item.toSupplier())
                }
            }
        }.getOrDefault(emptyList())
        // Existing installs have no balanceTemplates key. Seed exactly one
        // editable-by-copy built-in template during that migration only.
        val templates = runCatching {
            if (!root.has("balanceTemplates")) {
                listOf(BalanceQueryTemplate.newApiDefault())
            } else {
                val array = root.optJSONArray("balanceTemplates") ?: JSONArray()
                buildList {
                    for (index in 0 until array.length()) {
                        array.optJSONObject(index)?.toBalanceTemplate()?.let(::add)
                    }
                }.distinctBy { it.id }
            }
        }
            .getOrDefault(listOf(BalanceQueryTemplate.newApiDefault()))
            .ifEmpty { listOf(BalanceQueryTemplate.newApiDefault()) }
        // Built-ins are versioned application defaults. They cannot be edited in
        // place (the editor always creates a copy), so it is safe to refresh the
        // new-api definition when credentials/header support is upgraded while
        // leaving every custom template untouched.
        val refreshedTemplates = templates.map { template ->
            if (template.builtIn && template.id == BalanceQueryTemplate.NEW_API_TEMPLATE_ID) {
                BalanceQueryTemplate.newApiDefault()
            } else {
                template
            }
        }
        return SupplierStoreState(
            suppliers = suppliers,
            activeSupplierId = activeSupplierId?.takeIf { id -> suppliers.any { it.id == id } },
            balanceTemplates = refreshedTemplates,
        )
    }

    private fun JSONObject.toSupplier(): SupplierProfile {
        val modelArray = optJSONArray("models") ?: JSONArray()
        val models = buildList {
            for (index in 0 until modelArray.length()) {
                modelArray.optString(index).takeIf(String::isNotBlank)?.let(::add)
            }
        }
        val settings = optJSONObject("settings") ?: JSONObject()
        return SupplierProfile(
            id = optString("id"),
            name = optString("name").ifBlank { "未命名供应商" },
            baseUrl = optString("baseUrl"),
            protocol = runCatching {
                RelayProtocol.valueOf(optString("protocol"))
            }.getOrDefault(RelayProtocol.CHAT_COMPLETIONS),
            apiKeySecretId = optString("apiKeySecretId").takeIf(String::isNotBlank),
            balanceAccessTokenSecretId = optString("balanceAccessTokenSecretId")
                .takeIf(String::isNotBlank),
            balanceUserId = optString("balanceUserId"),
            models = models.distinct().sorted(),
            testSettings = TestSettings(
                timeoutSeconds = settings.optInt("timeoutSeconds", 20).coerceIn(3, 120),
                concurrency = settings.optInt("concurrency", 2).coerceIn(1, 20),
                prompt = settings.optString("prompt", "ping").ifBlank { "ping" },
                keyword = settings.optString("keyword", ""),
                maxTokens = settings.optInt("maxTokens", 4).coerceIn(1, 64),
                retryCount = settings.optInt("retryCount", 0).coerceIn(0, 5),
                delayMinMs = settings.optLong("delayMinMs", 500).coerceIn(0, 10_000),
                delayMaxMs = settings.optLong("delayMaxMs", 2_000).coerceIn(0, 10_000),
                batchSize = settings.optInt("batchSize", 10).coerceIn(1, 200),
                batchPauseMs = settings.optLong("batchPauseMs", 3_000).coerceIn(0, 60_000),
            ),
            balanceTemplateId = optString("balanceTemplateId").takeIf(String::isNotBlank),
        )
    }

    private fun JSONObject.toBalanceTemplate(): BalanceQueryTemplate? {
        val id = optString("id").takeIf(String::isNotBlank) ?: return null
        val headerArray = optJSONArray("headers") ?: JSONArray()
        val headers = buildList {
            for (index in 0 until headerArray.length()) {
                val item = headerArray.optJSONObject(index) ?: continue
                val name = item.optString("name").trim()
                if (name.isNotEmpty()) {
                    add(BalanceTemplateHeader(name, item.optString("valueTemplate")))
                }
            }
        }
        return BalanceQueryTemplate(
            id = id,
            name = optString("name").ifBlank { "未命名模板" },
            description = optString("description"),
            method = runCatching {
                BalanceHttpMethod.valueOf(optString("method"))
            }.getOrDefault(BalanceHttpMethod.GET),
            endpointTemplate = optString("endpointTemplate"),
            headers = headers,
            requestBodyTemplate = optString("requestBodyTemplate").takeIf(String::isNotBlank),
            availablePath = optString("availablePath"),
            usedPath = optString("usedPath").takeIf(String::isNotBlank),
            totalPath = optString("totalPath").takeIf(String::isNotBlank),
            deriveTotalFromAvailableAndUsed = optBoolean("deriveTotalFromAvailableAndUsed", false),
            currencyPath = optString("currencyPath").takeIf(String::isNotBlank),
            planNamePath = optString("planNamePath").takeIf(String::isNotBlank),
            unitLabel = optString("unitLabel").ifBlank { "额度" },
            scaleDivisor = optDouble("scaleDivisor", 1.0).takeIf { it.isFinite() && it > 0 } ?: 1.0,
            successPath = optString("successPath").takeIf(String::isNotBlank),
            successExpectedValue = optString("successExpectedValue").takeIf(String::isNotBlank),
            builtIn = optBoolean("builtIn", false),
            createdAt = optLong("createdAt", System.currentTimeMillis()),
            updatedAt = optLong("updatedAt", System.currentTimeMillis()),
        )
    }

    private companion object {
        val SUPPLIERS_KEY: Preferences.Key<String> = stringPreferencesKey("suppliers_json")
        val ACTIVE_SUPPLIER_KEY: Preferences.Key<String> = stringPreferencesKey("active_supplier_id")
    }
}
