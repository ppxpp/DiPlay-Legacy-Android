package com.shilapi.xcertplay.network.userspace;
import java.net.InetAddress;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Desktop JNI acceptance driver. Exercises the same lwIP and JNI compiled into the APK. */
public class UserSpaceNative {
    static { System.loadLibrary("diplay_userspace"); }
    native void start(byte[] ip, byte[] mac);
    native void stop();
    native void input(byte[] frame);
    native byte[] output();
    native int socket(boolean udp);
    native void bind(int fd, byte[] ip, int port, boolean listen);
    native int accept(int fd) throws java.net.SocketException;
    native byte[] endpoint(int fd, boolean peer);
    native int read(int fd, byte[] buffer, int offset, int length);
    native void write(int fd, byte[] buffer, int offset, int length);
    native byte[] receive(int fd, int capacity) throws java.io.IOException;
    native void send(int fd, byte[] ip, int port, byte[] buffer, int offset, int length);
    native void option(int fd, int option, int value);
    native void close(int fd);
    static String hex(byte[] b) { return java.util.HexFormat.of().formatHex(b); }
    public static void main(String[] args) throws Exception {
        UserSpaceNative n = new UserSpaceNative();
        byte[] local = InetAddress.getByName("fe80::2").getAddress();
        byte[] peer = InetAddress.getByName("fe80::1").getAddress();
        n.start(local, new byte[]{2,0,0,0,0,2});
        AtomicBoolean stopping = new AtomicBoolean();
        int tcp = n.socket(false), udp = n.socket(true), timed = n.socket(true);
        n.bind(tcp, local, 7000, true); n.bind(udp, local, 7001, false);
        n.bind(timed, local, 0, false); n.option(timed, 0, 80);
        try { n.receive(timed, 20); throw new AssertionError("Receive timeout absent"); }
        catch (java.net.SocketTimeoutException expected) { System.out.println("TIMEOUT_OK"); }
        n.close(timed);
        // Close must wake a thread blocked in accept, rather than leaking it after unplug.
        int blocking = n.socket(false); n.bind(blocking, local, 7002, true);
        Thread blocked = new Thread(() -> {
            try { n.accept(blocking); throw new AssertionError("Unexpected accept"); }
            catch (java.net.SocketException expected) { System.out.println("CLOSE_WAKE_OK"); }
        });
        blocked.start(); Thread.sleep(80); n.close(blocking); blocked.join(2000);
        if (blocked.isAlive()) throw new AssertionError("Close left accept blocked");
        Thread out = new Thread(() -> {
            try { while (!stopping.get()) { byte[] frame = n.output(); if (frame != null) System.out.println("FRAME " + hex(frame)); else Thread.sleep(1); } }
            catch (Throwable t) { if (!stopping.get()) { t.printStackTrace(); System.exit(2); } }
        });
        Thread stream = new Thread(() -> {
            int fd = -1;
            try {
                fd = n.accept(tcp); n.option(fd, 2, 1);
                System.out.println("TCP_ACCEPT " + hex(n.endpoint(fd, true)));
                byte[] b = new byte[4096]; int total = 0, r;
                while ((r = n.read(fd, b, 0, b.length)) > 0) { n.write(fd, b, 0, r); total += r; }
                System.out.println("TCP_EOF " + total);
            } catch (Throwable t) { if (!stopping.get()) { t.printStackTrace(); System.exit(3); } }
            finally { if (fd >= 0) n.close(fd); }
        });
        Thread datagrams = new Thread(() -> {
            try { while (!stopping.get()) { byte[] b = n.receive(udp, 4096); System.out.println("UDP_RX " + hex(b)); n.send(udp, peer, 5001, b, 18, b.length - 18); } }
            catch (Throwable t) { if (!stopping.get()) { t.printStackTrace(); System.exit(4); } }
        });
        out.start(); stream.start(); datagrams.start(); System.out.println("READY");
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = input.readLine()) != null && !line.equals("STOP")) {
            if (line.startsWith("IN ")) n.input(java.util.HexFormat.of().parseHex(line.substring(3)));
        }
        stopping.set(true); n.close(tcp); n.close(udp); out.join(2000); stream.join(2000); datagrams.join(2000); n.stop();
        // The retained tcpip thread must allow another attachment after detach.
        n.start(local, new byte[]{2,0,0,0,0,2}); int again = n.socket(false); n.bind(again, local, 7000, true); n.close(again); n.stop();
        System.out.println("REATTACH_OK"); System.out.println("DONE");
    }
}
