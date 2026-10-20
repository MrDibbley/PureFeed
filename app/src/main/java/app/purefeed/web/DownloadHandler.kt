package app.purefeed.web

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebView
import android.widget.Toast
import java.io.File

/**
 * Saves files the page asks to download, e.g. "Save" on a Facebook photo. Without a
 * DownloadListener the WebView silently drops every download.
 *
 *  - http(s) links (Facebook's photo CDN) go to Android's DownloadManager, with this
 *    WebView's cookies and user agent, so they get the normal download notification.
 *  - blob: and data: links only exist inside the page, so their bytes are read there
 *    (see [fetchBlob]: filter.js keeps the Blob, since Facebook's CSP blocks fetch() of
 *    blob: URLs) and written directly.
 *
 * Images go to Pictures/PureFeed (shown in the gallery), anything else to Download/PureFeed.
 * On Android 8–9 the public folders need a storage permission this app doesn't ask for,
 * so files go to the app's own folder there (Android/data/app.purefeed/files/…).
 */
class DownloadHandler(private val context: Context, private val webView: WebView) : DownloadListener {

    private val main = Handler(Looper.getMainLooper())
    /**
     * blob: downloads we asked the page for (URL → file name). Bytes from the page are
     * only accepted for these. Facebook can start several at once.
     */
    private val pendingBlobs = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Facebook fires several identical blob downloads per tap on Save (seen: 4 within
     * 66 ms). Content hash → time saved, so duplicates within 30 s are skipped.
     */
    private val recentSaves = mutableMapOf<String, Long>()

    override fun onDownloadStart(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?, contentLength: Long) {
        Log.d(TAG, "Download requested: ${url.substringBefore('?').take(80)} type=$mimeType")
        // blob:/data: URLs have no real file name (a blob's is a random id), so use the time.
        val name = if (url.startsWith("http")) {
            "PureFeed_" + URLUtil.guessFileName(url, contentDisposition, mimeType)
        } else {
            "PureFeed_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date()) +
                "." + (android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "bin")
        }
        when {
            url.startsWith("http://") || url.startsWith("https://") -> enqueue(url, userAgent, mimeType, name)
            url.startsWith("data:") -> saveDataUrl(url, name)
            url.startsWith("blob:") -> fetchBlob(url, name)
            else -> toast("Can't save this kind of link")
        }
    }

    private fun enqueue(url: String, userAgent: String?, mimeType: String?, name: String) {
        val image = isImage(mimeType, name)
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
            userAgent?.let { addRequestHeader("User-Agent", it) }
            mimeType?.takeIf { it.isNotBlank() && it != "application/octet-stream" }?.let { setMimeType(it) }
            setTitle(name)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            val dir = if (image) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_DOWNLOADS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setDestinationInExternalPublicDir(dir, "PureFeed/$name")
            } else {
                setDestinationInExternalFilesDir(context, dir, "PureFeed/$name")
            }
        }
        try {
            (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            toast(if (image) "Saving photo to Pictures/PureFeed" else "Downloading $name")
        } catch (e: Exception) {
            Log.w(TAG, "DownloadManager refused: ${e.message}")
            toast("Couldn't save: ${e.message}")
        }
    }

    /** Asks the page to read the blob and hand it back as a data: URL via [onBlobData]. */
    private fun fetchBlob(url: String, name: String) {
        // Repeated taps on Save: one request per URL is enough.
        if (pendingBlobs.putIfAbsent(url, name) != null) return
        val js = """
            (async () => {
              let data = '';
              try { if (window.__fbm && window.__fbm.readBlob) data = await window.__fbm.readBlob(${quote(url)}); } catch (e) {}
              window.$NAME.onBlobData(${quote(url)}, data && data.length <= ${MAX_BYTES * 4 / 3 + 100} ? data : '');
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    /** Called by the page with the blob's contents. Ignored unless we asked for this URL. */
    @JavascriptInterface
    fun onBlobData(url: String, dataUrl: String) {
        val name = pendingBlobs.remove(url) ?: return
        if (dataUrl.isEmpty()) {
            Log.w(TAG, "Page couldn't read blob for download")
            toast("Couldn't read the file from the page")
            return
        }
        main.post { saveDataUrl(dataUrl, name) }
    }

    private fun saveDataUrl(dataUrl: String, fallbackName: String) {
        val header = dataUrl.substringBefore(',')
        val mime = header.removePrefix("data:").substringBefore(';').ifBlank { "application/octet-stream" }
        val bytes = try {
            if (header.endsWith(";base64")) Base64.decode(dataUrl.substringAfter(','), Base64.DEFAULT)
            else Uri.decode(dataUrl.substringAfter(',')).toByteArray()
        } catch (e: IllegalArgumentException) {
            toast("Couldn't read the file")
            return
        }
        if (bytes.size > MAX_BYTES) return toast("File too large to save")
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val now = System.currentTimeMillis()
        recentSaves.entries.removeAll { now - it.value > 30_000 }
        if (recentSaves.containsKey(hash)) {
            Log.d(TAG, "Skipped duplicate download")
            return
        }
        recentSaves[hash] = now
        val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
        val name = if (ext != null && !fallbackName.endsWith(".$ext")) fallbackName.substringBeforeLast('.') + ".$ext" else fallbackName
        try {
            val where = writeBytes(bytes, name, mime)
            toast("Saved to $where")
        } catch (e: Exception) {
            Log.w(TAG, "Save failed: ${e.message}")
            toast("Couldn't save: ${e.message}")
        }
    }

    /** Writes into Pictures/PureFeed or Download/PureFeed; returns where, for the toast. */
    private fun writeBytes(bytes: ByteArray, name: String, mime: String): String {
        val image = isImage(mime, name)
        val dir = if (image) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_DOWNLOADS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = if (image) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/PureFeed")
            }
            val uri = context.contentResolver.insert(collection, values) ?: error("no media store entry")
            context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
            return "$dir/PureFeed"
        }
        val target = File(context.getExternalFilesDir(dir), "PureFeed").apply { mkdirs() }
        File(target, name).writeBytes(bytes)
        return target.path
    }

    private fun isImage(mime: String?, name: String) =
        mime?.startsWith("image/") == true || name.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "heic")

    private fun toast(msg: String) {
        main.post { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun quote(s: String) = "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"

    companion object {
        /** Name of the JavaScript interface used for blob: downloads. */
        const val NAME = "PureFeedDownloads"
        private const val TAG = "PureFeed"
        private const val MAX_BYTES = 50 * 1024 * 1024
    }
}
