package com.shilapi.xcertplay.diagnostics

/** Test fixtures only: callers must label the entire run as simulated. No USB or secrets are used. */
object DiagnosticSimulation {
    val scenarios = setOf("success", "usb_denied", "ncm_missing", "network_failed", "airplay_timeout", "vpn_unavailable")
    fun events(scenario: String): List<(ConnectionDiagnosticRun) -> Unit> {
        require(scenario in scenarios)
        val failure = when (scenario) {
            "usb_denied" -> DiagnosticStage.USB_PERMISSION
            "ncm_missing" -> DiagnosticStage.NCM
            "network_failed" -> DiagnosticStage.NETWORK
            "airplay_timeout" -> DiagnosticStage.AIRPLAY
            "vpn_unavailable" -> DiagnosticStage.VPN
            else -> null
        }
        val order = listOf(DiagnosticStage.CAPABILITIES, DiagnosticStage.VPN, DiagnosticStage.AUTH_PROVIDER,
            DiagnosticStage.USB_DEVICE, DiagnosticStage.USB_PERMISSION, DiagnosticStage.USB_CONFIGURATION,
            DiagnosticStage.USBMUX, DiagnosticStage.NCM, DiagnosticStage.PAIRING, DiagnosticStage.CONTROL,
            DiagnosticStage.NETWORK, DiagnosticStage.NETWORK_INPUT, DiagnosticStage.IAP2,
            DiagnosticStage.AUTHENTICATION, DiagnosticStage.NEIGHBOR, DiagnosticStage.AIRPLAY, DiagnosticStage.VIDEO)
        val result = mutableListOf<(ConnectionDiagnosticRun) -> Unit>()
        for (stage in order) {
            result += { run ->
                check(run.simulated) { "Fixture cannot alter a real diagnostic attempt" }
                if (stage != DiagnosticStage.VPN || run.mode == com.shilapi.xcertplay.orchestration.WiredNetworkMode.VPN) run.begin(stage, "Simulated check")
            }
            result += { run ->
                check(run.simulated)
                if (stage == failure) run.fail(stage, when (scenario) {
                    "usb_denied" -> "Simulated: USB permission denied"
                    "ncm_missing" -> "Simulated: iPhone configuration exposes no NCM interface"
                    "network_failed" -> "Simulated: userspace backend initialization failed"
                    "airplay_timeout" -> "Simulated: AirPlay session activation timed out"
                    else -> "Simulated: VPN service unavailable"
                })
                else if (stage != DiagnosticStage.VPN || run.mode == com.shilapi.xcertplay.orchestration.WiredNetworkMode.VPN) run.pass(stage, "Simulated successful operation")
            }
            if (stage == failure) break
        }
        return result
    }
}
