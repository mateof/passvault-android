package com.mateof.passvault.update

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * What the newest published release says about itself.
 *
 * `version` is the tag with its `v` removed, because that is the only version a release carries —
 * see `Version`. `apkUrl` is absent when a release published no APK at all, which is a state the
 * screen has to render rather than crash on: a release can exist for a documentation change.
 */
data class Release(
    val version: String,
    val notes: String,
    val apkUrl: String?,
    val apkBytes: Long,
    /** Where the digest published beside the APK lives, when a release published one. */
    val sha256Url: String?,
    val pageUrl: String,
)

/**
 * Asking GitHub what the newest release is.
 *
 * The public releases API, unauthenticated. Sixty requests an hour per address is the anonymous
 * limit and this asks once when a screen is opened, so a token would be a credential shipped in
 * an APK to buy nothing.
 *
 * Nothing here decides whether to install. This reports what was published; `UpdateChecker`
 * compares it against what is running, and `ApkVerifier` decides whether the bytes are allowed
 * anywhere near the package manager.
 */
class GitHubReleases(
    private val repository: String = DEFAULT_REPOSITORY,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {

    fun latest(): Release {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$repository/releases/latest")
            .header("Accept", "application/vnd.github+json")
            // Asked for by name, because an unnamed client is the one rate limiting treats worst.
            .header("User-Agent", "PassVault-Android")
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("GitHub answered ${response.code}")
            }
            return parse(body)
        }
    }

    companion object {
        const val DEFAULT_REPOSITORY = "mateof/passvault-android"

        /**
         * Reads a release payload.
         *
         * Separated from the request so the shape can be tested without a network, which is the
         * only part of this worth testing: a field GitHub renames is a silent "you are up to
         * date" for ever.
         */
        fun parse(json: String): Release {
            val root = Json.parseToJsonElement(json).jsonObject
            val assets = root["assets"]?.jsonArray.orEmpty().map { it.jsonObject }
            val apk = assets.firstOrNull { it.text("name")?.endsWith(".apk") == true }
            val digestAsset = assets.firstOrNull { it.text("name")?.endsWith(".sha256") == true }

            return Release(
                version = root.text("tag_name").orEmpty().removePrefix("v"),
                notes = root.text("body").orEmpty().trim(),
                apkUrl = apk?.text("browser_download_url"),
                apkBytes = apk?.get("size")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                // The address of the digest, not the digest: reading it means another request,
                // and making requests is the downloader's job rather than this one's.
                sha256Url = digestAsset?.text("browser_download_url"),
                pageUrl = root.text("html_url").orEmpty(),
            )
        }

        private fun kotlinx.serialization.json.JsonObject.text(key: String): String? =
            this[key]?.jsonPrimitive?.let { if (it.isString) it.content else null }
    }
}
