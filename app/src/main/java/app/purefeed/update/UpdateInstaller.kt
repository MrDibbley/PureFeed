package app.purefeed.update

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Downloads a release APK, checks it, and hands it to Android's PackageInstaller.
 *
 * Two checks before anything is installed:
 *  1. Its SHA-256 matches the release's SHA256SUMS.txt (catches corrupt or swapped files).
 *  2. It is signed with the SAME certificate as the running app (catches anything not
 *     built with the PureFeed release key, even if GitHub itself served it). Android
 *     enforces this on updates too; checking first gives a clear message instead.
 */
class UpdateInstaller(private val context: Context) {

    class VerifyException(message: String) : Exception(message)

    private val dir = File(context.cacheDir, "updates")

    /** Downloads the APK (reporting 0..1 progress) and verifies it. Returns the checked file. */
    suspend fun downloadAndVerify(release: Release, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val sumsUrl = release.sumsUrl ?: throw VerifyException("The release has no SHA256SUMS.txt, so it can't be checked.")
        val expected = UpdateChecker.shaFor(fetchText(sumsUrl), release.apkName)
            ?: throw VerifyException("SHA256SUMS.txt doesn't list ${release.apkName}.")

        dir.deleteRecursively()
        dir.mkdirs()
        val file = File(dir, "PureFeed-${release.version}.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        val conn = open(release.apkUrl)
        try {
            check(conn.responseCode == 200) { "Download failed: HTTP ${conn.responseCode}" }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
            check(total in 1..MAX_APK_BYTES) { "Unexpected download size" }
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        coroutineContext.ensureActive()   // Cancel stops the download
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        check(done <= MAX_APK_BYTES) { "Download too large" }
                        onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        } finally {
            conn.disconnect()
        }

        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            file.delete()
            throw VerifyException("The download's SHA-256 doesn't match the release. Nothing was installed.")
        }
        verifySignature(file)
        file
    }

    /** True if the APK is this app (same package) signed with the same certificate. */
    private fun verifySignature(file: File) {
        val pm = context.packageManager
        val archive = archiveInfo(pm, file) ?: throw VerifyException("The download isn't a readable APK.")
        if (archive.packageName != context.packageName) {
            throw VerifyException("The download is a different app (${archive.packageName}).")
        }
        val ours = certDigests(signatures(ownInfo(pm)))
        val theirs = certDigests(signatures(archive))
        if (theirs.isEmpty() || theirs != ours) throw SignatureMismatch()
    }

    /** The release is signed with a different key than this build (e.g. a debug build). */
    class SignatureMismatch : Exception("This build is signed with a different key, so it can't update itself.")

    /** Whether Android lets PureFeed install apps ("Install unknown apps" for PureFeed). */
    fun canInstall() = context.packageManager.canRequestPackageInstalls()

    /** Starts the install; Android then shows its own "update this app?" confirmation. */
    fun install(file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("PureFeed.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, InstallResultReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender)
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = true   // GitHub redirects downloads to its file host (https → https)
    }

    private fun fetchText(url: String): String {
        val conn = open(url)
        try {
            check(conn.responseCode == 200) { "Couldn't read SHA256SUMS.txt: HTTP ${conn.responseCode}" }
            return conn.inputStream.use { it.readNBytes(64 * 1024) }.decodeToString()
        } finally {
            conn.disconnect()
        }
    }

    @Suppress("DEPRECATION")
    private fun ownInfo(pm: PackageManager): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }

    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: PackageManager, file: File): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        } else {
            PackageManager.GET_SIGNATURES
        }
        return pm.getPackageArchiveInfo(file.path, flags)
    }

    @SuppressLint("PackageManagerGetSignatures")
    @Suppress("DEPRECATION")
    private fun signatures(info: PackageInfo): List<Signature> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.let { si ->
                val sigs = if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
                if (!sigs.isNullOrEmpty()) return sigs.toList()
            }
        }
        return info.signatures?.toList().orEmpty()
    }

    private fun certDigests(sigs: List<Signature>): Set<String> = sigs.map { sig ->
        MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
    }.toSet()

    companion object {
        private const val MAX_APK_BYTES = 100L * 1024 * 1024
    }
}

/** Receives PackageInstaller's result: shows Android's confirmation, or reports a failure. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit   // Android restarts the updated app
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown error"
                Toast.makeText(context, "Update not installed: $msg", Toast.LENGTH_LONG).show()
            }
        }
    }
}
