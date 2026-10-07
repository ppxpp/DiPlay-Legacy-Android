package com.shilapi.xcertplay.network

import java.net.DatagramSocket
import java.net.ServerSocket

/** Session-owned factory: dynamic AirPlay ports must use the same backend as control. */
interface AirPlayNetwork {
    fun server(): ServerSocket
    fun datagram(): DatagramSocket
    val supportsIpv4: Boolean get() = true
}

object SystemAirPlayNetwork : AirPlayNetwork {
    override fun server() = ServerSocket()
    override fun datagram() = DatagramSocket(null)
}
