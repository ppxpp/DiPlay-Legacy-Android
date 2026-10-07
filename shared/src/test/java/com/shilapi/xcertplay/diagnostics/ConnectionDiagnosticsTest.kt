package com.shilapi.xcertplay.diagnostics

import com.shilapi.xcertplay.orchestration.WiredNetworkMode
import org.junit.Assert.*
import org.junit.Test

class ConnectionDiagnosticsTest {
    @Test fun compatibilityDoesNotRequireVpnOrBluetooth() {
        val snapshot = ConnectionDiagnosticRun(WiredNetworkMode.USERSPACE).snapshot()
        assertEquals(DiagnosticState.NOT_APPLICABLE, snapshot.steps.first { it.stage == DiagnosticStage.VPN }.state)
        assertEquals(DiagnosticState.NOT_APPLICABLE, snapshot.steps.first { it.stage == DiagnosticStage.BLUETOOTH }.state)
        assertEquals(DiagnosticState.UNVERIFIED, snapshot.steps.first { it.stage == DiagnosticStage.AUDIO }.state)
    }
    @Test fun initializationNeverImpliesCommunication() {
        val run = ConnectionDiagnosticRun(WiredNetworkMode.USERSPACE)
        run.pass(DiagnosticStage.NETWORK)
        assertEquals(DiagnosticState.NOT_STARTED, run.snapshot().steps.first { it.stage == DiagnosticStage.AIRPLAY }.state)
    }
    @Test fun failedRunRejectsLateSuccessAndRetainsEarlierEvidence() {
        val run = ConnectionDiagnosticRun(WiredNetworkMode.USERSPACE)
        run.pass(DiagnosticStage.USB_DEVICE)
        run.begin(DiagnosticStage.NCM)
        run.fail(reason = "NCM interface unavailable")
        run.pass(DiagnosticStage.NCM)
        run.pass(DiagnosticStage.AIRPLAY)
        assertEquals(DiagnosticStage.NCM, run.snapshot().failure?.stage)
        assertEquals(DiagnosticState.PASSED, run.snapshot().steps.first { it.stage == DiagnosticStage.USB_DEVICE }.state)
        assertEquals(DiagnosticState.NOT_STARTED, run.snapshot().steps.first { it.stage == DiagnosticStage.AIRPLAY }.state)
    }
    @Test fun stoppedAttemptRejectsCallbacks() {
        val run = ConnectionDiagnosticRun(WiredNetworkMode.VPN)
        run.begin(DiagnosticStage.USB_PERMISSION)
        run.stop()
        run.pass(DiagnosticStage.USB_PERMISSION)
        assertEquals(DiagnosticState.UNVERIFIED, run.snapshot().steps.first { it.stage == DiagnosticStage.USB_PERMISSION }.state)
    }
    @Test fun concurrentChecksDoNotLeaveSpinnersAfterFailure() {
        val run = ConnectionDiagnosticRun(WiredNetworkMode.USERSPACE)
        run.begin(DiagnosticStage.NETWORK_INPUT)
        run.begin(DiagnosticStage.AUTHENTICATION)
        run.fail(DiagnosticStage.AUTHENTICATION, "Rejected")
        assertFalse(run.snapshot().steps.any { it.state == DiagnosticState.RUNNING })
    }
    @Test fun simulationScenariosStopAtTheirActualFailure() {
        for (scenario in DiagnosticSimulation.scenarios - "success") {
            val mode = if (scenario == "vpn_unavailable") WiredNetworkMode.VPN else WiredNetworkMode.USERSPACE
            val run = ConnectionDiagnosticRun(mode, simulated = true)
            DiagnosticSimulation.events(scenario).forEach { it(run) }
            assertNotNull(scenario, run.snapshot().failure)
            assertFalse(run.snapshot().steps.any { it.stage == DiagnosticStage.VIDEO && it.state == DiagnosticState.PASSED })
        }
    }
    @Test fun simulatedSuccessLeavesHumanObservedFunctionsUnverified() {
        val run = ConnectionDiagnosticRun(WiredNetworkMode.USERSPACE, simulated = true)
        DiagnosticSimulation.events("success").forEach { it(run) }
        assertNull(run.snapshot().failure)
        assertEquals(DiagnosticState.PASSED, run.snapshot().steps.first { it.stage == DiagnosticStage.VIDEO }.state)
        for (stage in listOf(DiagnosticStage.AUDIO, DiagnosticStage.TOUCH, DiagnosticStage.MICROPHONE)) {
            assertEquals(DiagnosticState.UNVERIFIED, run.snapshot().steps.first { it.stage == stage }.state)
        }
    }
    @Test fun fixtureCannotChangeRealAttempt() {
        val run = ConnectionDiagnosticRun(WiredNetworkMode.USERSPACE)
        try { DiagnosticSimulation.events("success").first()(run); fail("Fixture accepted real run") }
        catch (_: IllegalStateException) {}
        assertTrue(run.snapshot().steps.none { it.state == DiagnosticState.PASSED })
    }
    @Test fun repeatedPacketsPreserveFirstSuccessTime() {
        var time = 1000L
        val run = ConnectionDiagnosticRun(WiredNetworkMode.USERSPACE, clock = { time })
        run.pass(DiagnosticStage.NETWORK_INPUT, "First frame")
        time = 2000L; run.pass(DiagnosticStage.NETWORK_INPUT, "Later frame")
        val step = run.snapshot().steps.first { it.stage == DiagnosticStage.NETWORK_INPUT }
        assertEquals(1000L, step.finishedAt)
        assertEquals("First frame", step.detail)
    }
    @Test fun timingAndSnapshotAreStable() {
        var time = 1000L
        val run = ConnectionDiagnosticRun(WiredNetworkMode.USERSPACE, clock = { time })
        run.begin(DiagnosticStage.NCM); time = 1250; run.pass(DiagnosticStage.NCM)
        val saved = run.snapshot()
        time = 1400; run.begin(DiagnosticStage.NETWORK)
        val step = saved.steps.first { it.stage == DiagnosticStage.NCM }
        assertEquals(1000L, step.startedAt)
        assertEquals(1250L, step.finishedAt)
        assertEquals(DiagnosticState.NOT_STARTED, saved.steps.first { it.stage == DiagnosticStage.NETWORK }.state)
    }
}
