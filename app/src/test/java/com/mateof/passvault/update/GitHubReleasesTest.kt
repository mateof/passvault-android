package com.mateof.passvault.update

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Reading what GitHub says about a release.
 *
 * The payload is real, trimmed from `repos/mateof/passvault-android/releases/latest`. A field
 * GitHub renames — or a release built by a workflow that stops attaching the APK — turns into a
 * permanent, silent "you are up to date", so the shape is pinned here rather than trusted.
 */
class GitHubReleasesTest {

    private val payload = """
        {
          "tag_name": "v0.4.0",
          "name": "v0.4.0",
          "html_url": "https://github.com/mateof/passvault-android/releases/tag/v0.4.0",
          "body": "Sign in with a passkey.",
          "assets": [
            {
              "name": "app-release.apk",
              "size": 7362053,
              "content_type": "application/vnd.android.package-archive",
              "browser_download_url": "https://github.com/mateof/passvault-android/releases/download/v0.4.0/app-release.apk"
            },
            {
              "name": "app-release.apk.sha256",
              "size": 84,
              "content_type": "text/plain",
              "browser_download_url": "https://github.com/mateof/passvault-android/releases/download/v0.4.0/app-release.apk.sha256"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `the version is the tag without its v`() {
        assertThat(GitHubReleases.parse(payload).version).isEqualTo("0.4.0")
    }

    @Test
    fun `the APK is found among the assets`() {
        assertThat(GitHubReleases.parse(payload).apkUrl).endsWith("/v0.4.0/app-release.apk")
    }

    @Test
    fun `its size is carried, so the screen can say what the download costs`() {
        assertThat(GitHubReleases.parse(payload).apkBytes).isEqualTo(7_362_053L)
    }

    @Test
    fun `the digest published beside it is found too`() {
        assertThat(GitHubReleases.parse(payload).sha256Url).endsWith(".apk.sha256")
    }

    @Test
    fun `the release notes come across, since they are what the decision is made on`() {
        assertThat(GitHubReleases.parse(payload).notes).isEqualTo("Sign in with a passkey.")
    }

    @Test
    fun `a release with no APK reports none rather than inventing one`() {
        val withoutApk = """{ "tag_name": "v0.4.1", "body": "", "html_url": "", "assets": [] }"""

        assertThat(GitHubReleases.parse(withoutApk).apkUrl).isNull()
    }

    @Test
    fun `a release with no digest is read, since older ones published none`() {
        val withoutDigest = """
            {
              "tag_name": "v0.1.0",
              "body": "",
              "html_url": "",
              "assets": [{ "name": "app-release.apk", "size": 1, "browser_download_url": "https://example.org/a.apk" }]
            }
        """.trimIndent()

        val release = GitHubReleases.parse(withoutDigest)

        assertThat(release.apkUrl).isNotNull()
        assertThat(release.sha256Url).isNull()
    }
}
