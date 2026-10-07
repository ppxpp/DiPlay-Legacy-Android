package com.shilapi.xcertplay

import android.content.Intent
import android.content.DialogInterface
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.shilapi.xcertplay.diagnostics.*
import com.shilapi.xcertplay.orchestration.WiredNetworkMode
import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WiredDiagnosticPageTest {
    private fun textViews(view: View): List<TextView> =
        (if (view is TextView) listOf(view) else emptyList()) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { textViews(view.getChildAt(it)) } else emptyList()

    @Test fun failurePageShowsRealStageEvidenceAndSimulationLabelAfterRecreation() {
        val run = ConnectionDiagnostics.start(WiredNetworkMode.USERSPACE, simulated = true)
        DiagnosticSimulation.events("ncm_missing").forEach { it(run) }
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java, Intent().putExtra("page", "diagnostics")).setup()
        try {
            fun verify() {
                val activity = controller.get()
                val text = textViews(activity.window.decorView).filter { it.visibility == View.VISIBLE }.joinToString("\n") { it.text.toString() }
                assertTrue(text.contains(activity.getString(R.string.wired_diagnostics_simulated)))
                assertTrue(text.contains("Simulated: iPhone configuration exposes no NCM interface"))
                assertTrue(text.contains(activity.getString(R.string.wired_diagnostics_blocked)))
                assertTrue(text.contains(activity.getString(R.string.wired_stage_ncm)))
            }
            verify(); controller.recreate(); verify()
        } finally { controller.pause().stop().destroy(); run.stop() }
    }

    @Test fun selectingModeSavesItWithoutLaunchingAConnection() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java, Intent().putExtra("page", "diagnostics")).setup()
        val activity = controller.get()
        try {
            AirPlayPersistence.saveWiredNetworkMode(activity, WiredNetworkMode.VPN)
            val button = textViews(activity.window.decorView).filterIsInstance<Button>()
                .first { it.text.startsWith(activity.getString(R.string.wired_mode_title)) }
            button.performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            dialog.listView.performItemClick(dialog.listView.getChildAt(1), 1, 1)
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(WiredNetworkMode.USERSPACE, AirPlayPersistence.loadWiredNetworkMode(activity))
            assertFalse(CarPlayBackgroundSession.hasSession())
        } finally { controller.pause().stop().destroy() }
    }
}
