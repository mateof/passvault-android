package com.mateof.passvault.server

import android.content.Context
import java.util.Locale

/**
 * Where the server is, if there is one.
 *
 * There usually is not. The application's claim is that it works with no server at all, so this is
 * empty until somebody types an address, and every screen has to behave when it is.
 *
 * Plain preferences rather than the encrypted store: an address is not a secret, and putting it
 * behind the vault key would mean the app could not tell you which server it is configured for
 * until you had unlocked it.
 */
class ServerSettings(
    context: Context,
    /** Seals the session token, so a restart does not mean signing in again. */
    private val keys: com.mateof.passvault.data.DeviceKeys? = null,
) {

    private val preferences = context.getSharedPreferences("passvault.server", Context.MODE_PRIVATE)

    fun baseUrl(): String = preferences.getString(BASE_URL, "").orEmpty()

    fun isConfigured(): Boolean = baseUrl().isNotBlank()

    /**
     * Stores the address, tidied.
     *
     * A bare hostname becomes `https://`, never `http://`. Somebody typing
     * `passvault.example.com` means the site, and defaulting to plaintext would send a bearer token
     * and a vault passphrase over the network in the clear because of a missing prefix.
     */
    fun setBaseUrl(value: String) {
        val trimmed = value.trim().trimEnd('/')
        val normalised = when {
            trimmed.isEmpty() -> ""
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            else -> "https://$trimmed"
        }
        preferences.edit().putString(BASE_URL, normalised).apply()
    }

    fun clear() {
        preferences.edit().remove(BASE_URL).remove(SESSION_TOKEN).apply()
    }

    /**
     * The session, kept until somebody says otherwise.
     *
     * It used to live only in memory, on the reasoning that a bearer token on disk is a bearer
     * token somebody could steal. True, and it made the app sign in again on every start and
     * every reinstall — for a wallet somebody opens at a turnstile, in a queue, with one hand.
     *
     * Sealed with the KeyStore key that already wraps the vault key, so what sits in preferences
     * is useless on another device and useless here to anything that cannot ask the KeyStore. A
     * reinstall generates a new KeyStore key, so the old token unseals to nothing and the app
     * asks again — which is the correct outcome and the one thing this cannot avoid.
     */
    fun sessionToken(): String? {
        val sealed = preferences.getString(SESSION_TOKEN, null) ?: return null
        return keys?.open(sealed)
    }

    fun setSessionToken(token: String?) {
        preferences.edit().apply {
            if (token == null || keys == null) remove(SESSION_TOKEN)
            else putString(SESSION_TOKEN, keys.seal(token))
        }.apply()
    }

    /**
     * The password to publish an event under, chosen before it has ever been uploaded.
     *
     * A password decides who can decrypt an event, and it can only be set as the event is created
     * on the server — which for a wallet built offline happens during a synchronisation, long
     * after the person who wanted the password was looking at the screen. So the choice is kept
     * here until the synchronisation that uses it.
     *
     * Alongside the address rather than behind the vault key, for the same reason the address is:
     * the synchronisation needs it before anything has been unlocked. It is a password for an
     * event the phone already holds in the clear, so keeping it here reveals nothing the device
     * does not already have — and it is dropped as soon as it has been used.
     */
    fun eventPassword(eventId: String): String? =
        preferences.getString(EVENT_PASSWORD + eventId, null)?.takeIf { it.isNotBlank() }

    fun setEventPassword(eventId: String, password: String?) {
        preferences.edit().apply {
            if (password.isNullOrBlank()) remove(EVENT_PASSWORD + eventId)
            else putString(EVENT_PASSWORD + eventId, password)
        }.apply()
    }

    /** What the server should answer in. Its catalogue has the same three languages. */
    fun locale(): String = when (Locale.getDefault().language) {
        "es" -> "es"
        "en" -> "en"
        else -> "gl"
    }

    private companion object {
        const val BASE_URL = "base_url"
        const val SESSION_TOKEN = "session_token"
        const val EVENT_PASSWORD = "event_password:"
    }
}
