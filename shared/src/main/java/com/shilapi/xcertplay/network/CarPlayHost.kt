package com.shilapi.xcertplay.network

import android.os.Binder
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.transport.NcmUsbBridge
import java.net.InetAddress

/** Service seam keeps the compatibility path independent of the VPN service class. */
interface CarPlayHost {
    sealed class AttachResult {
        data object Started : AttachResult()
        data object AlreadyStarted : AttachResult()
        data class Failed(val message: String) : AttachResult()
    }
    fun attach(ncm: NcmUsbBridge, linkLocal: String, hostMac: ByteArray, config: AirPlayConfig,
        identity: AirPlayIdentity, pairings: PairingStore, mfi: MfiAuthenticator?,
        listener: AirPlaySessionListener, media: AirPlayMediaHandler): AttachResult
    fun attachWireless(bindAddress: InetAddress, config: AirPlayConfig, identity: AirPlayIdentity,
        pairings: PairingStore, mfi: MfiAuthenticator?, listener: AirPlaySessionListener,
        media: AirPlayMediaHandler): AttachResult
    fun detach()
    fun isAttached(): Boolean
}
class CarPlayHostBinder(val host: CarPlayHost) : Binder()
