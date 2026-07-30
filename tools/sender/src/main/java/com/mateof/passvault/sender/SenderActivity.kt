package com.mateof.passvault.sender

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hands PassVault a file the way a messaging app does.
 *
 * PassVault's two import routes both begin with a `content://` URI belonging to somebody else,
 * carrying a temporary read grant. That is the part `adb` cannot produce: `am start -d file://…`
 * either fails on scoped storage or arrives without a grant, so three attempts at verifying the
 * import by shell alone got no further than the activity opening with nothing to read. An
 * application has to be the one doing the sending, so this is the smallest application that can.
 *
 * Everything is driven from the shell, and the file comes off the outbox on external storage so a
 * new fixture is a `push` rather than a rebuild:
 *
 * ```
 * adb push entradas.pdf /sdcard/Android/data/com.mateof.passvault.sender/files/outbox/
 * adb shell am start -n com.mateof.passvault.sender/.SenderActivity \
 *     -e file entradas.pdf -e action SEND -e mime application/pdf
 * ```
 *
 * `mime` is worth setting by hand: the interesting case is a `.tkpak` announced as
 * `application/octet-stream`, which is what most messaging apps do with an extension they do not
 * recognise, and which the receiving manifest has to match for the file to arrive at all.
 */
class SenderActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        send(intent)
    }

    /**
     * The same work again when the instance is reused.
     *
     * `am start` compares intents with `filterEquals`, which ignores extras: two sends that differ
     * only in `-e file` look identical to the system, so the second is handed to the running
     * instance instead of starting a new one. Without this the shell reports success and nothing
     * is sent — a harness that lies about having done its job is worse than no harness.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        send(intent)
    }

    private fun send(intent: Intent) {
        val outbox = File(getExternalFilesDir(null), "outbox").apply { mkdirs() }
        val requested = intent.getStringExtra("file")
        if (requested == null) {
            fail("no -e file given. Files in the outbox: ${outbox.list()?.joinToString() ?: "none"}")
            return
        }

        // An absolute path is accepted so a fixture elsewhere can be pointed at directly, but it
        // still has to sit under the outbox: FileProvider only publishes what its paths declare,
        // and a URI it will not serve fails later and less clearly than one refused here.
        val file = if (requested.startsWith("/")) File(requested) else File(outbox, requested)
        if (!file.isFile) {
            fail("${file.path} is not a file. In the outbox: ${outbox.list()?.joinToString() ?: "none"}")
            return
        }

        val mime = intent.getStringExtra("mime") ?: guessMime(file.name)
        val target = intent.getStringExtra("target") ?: "com.mateof.passvault.debug"
        val action = when (intent.getStringExtra("action")?.uppercase()) {
            "VIEW" -> Intent.ACTION_VIEW
            null, "SEND" -> Intent.ACTION_SEND
            else -> {
                fail("action must be SEND or VIEW")
                return
            }
        }

        val uri: Uri = try {
            FileProvider.getUriForFile(this, "$packageName.files", file)
        } catch (refused: IllegalArgumentException) {
            fail("FileProvider will not serve ${file.path}: ${refused.message}")
            return
        }

        val outgoing = Intent(action).apply {
            setPackage(target)
            // The grant is the whole point. Without it the receiver gets a URI it is not allowed
            // to open, which is exactly the failure that looks like a broken reader.
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (action == Intent.ACTION_VIEW) {
                setDataAndType(uri, mime)
                addCategory(Intent.CATEGORY_DEFAULT)
            } else {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
            }
        }

        Log.i(TAG, "sending $action $uri as $mime to $target (${file.length()} bytes)")
        try {
            startActivity(outgoing)
        } catch (missing: android.content.ActivityNotFoundException) {
            // Says which of the two things is wrong rather than leaving it to be guessed: either
            // the app is not installed, or its manifest does not claim this media type.
            fail("$target has no activity for $action of $mime — not installed, or no filter matches")
            return
        }
        finish()
    }

    private fun fail(reason: String) {
        Log.e(TAG, reason)
        setResult(RESULT_CANCELED)
        finish()
    }

    /**
     * What a sending app would claim the file is.
     *
     * `.tkpak` is deliberately not mapped to its registered type here: an app that has never heard
     * of the extension calls it a byte stream, and that is the case worth exercising by default.
     */
    private fun guessMime(name: String): String = when {
        name.endsWith(".pdf", ignoreCase = true) -> "application/pdf"
        name.endsWith(".png", ignoreCase = true) -> "image/png"
        name.endsWith(".jpg", ignoreCase = true) || name.endsWith(".jpeg", ignoreCase = true) ->
            "image/jpeg"
        name.endsWith(".pkpass", ignoreCase = true) -> "application/vnd.apple.pkpass"
        else -> "application/octet-stream"
    }

    private companion object {
        const val TAG = "PassVaultSender"
    }
}
