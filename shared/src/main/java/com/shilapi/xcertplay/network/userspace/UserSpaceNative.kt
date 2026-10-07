package com.shilapi.xcertplay.network.userspace

/** lwIP sockets and raw Ethernet I/O; never creates a TUN or changes system routes. */
internal object UserSpaceNative {
    init { System.loadLibrary("diplay_userspace") }
    external fun start(ip: ByteArray, mac: ByteArray)
    external fun stop()
    external fun input(frame: ByteArray)
    external fun output(): ByteArray?
    external fun socket(udp: Boolean): Int
    external fun bind(fd: Int, ip: ByteArray, port: Int, listen: Boolean)
    external fun accept(fd: Int): Int
    external fun endpoint(fd: Int, peer: Boolean): ByteArray
    external fun read(fd: Int, buffer: ByteArray, offset: Int, length: Int): Int
    external fun write(fd: Int, buffer: ByteArray, offset: Int, length: Int)
    external fun receive(fd: Int, capacity: Int): ByteArray
    external fun send(fd: Int, ip: ByteArray, port: Int, buffer: ByteArray, offset: Int, length: Int)
    external fun option(fd: Int, option: Int, value: Int)
    external fun close(fd: Int)
}
