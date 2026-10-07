package com.shilapi.xcertplay.transport

import java.io.Closeable

/** Raw Ethernet seam for USB NCM and a deterministic virtual cable in network tests. */
interface EthernetFramePipe : Closeable {
    fun send(frame: ByteArray, timeoutMillis: Int)
    fun recv(timeoutMillis: Long): ByteArray?
}
