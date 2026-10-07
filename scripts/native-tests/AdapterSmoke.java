import com.shilapi.xcertplay.network.userspace.UserSpaceNetwork;
import com.shilapi.xcertplay.transport.EthernetFramePipe;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import kotlin.Unit;

/** Exercises the exact Kotlin socket adapters and attachment lifecycle used by AirPlay. */
public class AdapterSmoke {
    static class Cable implements EthernetFramePipe {
        final BlockingQueue<byte[]> inbound = new LinkedBlockingQueue<>();
        volatile boolean closed;
        public void send(byte[] frame, int timeout) { System.out.println("FRAME " + java.util.HexFormat.of().formatHex(frame)); }
        public byte[] recv(long timeout) {
            try { return inbound.poll(timeout, TimeUnit.MILLISECONDS); }
            catch (InterruptedException stopped) { return null; }
        }
        public void close() { closed = true; }
    }
    public static void main(String[] args) throws Exception {
        Cable cable = new Cable(); AtomicBoolean stopping = new AtomicBoolean();
        InetAddress local = InetAddress.getByName("fe80::2");
        UserSpaceNetwork network = new UserSpaceNetwork(cable, local, new byte[]{2,0,0,0,0,2},
            frame -> Unit.INSTANCE, error -> { if (!stopping.get()) { error.printStackTrace(); System.exit(5); } return Unit.INSTANCE; });
        ServerSocket tcp = network.server(); tcp.bind(new InetSocketAddress(local, 7000));
        DatagramSocket udp = network.datagram(); udp.bind(new InetSocketAddress(local, 7001));
        DatagramSocket timed = network.datagram(); timed.bind(new InetSocketAddress(local, 0)); timed.setSoTimeout(80);
        try { timed.receive(new DatagramPacket(new byte[20],20)); throw new AssertionError("Timeout absent"); }
        catch (SocketTimeoutException expected) { System.out.println("TIMEOUT_OK"); }
        timed.close();
        ServerSocket blocking = network.server(); blocking.bind(new InetSocketAddress(local, 7002));
        Thread blocked = new Thread(() -> {
            try { blocking.accept(); throw new AssertionError("Unexpected accept"); }
            catch (IOException expected) { System.out.println("CLOSE_WAKE_OK"); }
        }); blocked.start(); Thread.sleep(80); blocking.close(); blocked.join(2000);
        if (blocked.isAlive()) throw new AssertionError("Close left accept blocked");
        Thread stream = new Thread(() -> {
            try (Socket client = tcp.accept()) {
                if (!client.getLocalAddress().equals(local) || client.getPort() != 5000) throw new AssertionError("Wrong adapter endpoints");
                client.setTcpNoDelay(true); client.setKeepAlive(true); client.setSoLinger(true,0);
                System.out.println("TCP_ACCEPT " + client.getRemoteSocketAddress());
                byte[] b = new byte[4096]; int total = 0, r;
                while ((r = client.getInputStream().read(b)) > 0) { client.getOutputStream().write(b,0,r); total += r; }
                System.out.println("TCP_EOF " + total);
            } catch (Throwable t) { if (!stopping.get()) { t.printStackTrace(); System.exit(3); } }
        });
        Thread datagrams = new Thread(() -> {
            try { while (!stopping.get()) {
                byte[] b = new byte[4114]; DatagramPacket packet = new DatagramPacket(b,18,4096); udp.receive(packet);
                if (!packet.getAddress().equals(InetAddress.getByName("fe80::1")) || packet.getPort() != 5001) throw new AssertionError("Wrong UDP peer");
                byte[] endpoint = new byte[18+packet.getLength()]; System.arraycopy(packet.getAddress().getAddress(),0,endpoint,0,16);
                endpoint[16]=(byte)(packet.getPort()>>8);endpoint[17]=(byte)packet.getPort();System.arraycopy(b,packet.getOffset(),endpoint,18,packet.getLength());
                System.out.println("UDP_RX " + java.util.HexFormat.of().formatHex(endpoint));
                udp.send(new DatagramPacket(b,packet.getOffset(),packet.getLength(),packet.getSocketAddress()));
            } } catch (Throwable t) { if (!stopping.get()) { t.printStackTrace(); System.exit(4); } }
        });
        stream.start(); datagrams.start(); System.out.println("READY");
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in)); String line;
        while ((line=input.readLine())!=null && !line.equals("STOP")) {
            if (line.startsWith("IN ")) cable.inbound.add(java.util.HexFormat.of().parseHex(line.substring(3)));
        }
        stopping.set(true); network.close(); stream.join(2000); datagrams.join(2000);
        if (stream.isAlive() || datagrams.isAlive() || !cable.closed) throw new AssertionError("Adapter cleanup incomplete");
        UserSpaceNetwork again = new UserSpaceNetwork(new Cable(), local, new byte[]{2,0,0,0,0,2}, frame->Unit.INSTANCE, error->Unit.INSTANCE);
        ServerSocket listener = again.server(); listener.bind(new InetSocketAddress(local,7000)); again.close();
        System.out.println("REATTACH_OK"); System.out.println("DONE");
    }
}
