package com.shilapi.xcertplay

import android.content.Context
import android.app.Application
import com.shilapi.xcertplay.orchestration.WiredNetworkMode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WiredModePersistenceTest {
    @Test fun explicitSelectionSurvivesReadsAndInvalidValueUsesStandardDefault() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
        assertEquals(WiredNetworkMode.VPN, AirPlayPersistence.loadWiredNetworkMode(context))
        AirPlayPersistence.saveWiredNetworkMode(context, WiredNetworkMode.USERSPACE)
        assertEquals(WiredNetworkMode.USERSPACE, AirPlayPersistence.loadWiredNetworkMode(context))
        context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().putString("wired_network_mode", "AUTO").commit()
        assertEquals(WiredNetworkMode.VPN, AirPlayPersistence.loadWiredNetworkMode(context))
    }
}
