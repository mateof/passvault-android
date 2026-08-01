package com.mateof.passvault.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

/**
 * Synchronising without being asked.
 *
 * The button stays, because somebody standing at a turnstile wanting their ticket *now* should
 * not have to trust a schedule. What this adds is that they usually will not need it: the wallet
 * synchronises when it opens and every so often in the background, so an event a friend shared
 * this morning is there before anybody thinks to look for it.
 *
 * Fifteen minutes is the floor Android allows for periodic work and is the right order of
 * magnitude anyway — tickets do not change by the second, and a wallet that woke the radio every
 * minute would be a wallet people uninstall for eating the battery. The system will stretch it
 * when the device is idle, which is correct and not worth fighting.
 *
 * Only on a network, and only when there is a server configured: the periodic request is
 * cancelled rather than left to wake up and do nothing on a device that never joined one.
 */
class SyncWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun syncEngine(): SyncEngine
    }

    override suspend fun doWork(): Result {
        val engine = EntryPointAccessors
            .fromApplication(applicationContext, Dependencies::class.java)
            .syncEngine()

        return when (engine.sync()) {
            is SyncOutcome.Done -> Result.success()
            // Nothing to do and nothing wrong. Retrying would be a wallet with no server waking
            // up forever to be told the same thing.
            SyncOutcome.NotConfigured -> Result.success()
            // A session that ended needs a person, not a retry. The sign-in screen says so when
            // they next open the app.
            SyncOutcome.SignedOut -> Result.success()
            // Same shape: a passphrase this phone does not hold is a person's to type, and a
            // background retry loop cannot type it.
            SyncOutcome.VaultLocked -> Result.success()
            // A server that is down, a tunnel asleep, an actual tunnel. Worth trying again with
            // the backoff WorkManager already applies.
            is SyncOutcome.Failed -> Result.retry()
        }
    }
}

object SyncScheduler {

    private const val PERIODIC = "passvault.sync.periodic"

    /**
     * Asks for the periodic run, replacing whatever was scheduled before.
     *
     * `UPDATE` rather than `KEEP`: the constraints here are part of the app, so a new version
     * that changes them should take effect rather than being ignored until somebody reinstalls.
     */
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** Stops it, for a wallet that has forgotten its server. */
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
    }
}
