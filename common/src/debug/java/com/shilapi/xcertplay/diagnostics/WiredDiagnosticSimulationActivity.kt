package com.shilapi.xcertplay.diagnostics

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.DiPlayActivity
import com.shilapi.xcertplay.orchestration.WiredNetworkMode

/** Debug-only entry point used by adb/emulator acceptance. Not included in release APKs. */
class WiredDiagnosticSimulationActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val scenario = intent.getStringExtra("scenario") ?: "success"
        val mode = if (scenario == "vpn_unavailable") WiredNetworkMode.VPN else WiredNetworkMode.USERSPACE
        if (com.shilapi.xcertplay.CarPlayBackgroundSession.hasSession()) { finish(); return }
        val run = ConnectionDiagnostics.start(mode, simulated = true)
        val handler = Handler(Looper.getMainLooper())
        DiagnosticSimulation.events(scenario).forEachIndexed { index, event ->
            handler.postDelayed({ if (ConnectionDiagnostics.current === run) event(run) }, index * 150L)
        }
        startActivity(Intent(this, DiPlayActivity::class.java).putExtra("page", "diagnostics"))
        finish()
    }
}
