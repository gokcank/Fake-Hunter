package com.gokcank.fakehunter.core.detector

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.gokcank.fakehunter.core.export.EvidenceCollector
import com.gokcank.fakehunter.core.model.AuditOptions
import com.gokcank.fakehunter.core.model.AuditReport
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Application-scoped, single-flight coordinator for hardware audits and forensic ZIP exports.
 * Survives Activity configuration / locale recreation without leaking Activity references
 * or spawning concurrent overlapping storage verification tests.
 */
object AuditCoordinator {

    data class State(
        val report: AuditReport? = null,
        val isInspecting: Boolean = false,
        val phase: HardwareDetector.Phase = HardwareDetector.Phase.SYSTEM,
        val progressPercent: Int = 0,
        val progressDetail: String = "",
        val isExportingZip: Boolean = false
    )

    interface Listener {
        fun onStateChanged(state: State)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val lock = Any()

    @Volatile
    private var currentState = State()

    val state: State
        get() = currentState

    fun registerListener(listener: Listener) {
        listeners.add(listener)
        listener.onStateChanged(currentState)
    }

    fun unregisterListener(listener: Listener) {
        listeners.remove(listener)
    }

    /**
     * Starts an audit only if neither an audit nor an initial report exists yet.
     * Used on Activity startup so locale recreation does not re-trigger overlapping storage tests.
     */
    fun ensureInitialAudit(context: Context, options: AuditOptions) {
        val shouldStart = synchronized(lock) {
            currentState.report == null && !currentState.isInspecting
        }
        if (shouldStart) {
            startFullAudit(context, options)
        }
    }

    /**
     * Starts a full audit chain if none is currently running.
     */
    fun startFullAudit(context: Context, options: AuditOptions): Boolean {
        val appContext = context.applicationContext ?: context
        synchronized(lock) {
            if (currentState.isInspecting) return false
            currentState = currentState.copy(
                isInspecting = true,
                phase = HardwareDetector.Phase.SYSTEM,
                progressPercent = 0,
                progressDetail = ""
            )
        }
        dispatchState()

        Thread({
            val result = HardwareDetector.performAudit(appContext, options) { phase, pct, detail ->
                updateState {
                    copy(
                        phase = phase,
                        progressPercent = pct,
                        progressDetail = detail
                    )
                }
            }
            updateState {
                copy(
                    report = result,
                    isInspecting = false,
                    phase = HardwareDetector.Phase.DONE,
                    progressPercent = 100,
                    progressDetail = ""
                )
            }
        }, "FakeHunter-Audit").apply {
            isDaemon = true
            start()
        }
        return true
    }

    /**
     * Re-runs ONLY the deep root inspection and root-assisted flash chip check
     * without repeating the lengthy storage write/read verification test.
     */
    fun startRootOnlyAudit(context: Context): Boolean {
        val appContext = context.applicationContext ?: context
        val baseline: AuditReport = synchronized(lock) {
            if (currentState.isInspecting) return false
            val existing = currentState.report ?: return startFullAudit(
                appContext,
                AuditOptions(requestRoot = true)
            )
            currentState = currentState.copy(
                isInspecting = true,
                phase = HardwareDetector.Phase.ROOT,
                progressPercent = 25,
                progressDetail = ""
            )
            existing
        }
        dispatchState()

        Thread({
            val updated = HardwareDetector.refreshRootAndFlashOnly(baseline) { phase, pct, detail ->
                updateState {
                    copy(
                        phase = phase,
                        progressPercent = pct,
                        progressDetail = detail
                    )
                }
            }
            updateState {
                copy(
                    report = updated,
                    isInspecting = false,
                    phase = HardwareDetector.Phase.DONE,
                    progressPercent = 100,
                    progressDetail = ""
                )
            }
        }, "FakeHunter-RootAudit").apply {
            isDaemon = true
            start()
        }
        return true
    }

    fun exportZipToSafUri(
        context: Context,
        uri: Uri,
        onComplete: (success: Boolean) -> Unit
    ): Boolean {
        val appContext = context.applicationContext ?: context
        val targetReport = synchronized(lock) {
            if (currentState.isExportingZip) return false
            val rep = currentState.report ?: return false
            currentState = currentState.copy(isExportingZip = true)
            rep
        }
        dispatchState()

        Thread({
            val success = try {
                appContext.contentResolver.openOutputStream(uri)?.use { os ->
                    EvidenceCollector.exportEvidenceZip(targetReport, os)
                } ?: false
            } catch (_: Exception) {
                false
            }
            if (!success) {
                EvidenceCollector.deletePartialSafDocument(appContext, uri)
            }
            updateState { copy(isExportingZip = false) }
            mainHandler.post { onComplete(success) }
        }, "FakeHunter-ZipExport").apply {
            isDaemon = true
            start()
        }
        return true
    }

    fun exportZipFallback(
        context: Context,
        onComplete: (savedFile: File?) -> Unit
    ): Boolean {
        val appContext = context.applicationContext ?: context
        val targetReport = synchronized(lock) {
            if (currentState.isExportingZip) return false
            val rep = currentState.report ?: return false
            currentState = currentState.copy(isExportingZip = true)
            rep
        }
        dispatchState()

        Thread({
            val savedFile = EvidenceCollector.exportFallbackToAppDir(appContext, targetReport)
            updateState { copy(isExportingZip = false) }
            mainHandler.post { onComplete(savedFile) }
        }, "FakeHunter-ZipFallback").apply {
            isDaemon = true
            start()
        }
        return true
    }

    private fun updateState(transform: State.() -> State) {
        synchronized(lock) {
            currentState = currentState.transform()
        }
        dispatchState()
    }

    private fun dispatchState() {
        val snapshot = currentState
        if (Looper.myLooper() == Looper.getMainLooper()) {
            listeners.forEach { it.onStateChanged(snapshot) }
        } else {
            mainHandler.post {
                listeners.forEach { it.onStateChanged(snapshot) }
            }
        }
    }
}
