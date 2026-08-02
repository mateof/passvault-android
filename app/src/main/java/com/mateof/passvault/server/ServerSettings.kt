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
        preferences.edit()
            .remove(BASE_URL)
            .remove(SESSION_TOKEN)
            .remove(REFRESH_TOKEN)
            .remove(VAULT_PASSPHRASE)
            .apply()
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

    /**
     * The vault passphrase, sealed under the KeyStore key like the session token.
     *
     * The server forgets unwrapped keys on every restart — that is its design — so without this,
     * every server update asked the phone to retype the passphrase. Storing it here adds no new
     * exposure class: this device already holds the entire wallet readable under the same
     * KeyStore key, so a passphrase sealed the same way protects exactly as much as the wallet
     * it opens. It is dropped on sign-out with everything else.
     */
    fun vaultPassphrase(): String? {
        val sealed = preferences.getString(VAULT_PASSPHRASE, null) ?: return null
        return keys?.open(sealed)
    }

    fun setVaultPassphrase(passphrase: String?) {
        preferences.edit().apply {
            if (passphrase == null || keys == null) remove(VAULT_PASSPHRASE)
            else putString(VAULT_PASSPHRASE, keys.seal(passphrase))
        }.apply()
    }

    /**
     * The interface language, when somebody chose one other than the device's.
     *
     * Null means "follow the system", which is the default and the right one for almost
     * everybody — the setting exists for the phone set to English whose owner reads Galician.
     */
    fun uiLocale(): String? = preferences.getString(UI_LOCALE, null)?.takeIf { it.isNotBlank() }

    fun setUiLocale(tag: String?) {
        preferences.edit().apply {
            if (tag.isNullOrBlank()) remove(UI_LOCALE) else putString(UI_LOCALE, tag)
        }.apply()
    }

    fun setSessionToken(token: String?) {
        preferences.edit().apply {
            if (token == null || keys == null) remove(SESSION_TOKEN)
            else putString(SESSION_TOKEN, keys.seal(token))
        }.apply()
    }

    /**
     * The refresh token, sealed like the access token beside it.
     *
     * The long-lived half of the pair: the access token it renews is short, so this is what
     * actually keeps the phone signed in for as long as the session lasts. Sealed under the same
     * KeyStore key as everything else, and dropped on sign-out with the rest.
     */
    fun refreshToken(): String? {
        val sealed = preferences.getString(REFRESH_TOKEN, null) ?: return null
        return keys?.open(sealed)
    }

    fun setRefreshToken(token: String?) {
        preferences.edit().apply {
            if (token == null || keys == null) remove(REFRESH_TOKEN)
            else putString(REFRESH_TOKEN, keys.seal(token))
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

    /**
     * Whether the original file behind a document is kept off the server.
     *
     * The synchroniser uploads originals so that whoever an event is shared with can fetch them,
     * which is the right default — but not always what somebody wants for a file with a name or a
     * face on it. Blocking is stored rather than sharing, so the ordinary case needs no setting and
     * only a deliberate "do not share this one" leaves a mark. A block stops future uploads; it
     * cannot recall a copy a member already downloaded, the same honesty a revoke owes.
     */
    fun serverDocumentBlocked(documentId: String): Boolean =
        preferences.getBoolean(DOC_BLOCKED + documentId, false)

    fun setServerDocumentBlocked(documentId: String, blocked: Boolean) {
        preferences.edit().apply {
            if (blocked) putBoolean(DOC_BLOCKED + documentId, true)
            else remove(DOC_BLOCKED + documentId)
        }.apply()
    }

    /**
     * What this phone is called wherever a person has to recognise it: the peer list on another
     * phone, the session list on the server. The model is the default because it names the
     * hardware, but "Pixel 8" names nobody in a family that bought two of them.
     */
    fun deviceName(): String =
        preferences.getString(DEVICE_NAME, null)?.takeIf { it.isNotBlank() }
            ?: listOf(android.os.Build.MANUFACTURER, android.os.Build.MODEL)
                .filter { !it.isNullOrBlank() }
                .joinToString(" ")
                .ifBlank { "PassVault" }
                .take(60)

    fun setDeviceName(name: String?) {
        preferences.edit().apply {
            if (name.isNullOrBlank()) remove(DEVICE_NAME) else putString(DEVICE_NAME, name.trim().take(60))
        }.apply()
    }

    /** What the server should answer in: the chosen language first, the device's otherwise. */
    fun locale(): String = when (uiLocale() ?: Locale.getDefault().language) {
        "es" -> "es"
        "en" -> "en"
        else -> "gl"
    }

    private companion object {
        const val BASE_URL = "base_url"
        const val SESSION_TOKEN = "session_token"
        const val REFRESH_TOKEN = "refresh_token"
        const val VAULT_PASSPHRASE = "vault_passphrase"
        const val UI_LOCALE = "ui_locale"
        const val EVENT_PASSWORD = "event_password:"
        const val DOC_BLOCKED = "doc_unshared:"
        const val DEVICE_NAME = "device_name"
    }
}
