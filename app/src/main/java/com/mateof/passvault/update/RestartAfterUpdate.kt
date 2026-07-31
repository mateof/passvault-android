package com.mateof.passvault.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.mateof.passvault.R

/**
 * Opening the app again after it has updated itself.
 *
 * Updating from inside the app ends with the process being replaced, so the last thing the user
 * sees is their wallet disappearing — they asked for an update and were left on the home screen.
 * Android delivers `MY_PACKAGE_REPLACED` to the new version, which is the one moment the app can
 * do something about that.
 *
 * Two attempts, because one of them is not allowed to work everywhere. Starting the activity
 * directly is what actually reopens the wallet, and Android 10 and later refuse background
 * activity starts in most circumstances — so a notification goes out as well, and tapping it
 * opens the app. On a device that permits the direct start the notification is redundant and
 * disappears with the activity; on one that does not, it is the whole feature.
 *
 * Nothing here is silent-install machinery. The system still shows its own confirmation before
 * replacing the app, which is where that decision belongs.
 */
class RestartAfterUpdate : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            ?: return

        notify(context, launch)

        // Attempted second, so a refusal here cannot stop the notification from being posted.
        // Caught rather than checked: whether a background start is permitted depends on the
        // version, the manufacturer and what the user was doing, and the only reliable way to
        // find out is to try.
        runCatching { context.startActivity(launch) }
    }

    private fun notify(context: Context, launch: Intent) {
        val manager = NotificationManagerCompat.from(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    context.getString(R.string.update_channel),
                    // Low: the app has already updated successfully. This is an invitation to
                    // come back, not something to interrupt anybody for.
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }

        val tap = PendingIntent.getActivity(
            context,
            0,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_IMMUTABLE
                } else {
                    0
                },
        )

        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(R.string.update_restart_title))
            .setContentText(context.getString(R.string.update_restart_body))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()

        // Without the runtime permission on Android 13 and later this does nothing at all, which
        // is the right outcome: a user who declined notifications is not shown one, and the
        // direct start above may well have worked anyway.
        runCatching { manager.notify(NOTIFICATION, notification) }
    }

    private companion object {
        const val CHANNEL = "passvault.update"
        const val NOTIFICATION = 4201
    }
}
