package com.mateof.passvault.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

/**
 * Deciding whether a downloaded file is allowed near the package installer.
 *
 * This is the part of self-updating that matters. Everything else is plumbing; this is what
 * stands between a user tapping "update" and an arbitrary APK replacing their wallet.
 *
 * Three checks, in the order they can be made cheaply, and each one covers something the others
 * do not:
 *
 *   1. **The digest**, when the release published one. Catches a truncated download and a
 *      corrupted mirror. It does not catch a malicious release, since whoever could replace the
 *      APK could replace the digest beside it.
 *   2. **The package name**, which must be exactly this application's. An APK for another package
 *      would not update anything — it would silently install a second, unrelated app.
 *   3. **The signing certificate**, which must be the one the running app was signed with. This
 *      is the check that actually protects: Android will refuse the install anyway, but refusing
 *      here means the user is told what is wrong instead of watching a system dialog fail with
 *      nothing to read.
 *
 * The third check also explains why a debug build can never update itself from a release: it is
 * signed with the debug key and carries a different package name, so it fails two of the three
 * on purpose rather than by accident.
 */
class ApkVerifier(private val context: Context) {

    sealed interface Verdict {
        data object Ok : Verdict

        /** The bytes are not what the release said they would be. */
        data object DigestMismatch : Verdict

        /** An APK for some other application entirely. */
        data class WrongPackage(val found: String?) : Verdict

        /** Signed with a key that is not the one this installation trusts. */
        data object WrongSigner : Verdict

        /** Not readable as an APK at all. */
        data object Unreadable : Verdict
    }

    fun verify(apk: File, expectedSha256: String?): Verdict {
        if (expectedSha256 != null && !digestMatches(apk, expectedSha256)) {
            return Verdict.DigestMismatch
        }

        val downloaded = archiveInfo(apk) ?: return Verdict.Unreadable
        if (downloaded.packageName != context.packageName) {
            return Verdict.WrongPackage(downloaded.packageName)
        }

        val installed = installedInfo() ?: return Verdict.Unreadable
        return if (signersOf(downloaded) == signersOf(installed) && signersOf(installed).isNotEmpty()) {
            Verdict.Ok
        } else {
            Verdict.WrongSigner
        }
    }

    /** The version the file declares, so a screen can say what it is about to install. */
    fun versionOf(apk: File): String? = archiveInfo(apk)?.versionName

    private fun digestMatches(apk: File, expected: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        apk.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        // A `sha256sum` file is "<digest>  <filename>", so only the first field is compared, and
        // case is ignored because both spellings are in the wild.
        return hex.equals(expected.trim().substringBefore(' ').trim(), ignoreCase = true)
    }

    private fun archiveInfo(apk: File): PackageInfo? =
        context.packageManager.getPackageArchiveInfo(apk.absolutePath, signingFlags())

    private fun installedInfo(): PackageInfo? = runCatching {
        context.packageManager.getPackageInfo(context.packageName, signingFlags())
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun signingFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }

    /**
     * The certificates a package is signed with, as digests.
     *
     * Digests rather than the raw certificates so the comparison is a set of strings, and the
     * whole set rather than the first entry: a package signed by two certificates and one signed
     * by one of them are not the same package, and comparing only the first would say they were.
     */
    @Suppress("DEPRECATION")
    private fun signersOf(info: PackageInfo): Set<String> {
        val certificates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = info.signingInfo ?: return emptySet()
            if (signing.hasMultipleSigners()) {
                signing.apkContentsSigners
            } else {
                signing.signingCertificateHistory
            }
        } else {
            info.signatures
        }
        return certificates.orEmpty().filterNotNull().map { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }.toSet()
    }
}
