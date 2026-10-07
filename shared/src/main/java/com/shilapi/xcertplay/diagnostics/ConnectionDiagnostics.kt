package com.shilapi.xcertplay.diagnostics

import com.shilapi.xcertplay.orchestration.WiredNetworkMode
import java.util.UUID

enum class DiagnosticStage {
    CAPABILITIES, VPN, BLUETOOTH, AUTH_PROVIDER, USB_DEVICE, USB_PERMISSION,
    USB_CONFIGURATION, USBMUX, PAIRING, CONTROL, NCM, NETWORK, NETWORK_INPUT,
    IAP2, AUTHENTICATION, NEIGHBOR, AIRPLAY, VIDEO, AUDIO, TOUCH, MICROPHONE,
}
enum class DiagnosticState { NOT_STARTED, RUNNING, PASSED, FAILED, UNVERIFIED, NOT_APPLICABLE }
data class DiagnosticStep(
    val stage: DiagnosticStage,
    val state: DiagnosticState = DiagnosticState.NOT_STARTED,
    val detail: String = "",
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
)
data class DiagnosticSnapshot(
    val id: String,
    val mode: WiredNetworkMode,
    val simulated: Boolean,
    val createdAt: Long,
    val stopped: Boolean,
    val steps: List<DiagnosticStep>,
) {
    val failure: DiagnosticStep? get() = steps.firstOrNull { it.state == DiagnosticState.FAILED }
}

/** Structured evidence, not log parsing. Late callbacks cannot mutate a stopped/failed attempt. */
class ConnectionDiagnosticRun(
    val mode: WiredNetworkMode,
    val simulated: Boolean = false,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val id = UUID.randomUUID().toString()
    private val created = clock()
    private var stopped = false
    private val steps = DiagnosticStage.entries.associateWith { DiagnosticStep(it) }.toMutableMap()
    private var current = DiagnosticStage.CAPABILITIES

    init {
        set(DiagnosticStage.BLUETOOTH, DiagnosticState.NOT_APPLICABLE, "Wired USB does not use Bluetooth")
        if (mode == WiredNetworkMode.USERSPACE) set(DiagnosticStage.VPN, DiagnosticState.NOT_APPLICABLE, "Compatibility mode does not use VPN")
        listOf(DiagnosticStage.VIDEO, DiagnosticStage.AUDIO, DiagnosticStage.TOUCH, DiagnosticStage.MICROPHONE).forEach {
            set(it, DiagnosticState.UNVERIFIED, "Awaiting real media/input evidence")
        }
    }
    @Synchronized fun begin(stage: DiagnosticStage, detail: String = "") {
        if (!mutable() || steps.getValue(stage).state == DiagnosticState.PASSED) return
        current = stage
        set(stage, DiagnosticState.RUNNING, detail)
    }
    @Synchronized fun pass(stage: DiagnosticStage, detail: String = "") {
        if (steps.getValue(stage).state == DiagnosticState.PASSED || !mutable()) return
        set(stage, DiagnosticState.PASSED, detail)
    }
    @Synchronized fun fail(stage: DiagnosticStage = current, reason: String) {
        if (!mutable()) return
        set(stage, DiagnosticState.FAILED, reason)
        // Parallel checks did not finish. Preserve passed evidence without leaving spinners forever.
        steps.values.filter { it.state == DiagnosticState.RUNNING && it.stage != stage }.forEach {
            set(it.stage, DiagnosticState.UNVERIFIED, "Interrupted by another failed stage")
        }
    }
    @Synchronized fun unverified(stage: DiagnosticStage, detail: String) {
        if (mutable()) set(stage, DiagnosticState.UNVERIFIED, detail)
    }
    @Synchronized fun stop() {
        stopped = true
        steps.values.filter { it.state == DiagnosticState.RUNNING }.forEach {
            set(it.stage, DiagnosticState.UNVERIFIED, "Detection stopped before completion")
        }
    }
    @Synchronized fun snapshot() = DiagnosticSnapshot(id, mode, simulated, created, stopped, DiagnosticStage.entries.map { steps.getValue(it) })
    private fun mutable() = !stopped && steps.values.none { it.state == DiagnosticState.FAILED }
    private fun set(stage: DiagnosticStage, state: DiagnosticState, detail: String) {
        val previous = steps.getValue(stage)
        val now = clock()
        steps[stage] = previous.copy(state = state, detail = detail.take(240),
            startedAt = previous.startedAt ?: if (state == DiagnosticState.NOT_APPLICABLE) null else now,
            finishedAt = if (state == DiagnosticState.RUNNING) null else now)
    }
}

object ConnectionDiagnostics {
    @Volatile var current: ConnectionDiagnosticRun? = null
        private set
    @Synchronized fun start(mode: WiredNetworkMode, simulated: Boolean = false): ConnectionDiagnosticRun =
        ConnectionDiagnosticRun(mode, simulated).also { current = it }
    fun report(): String {
        val snapshot = current?.snapshot() ?: return "No wired diagnostic attempt recorded."
        return buildString {
            appendLine("Wired diagnostic id=${snapshot.id} mode=${snapshot.mode} simulated=${snapshot.simulated} started=${snapshot.createdAt} stopped=${snapshot.stopped}")
            snapshot.steps.forEach { step ->
                appendLine("${step.stage}: ${step.state}; ${step.detail}; started=${step.startedAt}; finished=${step.finishedAt}")
            }
        }
    }
}
