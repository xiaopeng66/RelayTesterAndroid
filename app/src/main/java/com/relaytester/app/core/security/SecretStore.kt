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

/**
 * What reading a stored secret found.
 *
 * Three outcomes rather than a nullable string, because two of them are not the same fact:
 * a slot that was never written is a normal state (a supplier with no key yet), while a slot
 * whose ciphertext no longer decrypts is damage the user has to be told about. Collapsing
 * both into `null` made a broken Keystore read look like an empty field, and the user was
 * then told to type a key he had already saved.
 */
sealed interface SecretRead {
    /** The plaintext, decrypted. */
    data class Found(val value: String) : SecretRead

    /** No record under this id, or no id at all: nothing was ever stored. */
    object Absent : SecretRead

    /** A record exists but cannot be decrypted: the reason is worth showing to the user. */
    data class Unreadable(val reason: String) : SecretRead
}

interface SecretStore {
    fun put(value: String): String

    /**
     * The secret under [secretId], or null when it is [SecretRead.Absent] *or*
     * [SecretRead.Unreadable].
     *
     * Kept for the callers that cannot act on the difference — a request being built, a
     * field being filled. Use [read] wherever the two cases need different words.
     */
    fun get(secretId: String): String?

    fun delete(secretId: String)

    /**
     * [get] with the three states kept apart.
     *
     * The default reports only what an implementation that knows [get] can honestly say:
     * a value, or nothing. An implementation backed by real crypto overrides it (see
     * [KeystoreSecretStore]).
     */
    fun read(secretId: String): SecretRead =
        get(secretId)?.let(SecretRead::Found) ?: SecretRead.Absent
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
        // The id exists before the cipher runs because it is the cipher's associated data:
        // see the note on `read` for what that buys and what it does not.
        val id = "secret_" + UUID.randomUUID()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(id.toByteArray(Charsets.UTF_8))
        val payload = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        preferences.edit {
            putString(id, Base64.encodeToString(payload, Base64.NO_WRAP))
        }
        return id
    }

    override fun get(secretId: String): String? =
        (read(secretId) as? SecretRead.Found)?.value

    /**
     * Decrypts [secretId], telling "never written" apart from "cannot be read".
     *
     * The ciphertext is authenticated against the slot id it was stored under, so a record
     * copied from another slot does not decrypt *as that other slot's value*. Only records
     * written by this version carry the binding: the AAD is not in the stored bytes, so a
     * decrypt without it is tried as a fallback for records written before it existed, and
     * those stay readable rather than becoming "unreadable" damage on upgrade. Nothing is
     * rewritten on read — a read that silently wrote would be able to resurrect a record
     * that a concurrent delete had just removed.
     */
    override fun read(secretId: String): SecretRead {
        val encoded = preferences.getString(secretId, null) ?: return SecretRead.Absent
        val payload = runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull()
            ?: return SecretRead.Unreadable(UNREADABLE_REASON)
        if (payload.size <= IV_LENGTH) return SecretRead.Unreadable(UNREADABLE_REASON)
        val value = decrypt(payload, secretId, boundToSlot = true)
            ?: decrypt(payload, secretId, boundToSlot = false)
            ?: return SecretRead.Unreadable(UNREADABLE_REASON)
        return SecretRead.Found(value)
    }

    private fun decrypt(payload: ByteArray, secretId: String, boundToSlot: Boolean): String? =
        runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(TAG_LENGTH_BITS, payload.copyOfRange(0, IV_LENGTH)),
            )
            if (boundToSlot) cipher.updateAAD(secretId.toByteArray(Charsets.UTF_8))
            cipher.doFinal(payload.copyOfRange(IV_LENGTH, payload.size))
                .toString(Charsets.UTF_8)
        }.getOrNull()

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

        /**
         * One sentence for every cause: a mangled Base64 record, a truncated payload and a
         * GCM tag that no longer verifies are all the same thing to the user — this machine
         * cannot produce the key that was saved — and the one cause worth naming is the
         * common one (Keystore reset or a restore onto another device), because that is what
         * tells him the stored key is gone rather than mistyped.
         */
        const val UNREADABLE_REASON = "本机保存的凭据无法解密（系统密钥库可能已被重置）"
    }
}
