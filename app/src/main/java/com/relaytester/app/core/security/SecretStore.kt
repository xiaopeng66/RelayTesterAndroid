package com.relaytester.app.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SecretStore {
    fun put(value: String): String
    fun get(secretId: String): String?
    fun delete(secretId: String)
}

/**
 * Keeps ciphertext in app-private SharedPreferences while the AES key stays in
 * Android Keystore. Supplier records only retain the opaque secret identifier.
 */
class KeystoreSecretStore(private val context: Context) : SecretStore {
    /**
     * Keep the SharedPreferences file out of the ViewModel factory's main
     * thread work. All secret operations are dispatched to IO by the caller,
     * so the first open happens together with the background restore.
     */
    private val preferences by lazy {
        context.getSharedPreferences(
            "relay_tester_secrets",
            Context.MODE_PRIVATE,
        )
    }

    @Volatile
    private var cachedKey: SecretKey? = null
    private val keyLock = Any()

    override fun put(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val payload = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val id = "secret_" + UUID.randomUUID()
        preferences.edit {
            putString(id, Base64.encodeToString(payload, Base64.NO_WRAP))
        }
        return id
    }

    override fun get(secretId: String): String? {
        val encoded = preferences.getString(secretId, null) ?: return null
        return runCatching {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            require(payload.size > IV_LENGTH) { "密钥数据不完整" }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(TAG_LENGTH_BITS, payload.copyOfRange(0, IV_LENGTH)),
            )
            cipher.doFinal(payload.copyOfRange(IV_LENGTH, payload.size))
                .toString(Charsets.UTF_8)
        }.getOrNull()
    }

    override fun delete(secretId: String) {
        preferences.edit {
            remove(secretId)
        }
    }

    private fun getOrCreateKey(): SecretKey = cachedKey ?: synchronized(keyLock) {
        cachedKey ?: run {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val key = (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)
                ?.secretKey
                ?: KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    ANDROID_KEYSTORE,
                ).apply {
                    init(
                        KeyGenParameterSpec.Builder(
                            KEY_ALIAS,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setKeySize(256)
                            .build(),
                    )
                }.generateKey()
            cachedKey = key
            key
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "relay_tester_api_keys_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_LENGTH_BITS = 128
    }
}
