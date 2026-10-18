package app.purefeed.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.purefeed.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/** What the update UI shows. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val version: String) : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val progress: Float) : UpdateState
    /** Downloaded and verified, waiting for "Install unknown apps" to be allowed. */
    data class NeedsPermission(val release: Release) : UpdateState
    /** This build can't install the release (different signing key, e.g. a debug build). */
    data class CantSelfUpdate(val release: Release) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Checks GitHub for a newer release (automatically at most once a day, or on demand),
 * then downloads, verifies and installs it when the user agrees.
 */
class UpdateController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
) {
    var state: UpdateState by mutableStateOf(UpdateState.Idle)
        private set

    val installedVersion: String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"

    private val installer = UpdateInstaller(context)
    private var job: Job? = null
    private var verified: File? = null

    /** Automatic check on launch: once a day, if enabled, quietly unless something's new. */
    fun checkOnLaunch() {
        scope.launch {
            val s = settings.updateSettings.first()
            if (!s.autoCheck || System.currentTimeMillis() - s.lastCheckMs < DAY_MS) return@launch
            check(manual = false)
        }
    }

    /** "Check now": always reports the result, and ignores "skip this version". */
    fun checkNow() {
        scope.launch { check(manual = true) }
    }

    private suspend fun check(manual: Boolean) {
        if (manual) state = UpdateState.Checking
        val release = try {
            UpdateChecker.latest()
        } catch (e: Exception) {
            if (manual) state = UpdateState.Failed("Couldn't check for updates: ${e.message}")
            return
        }
        settings.updateUpdateSettings { it.copy(lastCheckMs = System.currentTimeMillis()) }
        val skipped = settings.updateSettings.first().skippedVersion
        state = when {
            release == null || !UpdateChecker.isNewer(release, installedVersion) ->
                if (manual) UpdateState.UpToDate(installedVersion) else UpdateState.Idle
            !manual && release.version == skipped -> UpdateState.Idle
            else -> UpdateState.Available(release)
        }
    }

    fun later() { dismiss() }

    fun skip(release: Release) {
        scope.launch { settings.updateUpdateSettings { it.copy(skippedVersion = release.version) } }
        dismiss()
    }

    fun dismiss() {
        job?.cancel()
        state = UpdateState.Idle
    }

    /** "Update": download, verify, then install (or ask for the install permission first). */
    fun update(release: Release) {
        job?.cancel()
        job = scope.launch {
            state = UpdateState.Downloading(release, 0f)
            try {
                val file = installer.downloadAndVerify(release) { p -> state = UpdateState.Downloading(release, p) }
                verified = file
                installOrAsk(release, file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: UpdateInstaller.SignatureMismatch) {
                state = UpdateState.CantSelfUpdate(release)
            } catch (e: Exception) {
                state = UpdateState.Failed(e.message ?: "The update failed.")
            }
        }
    }

    private fun installOrAsk(release: Release, file: File) {
        if (!installer.canInstall()) {
            state = UpdateState.NeedsPermission(release)
            return
        }
        try {
            installer.install(file)
            state = UpdateState.Idle   // Android's confirmation dialog takes over
        } catch (e: Exception) {
            state = UpdateState.Failed("Couldn't start the install: ${e.message}")
        }
    }

    /** Opens Android's "Install unknown apps" switch for PureFeed. */
    fun openInstallPermission() {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Call from onResume: if the user just allowed installs, carry on with the update. */
    fun onResume() {
        val waiting = state as? UpdateState.NeedsPermission ?: return
        val file = verified ?: return
        if (installer.canInstall()) installOrAsk(waiting.release, file)
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
