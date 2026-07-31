package com.mateof.passvault.update

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fetching the APK, into this application's own cache.
 *
 * The cache directory rather than Downloads, and no storage permission: the file is a means to an
 * end that the package installer reads and nothing else needs. Leaving a signed APK in a shared
 * folder would be leaving something for another app to swap.
 *
 * Progress is reported because the file is several megabytes and a button that does nothing
 * visible for forty seconds is a button people press twice.
 */
class UpdateDownloader(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // Generous: this is a multi-megabyte download on whatever connection the phone has.
        .readTimeout(5, TimeUnit.MINUTES)
        .build(),
) {

    /** Where downloads land. Cleared before each one, so a failed attempt leaves nothing behind. */
    private fun directory(): File = File(context.cacheDir, "updates").apply { mkdirs() }

    fun download(release: Release, onProgress: (fraction: Float) -> Unit): File {
        val url = release.apkUrl ?: throw IOException("this release published no APK")
        directory().listFiles()?.forEach { it.delete() }
        val target = File(directory(), "passvault-${release.version}.apk")

        request(url).use { response ->
            if (!response.isSuccessful) {
                throw IOException("the download answered ${response.code}")
            }
            val body = response.body ?: throw IOException("the download was empty")
            val total = if (body.contentLength() > 0) body.contentLength() else release.apkBytes

            target.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        written += read
                        if (total > 0) onProgress((written.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        return target
    }

    /** The digest published beside the APK, when the release published one. */
    fun expectedDigest(release: Release): String? {
        val url = release.sha256Url ?: return null
        return runCatching {
            request(url).use { response ->
                if (response.isSuccessful) response.body?.string()?.trim() else null
            }
        }.getOrNull()
    }

    /** Nothing is kept once an install has been handed over: it is several megabytes of cache. */
    fun forget() {
        directory().listFiles()?.forEach { it.delete() }
    }

    private fun request(url: String) = client
        .newCall(Request.Builder().url(url).header("User-Agent", "PassVault-Android").build())
        .execute()
}
