package com.mateof.passvault.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File

/**
 * Handing an APK to the system to install.
 *
 * Through `PackageInstaller` rather than an `ACTION_VIEW` intent at a `content://` URI. The
 * intent form is the older recipe and still works, but it hands the file to whichever application
 * claims the type and then tells you nothing: no result, no reason, no way to distinguish "the
 * user declined" from "the signature did not match". A session reports back.
 *
 * What this cannot do, and does not pretend to: install silently. Every user-initiated update on
 * a device without device-owner privileges shows the system's own confirmation, and that is the
 * correct place for the decision to be made — an application that could replace itself without
 * being seen would be a worse thing than the inconvenience it saves.
 */
class UpdateInstaller(private val context: Context) {

    /**
     * Whether the user has allowed this app to install packages at all.
     *
     * A separate setting from the manifest permission since Android 8, granted per application in
     * Settings and revocable. Asking first means the failure is a screen that explains itself
     * rather than an install that ends with nothing happening.
     */
    fun mayInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    /** Where to send somebody to grant that, as an intent the caller starts. */
    fun permissionSettings(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /**
     * Writes the APK into a session and commits it.
     *
     * The system takes it from there: it verifies the signature against the installed app, shows
     * its confirmation, and reports the outcome to `InstallResultReceiver`.
     */
    fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Ask for the dialog that requires one confirmation rather than two on devices that
            // offer it. Purely how it looks; the confirmation still happens.
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_UNSPECIFIED)
        }

        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite(NAME, 0, apk.length()).use { out ->
                apk.inputStream().use { input -> input.copyTo(out) }
                session.fsync(out)
            }
            session.commit(statusIntent(sessionId).intentSender)
        }
    }

    private fun statusIntent(sessionId: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        sessionId,
        Intent(InstallResultReceiver.ACTION).setPackage(context.packageName),
        // Mutable, because the system fills in the status extras on the way back. Explicit by
        // package, so nothing else can receive it.
        PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0,
    )

    private companion object {
        const val NAME = "passvault-update"
    }
}

/**
 * What the system says about a commit.
 *
 * Its one real job is `STATUS_PENDING_USER_ACTION`: the system is asking for the confirmation
 * dialog to be shown, and it will not appear unless somebody starts the intent it hands over.
 * Without this the update silently stops right at the end, which is the single most confusing
 * way for it to fail.
 */
class InstallResultReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION = "com.mateof.passvault.INSTALL_RESULT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                }
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                confirm?.let(context::startActivity)
            }
            PackageInstaller.STATUS_SUCCESS -> UpdateOutcome.report(InstallResult.Installed)
            else -> UpdateOutcome.report(
                InstallResult.Failed(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)),
            )
        }
    }
}

/**
 * The result of the last install attempt, for a screen to read.
 *
 * A process-wide value rather than a callback, because the receiver is constructed by the system
 * and has no reference to whatever started the install — and on success the process is usually
 * being replaced anyway, so anything more elaborate would be machinery for a message nobody sees.
 */
sealed interface InstallResult {
    data object Installed : InstallResult

    data class Failed(val message: String?) : InstallResult
}

object UpdateOutcome {
    @Volatile
    var last: InstallResult? = null
        private set

    fun report(result: InstallResult) {
        last = result
    }

    fun clear() {
        last = null
    }
}
