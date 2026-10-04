package com.relaytester.app.core.storage

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * What the two update channels remember between launches.
 *
 * Both switches default to on. For the detection package that is what a fresh install
 * already does — the panel checks on entry — so "on" keeps the current behaviour rather
 * than introducing it; for the app itself the check is a few hundred bytes against a
 * public release asset, and a user who never opens the update page is exactly the person
 * who needs to hear about a new version.
 *
 * The two check timestamps are the only things here that are not switches: each channel
 * throttles itself to one check per [APP_CHECK_INTERVAL_MILLIS], and a throttle has to
 * survive a restart or every launch would be "the first check in a while".
 */
data class UpdatePreferencesState(
    val autoCheckBank: Boolean = true,
    val autoCheckApp: Boolean = true,
    /** Epoch millis of the last finished app check; 0 means "never checked". */
    val lastAppCheckAt: Long = 0L,
    /** Epoch millis of the last finished detection-package check; 0 means "never". */
    val lastBankCheckAt: Long = 0L,
) {
    companion object {
        /** How long a check stays fresh, so opening the app twice in an hour is one request. */
        const val APP_CHECK_INTERVAL_MILLIS = 6L * 60 * 60 * 1000
    }
}

private val Context.updatePreferencesDataStore by preferencesDataStore(name = "relay_tester_updates")

private val KEY_AUTO_CHECK_BANK = booleanPreferencesKey("auto_check_bank")
private val KEY_AUTO_CHECK_APP = booleanPreferencesKey("auto_check_app")
private val KEY_LAST_APP_CHECK_AT = longPreferencesKey("last_app_check_at")
private val KEY_LAST_BANK_CHECK_AT = longPreferencesKey("last_bank_check_at")

/**
 * Persists the update switches in a file of their own.
 *
 * Not a field in the supplier profile file: that one is written wholesale on every save,
 * so a switch flipped while a save is in flight would be overwritten by the older
 * snapshot. These are three independent keys, written one at a time.
 *
 * Like [SupplierStore], the null context is for tests: they override the methods, and
 * every real call goes through a context that came from the application.
 */
open class UpdatePreferences(private val context: Context?) {
    open suspend fun read(): UpdatePreferencesState {
        // The defaults live on the data class, so "on" is written down once.
        val defaults = UpdatePreferencesState()
        return requireNotNull(context).updatePreferencesDataStore.data
            .map { preferences ->
                UpdatePreferencesState(
                    autoCheckBank = preferences[KEY_AUTO_CHECK_BANK] ?: defaults.autoCheckBank,
                    autoCheckApp = preferences[KEY_AUTO_CHECK_APP] ?: defaults.autoCheckApp,
                    lastAppCheckAt = preferences[KEY_LAST_APP_CHECK_AT] ?: defaults.lastAppCheckAt,
                    lastBankCheckAt = preferences[KEY_LAST_BANK_CHECK_AT] ?: defaults.lastBankCheckAt,
                )
            }
            .first()
    }

    open suspend fun setAutoCheckBank(enabled: Boolean) {
        requireNotNull(context).updatePreferencesDataStore.edit { preferences ->
            preferences[KEY_AUTO_CHECK_BANK] = enabled
        }
    }

    open suspend fun setAutoCheckApp(enabled: Boolean) {
        requireNotNull(context).updatePreferencesDataStore.edit { preferences ->
            preferences[KEY_AUTO_CHECK_APP] = enabled
        }
    }

    open suspend fun setLastAppCheckAt(millis: Long) {
        requireNotNull(context).updatePreferencesDataStore.edit { preferences ->
            preferences[KEY_LAST_APP_CHECK_AT] = millis
        }
    }

    open suspend fun setLastBankCheckAt(millis: Long) {
        requireNotNull(context).updatePreferencesDataStore.edit { preferences ->
            preferences[KEY_LAST_BANK_CHECK_AT] = millis
        }
    }
}
