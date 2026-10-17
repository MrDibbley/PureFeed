package app.faceblockmobile.debug

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * "Dump DOM": asks filter.js for a sanitised copy of the page HTML, reassembles the
 * chunks, saves it under Android/data/app.faceblockmobile/files/dumps/ and opens the
 * share sheet. Also reachable with:
 *   adb pull /sdcard/Android/data/app.faceblockmobile/files/dumps/
 */
class DomDumper(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private var pendingId: String? = null
    private var chunks: Array<String?> = emptyArray()

    /** Starts a dump; returns the request id to pass to filter.js. */
    @Synchronized
    fun begin(): String = UUID.randomUUID().toString().also {
        pendingId = it
        chunks = emptyArray()
    }

    /** Called from the bridge (background thread). Ignores anything we didn't ask for. */
    @Synchronized
    fun onChunk(requestId: String, index: Int, total: Int, chunk: String, onSaved: (File) -> Unit) {
        if (requestId != pendingId || total !in 1..500 || index !in 0 until total) return
        if (chunks.size != total) chunks = arrayOfNulls(total)
        chunks[index] = chunk
        if (chunks.any { it == null }) return

        pendingId = null
        val dir = File(context.getExternalFilesDir(null), "dumps").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "fb-$stamp.dom.html")
        file.writeText(chunks.joinToString(""))
        chunks = emptyArray()
        main.post { onSaved(file) }
    }

    fun shareIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/html")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, "Share DOM dump")
    }
}
