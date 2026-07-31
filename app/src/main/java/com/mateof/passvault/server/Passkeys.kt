package com.mateof.passvault.server

import android.content.Context
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialCancellationException

/**
 * Signing in with a passkey.
 *
 * The whole ceremony happens in the system's own sheet: the app never sees the private key, never
 * sees a password, and cannot be phished into using a credential meant for another site — the
 * credential is bound to the origin, and the binding is checked by the platform rather than by
 * anything written here.
 *
 * That binding is why `/.well-known/assetlinks.json` has to be reachable on the server's domain.
 * Android will only let this package use credentials for `passvault.mateof.com.es` if that file
 * names the package and the exact certificate the installed build was signed with. A debug build
 * is signed with a different key, so passkeys work in a release and not in a development build —
 * which looks like a broken fingerprint sensor unless you know to expect it.
 *
 * The JSON is passed straight through in both directions. WebAuthn's request and response shapes
 * are defined by the specification and understood by both the platform and the server; re-modelling
 * them in Kotlin would add a third opinion about a format that already has two agreeing ones.
 */
class Passkeys(private val context: Context) {

    private val manager = CredentialManager.create(context)

    /**
     * Asks the platform to sign a challenge.
     *
     * Returns null when the user dismisses the sheet: choosing not to use a passkey is not a
     * failure, and reporting it as one would put an error on screen for somebody who simply
     * changed their mind.
     */
    suspend fun authenticate(optionsJson: String): String? = try {
        val credential = manager.getCredential(
            context,
            GetCredentialRequest(listOf(GetPublicKeyCredentialOption(optionsJson))),
        ).credential as PublicKeyCredential
        credential.authenticationResponseJson
    } catch (_: GetCredentialCancellationException) {
        null
    }

    /** Creates a new passkey for the signed-in account. Null if the user backed out. */
    suspend fun register(optionsJson: String): String? = try {
        val response = manager.createCredential(
            context,
            CreatePublicKeyCredentialRequest(optionsJson),
        )
        response.data.getString(
            "androidx.credentials.BUNDLE_KEY_REGISTRATION_RESPONSE_JSON",
        )
    } catch (_: CreateCredentialCancellationException) {
        null
    }
}
