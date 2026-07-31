package com.mateof.passvault.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mateof.passvault.update.ApkVerifier
import com.mateof.passvault.update.InstallResult
import com.mateof.passvault.update.Release
import com.mateof.passvault.update.UpdateChecker
import com.mateof.passvault.update.UpdateDownloader
import com.mateof.passvault.update.UpdateInstaller
import com.mateof.passvault.update.UpdateOutcome
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Checking for a new version, fetching it, and handing it to the system.
 *
 * Three steps that are deliberately separate on screen. Somebody on mobile data should be able to
 * see that 0.5.0 exists and what changed in it without seven megabytes arriving unasked, and the
 * step that matters — verifying what was downloaded before the package installer sees it — has to
 * be able to refuse in between.
 */
@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val checker: UpdateChecker,
    private val downloader: UpdateDownloader,
    private val verifier: ApkVerifier,
    private val installer: UpdateInstaller,
) : ViewModel() {

    private val _state = MutableStateFlow(UpdateUiState(installed = checker.installedVersion))
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private var downloaded: File? = null

    fun check() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, failure = null, refused = null)
            val outcome = withContext(Dispatchers.IO) { runCatching { checker.check() } }
            _state.value = outcome.fold(
                onSuccess = { status ->
                    when (status) {
                        is UpdateChecker.Status.Available ->
                            _state.value.copy(
                                busy = false,
                                available = status.release,
                                installed = status.installed,
                                checked = true,
                            )
                        is UpdateChecker.Status.UpToDate ->
                            _state.value.copy(
                                busy = false,
                                available = null,
                                installed = status.installed,
                                checked = true,
                            )
                        UpdateChecker.Status.NotUpdatable ->
                            _state.value.copy(busy = false, notUpdatable = true, checked = true)
                    }
                },
                onFailure = { _state.value.copy(busy = false, failure = it.message) },
            )
        }
    }

    /**
     * Downloads and verifies, but does not install.
     *
     * The verification verdict is kept rather than thrown: an APK signed with the wrong key is
     * not an error in this application, it is a finding, and the user is entitled to be told
     * which of the three checks failed.
     */
    fun download() {
        val release = _state.value.available ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, failure = null, refused = null, progress = 0f)
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val digest = downloader.expectedDigest(release)
                    val file = downloader.download(release) { fraction ->
                        _state.value = _state.value.copy(progress = fraction)
                    }
                    file to verifier.verify(file, digest)
                }
            }
            _state.value = outcome.fold(
                onSuccess = { (file, verdict) ->
                    if (verdict is ApkVerifier.Verdict.Ok) {
                        downloaded = file
                        _state.value.copy(busy = false, progress = 1f, ready = true)
                    } else {
                        // Kept off disk: a file that failed verification has no business staying
                        // in the cache where a later attempt might find it.
                        downloader.forget()
                        _state.value.copy(busy = false, progress = 0f, refused = verdict)
                    }
                },
                onFailure = { _state.value.copy(busy = false, progress = 0f, failure = it.message) },
            )
        }
    }

    fun install() {
        val apk = downloaded ?: return
        runCatching { installer.install(apk) }
            .onFailure { _state.value = _state.value.copy(failure = it.message) }
    }

    fun mayInstall(): Boolean = installer.mayInstall()

    fun permissionSettings() = installer.permissionSettings()

    /** Read once when the screen appears: the receiver has no way to reach this instance. */
    fun collectInstallResult() {
        UpdateOutcome.last?.let { result ->
            _state.value = when (result) {
                is InstallResult.Installed -> _state.value.copy(installedNow = true)
                is InstallResult.Failed -> _state.value.copy(failure = result.message)
            }
            UpdateOutcome.clear()
        }
    }
}

data class UpdateUiState(
    val installed: String,
    val checked: Boolean = false,
    val busy: Boolean = false,
    /** The release that supersedes this one, when there is one. */
    val available: Release? = null,
    val progress: Float = 0f,
    /** Downloaded and verified, waiting for the user to say so. */
    val ready: Boolean = false,
    /** Why a downloaded file was not handed to the installer. */
    val refused: ApkVerifier.Verdict? = null,
    val notUpdatable: Boolean = false,
    val installedNow: Boolean = false,
    val failure: String? = null,
)
