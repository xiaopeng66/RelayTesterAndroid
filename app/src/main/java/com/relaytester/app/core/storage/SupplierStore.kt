package com.relaytester.app.core.storage

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.relaytester.app.core.model.BalanceHttpMethod
import com.relaytester.app.core.model.BalanceQueryMode
import com.relaytester.app.core.model.BalanceQueryTemplate
import com.relaytester.app.core.model.BalanceSnapshot
import com.relaytester.app.core.model.BalanceTemplateHeader
import com.relaytester.app.core.model.ModelCatalogEntry
import com.relaytester.app.core.model.ModelSource
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
    /** User-facing model aliases with one or more supplier-backed sources. */
    val modelCatalog: List<ModelCatalogEntry> = emptyList(),
    /** Successful balance responses are display-only local cache, never credentials. */
    val balanceSnapshots: Map<String, BalanceSnapshot> = emptyMap(),
    /** Model-name filters are shared by every supplier. */
    val modelFilterKeyword: String = "",
    val quickFilterTerms: List<String> = emptyList(),
)

open class SupplierStore(private val context: Context?) {
    /**
     * DataStore is deliberately resolved at the call site rather than during
     * ViewModel construction. The first access can touch disk, so startup
     * callers invoke this from Dispatchers.IO before publishing UI state.
     */
    open suspend fun read(): SupplierStoreState = requireNotNull(context).supplierDataStore.data
        .map { preferences ->
            deserialize(preferences[SUPPLIERS_KEY], preferences[ACTIVE_SUPPLIER_KEY])
        }
        .first()

    open suspend fun save(state: SupplierStoreState) {
        requireNotNull(context).supplierDataStore.edit { preferences ->
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
                            .put("quickFilterTerms", JSONArray(supplier.testSettings.quickFilterTerms))
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
                    .put("queryMode", template.queryMode.name)
                    .put("scriptCode", template.scriptCode)
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
        val snapshotArray = JSONArray()
        state.balanceSnapshots
            .toSortedMap()
            .values
            .forEach { snapshot -> snapshotArray.put(snapshot.toJson()) }
        val catalogArray = JSONArray()
        state.modelCatalog.forEach { entry ->
            val sourceArray = JSONArray()
            entry.sources.forEach { source ->
                sourceArray.put(
                    JSONObject()
                        .put("supplierId", source.supplierId)
                        .put("modelId", source.modelId),
                )
            }
            catalogArray.put(
                JSONObject()
                    .put("id", entry.id)
                    .put("name", entry.name)
                    .put("sources", sourceArray),
            )
        }
        return root
            .put("suppliers", array)
            .put("balanceTemplates", templateArray)
            .put("modelCatalog", catalogArray)
            .put("balanceSnapshots", snapshotArray)
            .put(
                "modelFilters",
                JSONObject()
                    .put("keyword", state.modelFilterKeyword)
                    .put("quickFilterTerms", JSONArray(state.quickFilterTerms)),
            )
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
        val modelCatalog = runCatching {
            val array = root.optJSONArray("modelCatalog") ?: JSONArray()
            buildList {
                for (index in 0 until minOf(array.length(), MAX_MODEL_CATALOG_ENTRIES)) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id").trim().takeIf(String::isNotEmpty) ?: continue
                    val name = item.optString("name").trim().takeIf(String::isNotEmpty) ?: continue
                    val sourceArray = item.optJSONArray("sources") ?: JSONArray()
                    val sources = buildList {
                        for (sourceIndex in 0 until minOf(sourceArray.length(), MAX_MODEL_SOURCES_PER_ENTRY)) {
                            val source = sourceArray.optJSONObject(sourceIndex) ?: continue
                            val supplierId = source.optString("supplierId").trim()
                            val modelId = source.optString("modelId").trim()
                            if (supplierId.isNotEmpty() && modelId.isNotEmpty()) {
                                add(ModelSource(supplierId, modelId))
                            }
                        }
                    }.distinctBy { it.supplierId + "\u0000" + it.modelId }
                    if (sources.isNotEmpty()) add(ModelCatalogEntry(id, name, sources))
                }
            }.distinctBy { it.id }
        }.getOrDefault(emptyList())
        val snapshots = runCatching {
            val array = root.optJSONArray("balanceSnapshots") ?: JSONArray()
            buildMap {
                for (index in 0 until minOf(array.length(), MAX_BALANCE_SNAPSHOTS)) {
                    array.optJSONObject(index)?.toBalanceSnapshot()?.let { snapshot ->
                        put(snapshot.supplierId, snapshot)
                    }
                }
            }
        }.getOrDefault(emptyMap())
        val resolvedActiveId = activeSupplierId?.takeIf { id -> suppliers.any { it.id == id } }
            ?: suppliers.firstOrNull()?.id
        val modelFilters = root.optJSONObject("modelFilters")
        val legacyActive = suppliers.firstOrNull { it.id == resolvedActiveId }
        val modelFilterKeyword = modelFilters
            ?.optString("keyword", "")
            ?.take(512)
            ?: legacyActive?.testSettings?.keyword.orEmpty()
        val legacyQuickFilterTerms = suppliers.flatMap { it.testSettings.quickFilterTerms }
        val quickFilterTerms = modelFilters
            ?.optJSONArray("quickFilterTerms")
            ?.let { array -> readQuickFilterTerms(array) }
            ?: readQuickFilterTerms(JSONArray(legacyQuickFilterTerms))
        return SupplierStoreState(
            suppliers = suppliers,
            activeSupplierId = resolvedActiveId,
            balanceTemplates = refreshedTemplates,
            modelCatalog = modelCatalog.mapNotNull { entry ->
                val validSources = entry.sources.filter { source ->
                    suppliers.any { it.id == source.supplierId }
                }
                entry.copy(sources = validSources).takeIf { validSources.isNotEmpty() }
            },
            balanceSnapshots = snapshots,
            modelFilterKeyword = modelFilterKeyword,
            quickFilterTerms = quickFilterTerms,
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
                quickFilterTerms = readQuickFilterTerms(settings.optJSONArray("quickFilterTerms")),
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

    private fun BalanceSnapshot.toJson(): JSONObject = JSONObject()
        .put("supplierId", supplierId)
        .put("templateId", templateId)
        .put("templateName", templateName.take(MAX_TEMPLATE_NAME_LENGTH))
        .put("availableRaw", availableRaw)
        .put("usedRaw", usedRaw)
        .put("totalRaw", totalRaw)
        .put("currency", currency?.take(MAX_CURRENCY_LENGTH))
        .put("planName", planName?.take(MAX_PLAN_NAME_LENGTH))
        .put("unitLabel", unitLabel.take(MAX_UNIT_LABEL_LENGTH))
        .put("scaleDivisor", scaleDivisor)
        .put("checkedAt", checkedAt)
        .put("latencyMs", latencyMs)

    private fun JSONObject.toBalanceSnapshot(): BalanceSnapshot? {
        val supplierId = optString("supplierId").trim().takeIf(String::isNotEmpty) ?: return null
        val templateId = optString("templateId").trim().takeIf(String::isNotEmpty) ?: return null
        val available = optDouble("availableRaw", Double.NaN).takeIf(Double::isFinite) ?: return null
        val divisor = optDouble("scaleDivisor", 1.0).takeIf { it.isFinite() && it > 0 } ?: return null
        val checkedAt = optLong("checkedAt", 0L).takeIf { it > 0 } ?: return null
        fun optionalFinite(name: String): Double? = if (has(name) && !isNull(name)) {
            optDouble(name, Double.NaN).takeIf(Double::isFinite)
        } else {
            null
        }
        return BalanceSnapshot(
            supplierId = supplierId,
            templateId = templateId,
            templateName = optString("templateName").ifBlank { "已保存模板" }.take(MAX_TEMPLATE_NAME_LENGTH),
            availableRaw = available,
            usedRaw = optionalFinite("usedRaw"),
            totalRaw = optionalFinite("totalRaw"),
            currency = optString("currency").trim().takeIf(String::isNotEmpty)?.take(MAX_CURRENCY_LENGTH),
            planName = optString("planName").trim().takeIf(String::isNotEmpty)?.take(MAX_PLAN_NAME_LENGTH),
            unitLabel = optString("unitLabel").ifBlank { "额度" }.take(MAX_UNIT_LABEL_LENGTH),
            scaleDivisor = divisor,
            checkedAt = checkedAt,
            latencyMs = optLong("latencyMs", 0L).coerceAtLeast(0L),
        )
    }

    private fun readQuickFilterTerms(array: JSONArray?): List<String> = buildList {
        val source = array ?: return@buildList
        for (index in 0 until minOf(source.length(), MAX_QUICK_FILTER_TERMS)) {
            source.optString(index)
                .trim()
                .take(MAX_QUICK_FILTER_TERM_LENGTH)
                .takeIf(String::isNotEmpty)
                ?.let(::add)
        }
    }
        .distinctBy { it.lowercase() }

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
            queryMode = runCatching {
                BalanceQueryMode.valueOf(optString("queryMode"))
            }.getOrDefault(BalanceQueryMode.FORM),
            scriptCode = optString("scriptCode").takeIf(String::isNotBlank)?.take(MAX_SCRIPT_CODE_LENGTH),
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
        const val MAX_BALANCE_SNAPSHOTS = 128
        const val MAX_MODEL_CATALOG_ENTRIES = 128
        const val MAX_MODEL_SOURCES_PER_ENTRY = 32
        const val MAX_QUICK_FILTER_TERMS = 16
        const val MAX_QUICK_FILTER_TERM_LENGTH = 128
        const val MAX_TEMPLATE_NAME_LENGTH = 256
        const val MAX_CURRENCY_LENGTH = 64
        const val MAX_PLAN_NAME_LENGTH = 256
        const val MAX_UNIT_LABEL_LENGTH = 64
        const val MAX_SCRIPT_CODE_LENGTH = 16 * 1024
        val SUPPLIERS_KEY: Preferences.Key<String> = stringPreferencesKey("suppliers_json")
        val ACTIVE_SUPPLIER_KEY: Preferences.Key<String> = stringPreferencesKey("active_supplier_id")
    }
}
