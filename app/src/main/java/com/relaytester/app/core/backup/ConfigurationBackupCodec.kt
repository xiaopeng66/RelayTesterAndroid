package com.relaytester.app.core.backup

import com.relaytester.app.core.model.BalanceHttpMethod
import com.relaytester.app.core.model.BalanceQueryMode
import com.relaytester.app.core.model.BalanceQueryTemplate
import com.relaytester.app.core.model.BalanceTemplateHeader
import com.relaytester.app.core.model.ModelCatalogEntry
import com.relaytester.app.core.model.ModelSource
import com.relaytester.app.core.model.RelayProtocol
import com.relaytester.app.core.model.TestSettings
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * Plaintext configuration held only while a user-initiated backup is encrypted
 * or restored. Keystore IDs are intentionally not part of this model because
 * they are device-specific and must be regenerated during import.
 */
data class ConfigurationBackup(
    val createdAt: Long,
    val activeSupplierId: String?,
    val suppliers: List<ConfigurationBackupSupplier>,
    val balanceTemplates: List<BalanceQueryTemplate>,
    val modelCatalog: List<ModelCatalogEntry> = emptyList(),
    val modelFilterKeyword: String = "",
    val quickFilterTerms: List<String> = emptyList(),
)

data class ConfigurationBackupSupplier(
    val id: String,
    val name: String,
    val baseUrl: String,
    val protocol: RelayProtocol,
    val apiKey: String,
    val models: List<String>,
    val testSettings: TestSettings,
    val balanceTemplateId: String?,
    val balanceAccessToken: String,
    val balanceUserId: String,
)

data class ConfigurationBackupPreview(
    val createdAt: Long,
    val supplierCount: Int,
    val customTemplateCount: Int,
    val apiKeyCount: Int,
    val balanceTokenCount: Int,
)

class ConfigurationBackupException(message: String) : Exception(message)

/**
 * Versioned, password-encrypted configuration archive codec.
 *
 * The outer JSON envelope deliberately contains no names, addresses, templates
 * or credentials. It only stores KDF/cipher parameters and AES-GCM ciphertext.
 */
object ConfigurationBackupCodec {
    const val MIN_PASSWORD_LENGTH = 12
    const val MAX_BACKUP_BYTES = 5 * 1024 * 1024

    private const val FORMAT = "relay-tester-backup"
    private const val VERSION = 1
    private const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val CIPHER_ALGORITHM = "AES/GCM/NoPadding"
    private const val AES_ALGORITHM = "AES"
    private const val PBKDF2_ITERATIONS = 210_000
    private const val SALT_LENGTH = 16
    private const val IV_LENGTH = 12
    private const val KEY_LENGTH_BITS = 256
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val MAX_SUPPLIERS = 100
    private const val MAX_TEMPLATES = 100
    private const val MAX_MODELS_PER_SUPPLIER = 2_000
    private const val MAX_QUICK_FILTER_TERMS = 16
    private const val MAX_QUICK_FILTER_TERM_LENGTH = 128
    private const val MAX_HEADERS_PER_TEMPLATE = 50
    private const val MAX_MODEL_CATALOG_ENTRIES = 128
    private const val MAX_MODEL_SOURCES_PER_ENTRY = 32

    fun encrypt(backup: ConfigurationBackup, password: CharArray): ByteArray {
        return try {
            if (password.size < MIN_PASSWORD_LENGTH) {
                throw ConfigurationBackupException("备份密码至少需要 $MIN_PASSWORD_LENGTH 个字符")
            }
            val salt = ByteArray(SALT_LENGTH).also(SecureRandom()::nextBytes)
            val iv = ByteArray(IV_LENGTH).also(SecureRandom()::nextBytes)
            val key = deriveKey(password, salt, PBKDF2_ITERATIONS)
            val cipher = Cipher.getInstance(CIPHER_ALGORITHM).apply {
                init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            }
            val payload = cipher.doFinal(backup.toJson().toString().toByteArray(Charsets.UTF_8))
            JSONObject()
                .put("format", FORMAT)
                .put("version", VERSION)
                .put(
                    "kdf",
                    JSONObject()
                        .put("algorithm", KDF_ALGORITHM)
                        .put("iterations", PBKDF2_ITERATIONS)
                        .put("salt", salt.toBase64()),
                )
                .put(
                    "cipher",
                    JSONObject()
                        .put("algorithm", CIPHER_ALGORITHM)
                        .put("iv", iv.toBase64())
                        .put("payload", payload.toBase64()),
                )
                .toString()
                .toByteArray(Charsets.UTF_8)
        } catch (error: ConfigurationBackupException) {
            throw error
        } catch (error: GeneralSecurityException) {
            throw ConfigurationBackupException("无法创建加密备份")
        } finally {
            password.fill('\u0000')
        }
    }

    fun decrypt(raw: ByteArray, password: CharArray): ConfigurationBackup {
        return try {
            if (raw.size !in 1..MAX_BACKUP_BYTES) {
                throw ConfigurationBackupException("备份文件大小无效")
            }
            if (password.size < MIN_PASSWORD_LENGTH) {
                throw ConfigurationBackupException("请输入至少 $MIN_PASSWORD_LENGTH 个字符的备份密码")
            }
            val envelope = JSONObject(raw.toString(Charsets.UTF_8))
            if (envelope.optString("format") != FORMAT || envelope.optInt("version", -1) != VERSION) {
                throw ConfigurationBackupException("不是受支持的 Relay Tester 备份文件")
            }
            val kdf = envelope.optJSONObject("kdf")
                ?: throw ConfigurationBackupException("备份文件缺少加密参数")
            val cipherConfig = envelope.optJSONObject("cipher")
                ?: throw ConfigurationBackupException("备份文件缺少加密内容")
            if (kdf.optString("algorithm") != KDF_ALGORITHM ||
                cipherConfig.optString("algorithm") != CIPHER_ALGORITHM ||
                kdf.optInt("iterations", -1) != PBKDF2_ITERATIONS
            ) {
                throw ConfigurationBackupException("备份文件使用了不受支持的加密格式")
            }
            val salt = kdf.optString("salt").fromBase64(SALT_LENGTH, "盐值")
            val iv = cipherConfig.optString("iv").fromBase64(IV_LENGTH, "初始化向量")
            val payload = cipherConfig.optString("payload").fromBase64(null, "密文")
            val key = deriveKey(password, salt, PBKDF2_ITERATIONS)
            val plaintext = Cipher.getInstance(CIPHER_ALGORITHM).run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
                doFinal(payload)
            }
            fromJson(JSONObject(plaintext.toString(Charsets.UTF_8)))
        } catch (error: ConfigurationBackupException) {
            throw error
        } catch (_: GeneralSecurityException) {
            throw ConfigurationBackupException("密码不正确或备份文件已损坏")
        } catch (_: Throwable) {
            throw ConfigurationBackupException("密码不正确或备份文件已损坏")
        } finally {
            password.fill('\u0000')
        }
    }

    fun preview(backup: ConfigurationBackup): ConfigurationBackupPreview = ConfigurationBackupPreview(
        createdAt = backup.createdAt,
        supplierCount = backup.suppliers.size,
        customTemplateCount = backup.balanceTemplates.count { !it.builtIn },
        apiKeyCount = backup.suppliers.count { it.apiKey.isNotBlank() },
        balanceTokenCount = backup.suppliers.count { it.balanceAccessToken.isNotBlank() },
    )

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, KEY_LENGTH_BITS)
        return try {
            SecretKeySpec(
                SecretKeyFactory.getInstance(KDF_ALGORITHM).generateSecret(spec).encoded,
                AES_ALGORITHM,
            )
        } finally {
            spec.clearPassword()
        }
    }

    private fun ConfigurationBackup.toJson(): JSONObject = JSONObject()
        .put("schemaVersion", VERSION)
        .put("createdAt", createdAt)
        .put("activeSupplierId", activeSupplierId)
        .put("suppliers", JSONArray(suppliers.map { supplier -> supplier.toJson() }))
        .put("balanceTemplates", JSONArray(balanceTemplates.map { template -> template.toJson() }))
        .put(
            "modelCatalog",
            JSONArray(modelCatalog.map { entry ->
                JSONObject()
                    .put("id", entry.id)
                    .put("name", entry.name)
                    .put(
                        "sources",
                        JSONArray(entry.sources.map { source ->
                            JSONObject()
                                .put("supplierId", source.supplierId)
                                .put("modelId", source.modelId)
                        }),
                    )
            }),
        )
        .put(
            "modelFilters",
            JSONObject()
                .put("keyword", modelFilterKeyword)
                .put("quickFilterTerms", JSONArray(quickFilterTerms)),
        )

    private fun ConfigurationBackupSupplier.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("baseUrl", baseUrl)
        .put("protocol", protocol.name)
        .put("apiKey", apiKey)
        .put("models", JSONArray(models))
        .put("balanceTemplateId", balanceTemplateId)
        .put("balanceAccessToken", balanceAccessToken)
        .put("balanceUserId", balanceUserId)
        .put(
            "settings",
            JSONObject()
                .put("timeoutSeconds", testSettings.timeoutSeconds)
                .put("concurrency", testSettings.concurrency)
                .put("prompt", testSettings.prompt)
                .put("keyword", testSettings.keyword)
                .put("quickFilterTerms", JSONArray(testSettings.quickFilterTerms))
                .put("maxTokens", testSettings.maxTokens)
                .put("retryCount", testSettings.retryCount)
                .put("delayMinMs", testSettings.delayMinMs)
                .put("delayMaxMs", testSettings.delayMaxMs)
                .put("batchSize", testSettings.batchSize)
                .put("batchPauseMs", testSettings.batchPauseMs),
        )

    private fun BalanceQueryTemplate.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("description", description)
        .put("queryMode", queryMode.name)
        .put("scriptCode", scriptCode)
        .put("method", method.name)
        .put("endpointTemplate", endpointTemplate)
        .put(
            "headers",
            JSONArray(headers.map { header ->
                JSONObject().put("name", header.name).put("valueTemplate", header.valueTemplate)
            }),
        )
        .put("requestBodyTemplate", requestBodyTemplate)
        .put("availablePath", availablePath)
        .put("usedPath", usedPath)
        .put("totalPath", totalPath)
        .put("deriveTotalFromAvailableAndUsed", deriveTotalFromAvailableAndUsed)
        .put("currencyPath", currencyPath)
        .put("planNamePath", planNamePath)
        .put("unitLabel", unitLabel)
        .put("scaleDivisor", scaleDivisor)
        .put("successPath", successPath)
        .put("successExpectedValue", successExpectedValue)
        .put("builtIn", builtIn)
        .put("createdAt", createdAt)
        .put("updatedAt", updatedAt)

    private fun fromJson(root: JSONObject): ConfigurationBackup {
        if (root.optInt("schemaVersion", -1) != VERSION) {
            throw ConfigurationBackupException("备份内容版本不受支持")
        }
        val supplierArray = root.optJSONArray("suppliers")
            ?: throw ConfigurationBackupException("备份中没有供应商配置")
        val templateArray = root.optJSONArray("balanceTemplates")
            ?: throw ConfigurationBackupException("备份中没有余额模板")
        if (supplierArray.length() !in 1..MAX_SUPPLIERS || templateArray.length() !in 1..MAX_TEMPLATES) {
            throw ConfigurationBackupException("备份中的配置数量超出安全限制")
        }
        val suppliers = buildList {
            for (index in 0 until supplierArray.length()) {
                val item = supplierArray.optJSONObject(index)
                    ?: throw ConfigurationBackupException("第 ${index + 1} 个供应商格式无效")
                add(item.toBackupSupplier())
            }
        }
        if (suppliers.map(ConfigurationBackupSupplier::id).distinct().size != suppliers.size) {
            throw ConfigurationBackupException("备份中存在重复的供应商标识")
        }
        val templates = buildList {
            for (index in 0 until templateArray.length()) {
                val item = templateArray.optJSONObject(index)
                    ?: throw ConfigurationBackupException("第 ${index + 1} 个余额模板格式无效")
                add(item.toBalanceTemplate())
            }
        }
        if (templates.map(BalanceQueryTemplate::id).distinct().size != templates.size) {
            throw ConfigurationBackupException("备份中存在重复的余额模板标识")
        }
        val withBuiltIn = templates
            .filterNot { it.id == BalanceQueryTemplate.NEW_API_TEMPLATE_ID }
            .let { custom -> listOf(BalanceQueryTemplate.newApiDefault()) + custom }
        val templateIds = withBuiltIn.map(BalanceQueryTemplate::id).toSet()
        val normalizedSuppliers = suppliers.map { supplier ->
            supplier.copy(
                balanceTemplateId = supplier.balanceTemplateId?.takeIf(templateIds::contains)
                    ?: BalanceQueryTemplate.NEW_API_TEMPLATE_ID,
            )
        }
        val supplierIds = normalizedSuppliers.mapTo(mutableSetOf()) { it.id }
        val modelCatalog = root.optJSONArray("modelCatalog")?.let { catalogArray ->
            if (catalogArray.length() > MAX_MODEL_CATALOG_ENTRIES) {
                throw ConfigurationBackupException("备份中的模型来源数量超出安全限制")
            }
            buildList {
                for (index in 0 until catalogArray.length()) {
                    val item = catalogArray.optJSONObject(index)
                        ?: throw ConfigurationBackupException("第 ${index + 1} 个模型来源格式无效")
                    val id = item.requiredBoundedString("id", 128)
                    val name = item.requiredBoundedString("name", 256)
                    val sourceArray = item.optJSONArray("sources") ?: JSONArray()
                    if (sourceArray.length() !in 1..MAX_MODEL_SOURCES_PER_ENTRY) {
                        throw ConfigurationBackupException("模型“$name”的供应商来源数量无效")
                    }
                    val sources = buildList {
                        for (sourceIndex in 0 until sourceArray.length()) {
                            val source = sourceArray.optJSONObject(sourceIndex)
                                ?: throw ConfigurationBackupException("模型“$name”的供应商来源格式无效")
                            val supplierId = source.requiredBoundedString("supplierId", 128)
                                .takeIf { it in supplierIds }
                                ?: throw ConfigurationBackupException("模型“$name”引用了不存在的供应商")
                            val modelId = source.requiredBoundedString("modelId", 256)
                            add(ModelSource(supplierId, modelId))
                        }
                    }.distinctBy { it.supplierId + "\u0000" + it.modelId }
                    add(ModelCatalogEntry(id, name, sources))
                }
            }.distinctBy { it.id }
        } ?: emptyList()
        val activeId = root.optionalBoundedString("activeSupplierId", 128)
            ?.takeIf { candidate -> normalizedSuppliers.any { it.id == candidate } }
            ?: normalizedSuppliers.first().id
        val modelFilters = root.optJSONObject("modelFilters")
        val legacyFilters = normalizedSuppliers.firstOrNull { it.id == activeId }?.testSettings
        val legacyQuickFilterTerms = normalizedSuppliers.flatMap { it.testSettings.quickFilterTerms }
        return ConfigurationBackup(
            createdAt = root.optLong("createdAt", 0L).takeIf { it > 0 } ?: 0L,
            activeSupplierId = activeId,
            suppliers = normalizedSuppliers,
            balanceTemplates = withBuiltIn,
            modelCatalog = modelCatalog,
            modelFilterKeyword = modelFilters
                ?.optionalBoundedString("keyword", 512)
                ?: legacyFilters?.keyword.orEmpty(),
            quickFilterTerms = modelFilters
                ?.optJSONArray("quickFilterTerms")
                ?.let { array ->
                    if (array.length() > MAX_QUICK_FILTER_TERMS) {
                        throw ConfigurationBackupException("备份中的快捷筛选词数量超出安全限制")
                    }
                    buildList {
                        for (index in 0 until array.length()) {
                            val value = array.optString(index).trim()
                            if (value.isEmpty() || value.length > MAX_QUICK_FILTER_TERM_LENGTH) {
                                throw ConfigurationBackupException("备份中包含无效快捷筛选词")
                            }
                            add(value)
                    }
                    }.distinctBy { it.lowercase() }
                }
                ?: legacyQuickFilterTerms.distinctBy { it.lowercase() },
        )
    }

    private fun JSONObject.toBackupSupplier(): ConfigurationBackupSupplier {
        val id = requiredBoundedString("id", 128)
        val modelsArray = optJSONArray("models") ?: JSONArray()
        if (modelsArray.length() > MAX_MODELS_PER_SUPPLIER) {
            throw ConfigurationBackupException("供应商“$id”的模型数量超出安全限制")
        }
        val models = buildList {
            for (index in 0 until modelsArray.length()) {
                val value = modelsArray.optString(index)
                if (value.isBlank() || value.length > 256) {
                    throw ConfigurationBackupException("供应商“$id”包含无效模型名称")
                }
                add(value)
            }
        }.distinct().sorted()
        val settings = optJSONObject("settings") ?: JSONObject()
        val quickFilterArray = settings.optJSONArray("quickFilterTerms") ?: JSONArray()
        if (quickFilterArray.length() > MAX_QUICK_FILTER_TERMS) {
            throw ConfigurationBackupException("供应商“$id”的快捷筛选词数量超出安全限制")
        }
        val quickFilterTerms = buildList {
            for (index in 0 until quickFilterArray.length()) {
                val value = quickFilterArray.optString(index).trim()
                if (value.isEmpty() || value.length > MAX_QUICK_FILTER_TERM_LENGTH) {
                    throw ConfigurationBackupException("供应商“$id”包含无效快捷筛选词")
                }
                add(value)
            }
        }.distinctBy { it.lowercase() }
        return ConfigurationBackupSupplier(
            id = id,
            name = requiredBoundedString("name", 120),
            baseUrl = optionalBoundedString("baseUrl", 2_048).orEmpty(),
            protocol = runCatching { RelayProtocol.valueOf(requiredBoundedString("protocol", 32)) }
                .getOrElse { throw ConfigurationBackupException("供应商“$id”的协议无效") },
            apiKey = optionalBoundedString("apiKey", 8_192).orEmpty(),
            models = models,
            testSettings = TestSettings(
                timeoutSeconds = settings.optInt("timeoutSeconds", 20).coerceIn(3, 120),
                concurrency = settings.optInt("concurrency", 2).coerceIn(1, 20),
                prompt = settings.optionalBoundedString("prompt", 4_096).orEmpty().ifBlank { "ping" },
                keyword = settings.optionalBoundedString("keyword", 512).orEmpty(),
                quickFilterTerms = quickFilterTerms,
                maxTokens = settings.optInt("maxTokens", 4).coerceIn(1, 64),
                retryCount = settings.optInt("retryCount", 0).coerceIn(0, 5),
                delayMinMs = settings.optLong("delayMinMs", 500).coerceIn(0, 10_000),
                delayMaxMs = settings.optLong("delayMaxMs", 2_000).coerceIn(0, 10_000),
                batchSize = settings.optInt("batchSize", 10).coerceIn(1, 200),
                batchPauseMs = settings.optLong("batchPauseMs", 3_000).coerceIn(0, 60_000),
            ),
            balanceTemplateId = optionalBoundedString("balanceTemplateId", 128),
            balanceAccessToken = optionalBoundedString("balanceAccessToken", 8_192).orEmpty(),
            balanceUserId = optionalBoundedString("balanceUserId", 512).orEmpty(),
        )
    }

    private fun JSONObject.toBalanceTemplate(): BalanceQueryTemplate {
        val id = requiredBoundedString("id", 128)
        val headerArray = optJSONArray("headers") ?: JSONArray()
        if (headerArray.length() > MAX_HEADERS_PER_TEMPLATE) {
            throw ConfigurationBackupException("余额模板“$id”的请求头数量超出安全限制")
        }
        val headers = buildList {
            for (index in 0 until headerArray.length()) {
                val item = headerArray.optJSONObject(index)
                    ?: throw ConfigurationBackupException("余额模板“$id”的请求头格式无效")
                add(
                    BalanceTemplateHeader(
                        name = item.requiredBoundedString("name", 128),
                        valueTemplate = item.optionalBoundedString("valueTemplate", 4_096).orEmpty(),
                    ),
                )
            }
        }
        val method = runCatching { BalanceHttpMethod.valueOf(requiredBoundedString("method", 16)) }
            .getOrElse { throw ConfigurationBackupException("余额模板“$id”的请求方法无效") }
        val queryMode = runCatching {
            BalanceQueryMode.valueOf(optionalBoundedString("queryMode", 16) ?: BalanceQueryMode.FORM.name)
        }.getOrElse { throw ConfigurationBackupException("余额模板“$id”的查询模式无效") }
        val divisor = optDouble("scaleDivisor", 1.0)
        if (!divisor.isFinite() || divisor <= 0) {
            throw ConfigurationBackupException("余额模板“$id”的换算除数无效")
        }
        return BalanceQueryTemplate(
            id = id,
            name = requiredBoundedString("name", 120),
            description = optionalBoundedString("description", 1_024).orEmpty(),
            queryMode = queryMode,
            scriptCode = optionalBoundedString("scriptCode", 16 * 1024),
            method = method,
            endpointTemplate = requiredBoundedString("endpointTemplate", 2_048),
            headers = headers,
            requestBodyTemplate = optionalBoundedString("requestBodyTemplate", 16_384),
            availablePath = requiredBoundedString("availablePath", 256),
            usedPath = optionalBoundedString("usedPath", 256),
            totalPath = optionalBoundedString("totalPath", 256),
            deriveTotalFromAvailableAndUsed = optBoolean("deriveTotalFromAvailableAndUsed", false),
            currencyPath = optionalBoundedString("currencyPath", 256),
            planNamePath = optionalBoundedString("planNamePath", 256),
            unitLabel = requiredBoundedString("unitLabel", 64),
            scaleDivisor = divisor,
            successPath = optionalBoundedString("successPath", 256),
            successExpectedValue = optionalBoundedString("successExpectedValue", 256),
            builtIn = id == BalanceQueryTemplate.NEW_API_TEMPLATE_ID,
            createdAt = optLong("createdAt", System.currentTimeMillis()),
            updatedAt = optLong("updatedAt", System.currentTimeMillis()),
        )
    }

    private fun JSONObject.requiredBoundedString(name: String, maxLength: Int): String =
        optionalBoundedString(name, maxLength)
            ?.takeIf(String::isNotBlank)
            ?: throw ConfigurationBackupException("备份缺少有效的 $name")

    private fun JSONObject.optionalBoundedString(name: String, maxLength: Int): String? {
        if (!has(name) || isNull(name)) return null
        val value = optString(name, "")
        if (value.length > maxLength) {
            throw ConfigurationBackupException("备份中的 $name 超出安全长度")
        }
        return value
    }

    private fun ByteArray.toBase64(): String = Base64.getEncoder().encodeToString(this)

    private fun String.fromBase64(expectedLength: Int?, label: String): ByteArray = try {
        Base64.getDecoder().decode(this).also { decoded ->
            if (decoded.isEmpty() || (expectedLength != null && decoded.size != expectedLength)) {
                throw ConfigurationBackupException("备份中的${label}无效")
            }
        }
    } catch (error: IllegalArgumentException) {
        throw ConfigurationBackupException("备份中的${label}无效")
    }
}
