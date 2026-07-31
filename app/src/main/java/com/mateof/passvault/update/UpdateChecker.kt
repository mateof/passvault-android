package com.mateof.passvault.update

import android.content.Context
import com.mateof.passvault.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether there is a newer release than the one running.
 *
 * The comparison is on the version *name*, since that is all a GitHub release carries — see
 * `Version`. Being equal is the ordinary answer and is reported as such rather than as a failure:
 * "you are up to date" is the message this screen shows most of the time, and it should read like
 * an answer rather than like nothing happened.
 *
 * A debug build is refused before the network is touched. It carries the `.debug` package name
 * and the debug signing key, so a release APK could never replace it — downloading seven
 * megabytes to discover that at the last step would be a waste of somebody's data and their time.
 */
@Singleton
class UpdateChecker @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val releases: GitHubReleases,
) {

    sealed interface Status {
        data class Available(val release: Release, val installed: String) : Status

        data class UpToDate(val installed: String) : Status

        /** This build can never install a published release; saying so beats trying. */
        data object NotUpdatable : Status
    }

    val installedVersion: String get() = BuildConfig.VERSION_NAME

    fun check(): Status {
        if (!updatable()) {
            return Status.NotUpdatable
        }
        val latest = releases.latest()
        val installed = BuildConfig.VERSION_NAME
        return if (Version.parse(latest.version) > Version.parse(installed) && latest.apkUrl != null) {
            Status.Available(latest, installed)
        } else {
            Status.UpToDate(installed)
        }
    }

    /**
     * Whether this build could install a release at all.
     *
     * The package name is the honest test: the debug build is `…passvault.debug`, and a release
     * APK declares `…passvault`, so the two are different applications as far as the system is
     * concerned no matter what the signature says.
     */
    private fun updatable(): Boolean = context.packageName == RELEASE_PACKAGE

    private companion object {
        const val RELEASE_PACKAGE = "com.mateof.passvault"
    }
}
