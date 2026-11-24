package app.droidalter.camera;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class TransportTest {
    private static int assertions;
    private static void check(boolean result, String name) {
        assertions++;
        if (!result) { throw new AssertionError(name); }
    }
    private static void invalid(byte[] bytes) {
        boolean rejected = false;
        try { AnnexB.normalize(ByteBuffer.wrap(bytes)); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "Reject invalid AVCC framing");
    }
    public static void main(String[] args) throws Exception {
        byte[] avcc = {0, 0, 0, 2, 0x67, 0x42, 0, 0, 0, 1, 0x68};
        byte[] annexB = {0, 0, 0, 1, 0x67, 0x42, 0, 0, 0, 1, 0x68};
        check(Arrays.equals(AnnexB.normalize(ByteBuffer.wrap(avcc)), annexB), "Multiple NAL conversion");
        check(Arrays.equals(AnnexB.normalize(ByteBuffer.wrap(annexB)), annexB), "Annex B preservation");
        check(AnnexB.normalize(ByteBuffer.allocate(0)).length == 0, "Empty codec output");
        invalid(new byte[]{0,0,0,8,0x67});
        invalid(new byte[]{0,0,0,0});
        invalid(new byte[]{0,0,0,2,0x67,0x42,0});
        ByteBuffer wire = ByteBuffer.wrap(UsbStreamServer.packet(new byte[]{0x65, 0x42}, 123456L, 1));
        check(wire.getInt() == 0x44414c54, "Wire protocol identifier");
        check(wire.getInt() == 2 && wire.getLong() == 123456L && wire.getInt() == 1, "Access unit size, timestamp and key-frame flag");
        ByteBuffer sliced = ByteBuffer.wrap(new byte[]{9,0,0,0,2,0x67,0x42,9});
        sliced.position(1); sliced.limit(7);
        check(Arrays.equals(AnnexB.normalize(sliced), new byte[]{0,0,0,1,0x67,0x42}), "Buffer boundaries");
        AtomicInteger keyRequests = new AtomicInteger();
        CountDownLatch connected = new CountDownLatch(1);
        try (UsbStreamServer server = new UsbStreamServer(0, new UsbStreamServer.Listener() {
            public void onConnection(boolean value) { if (value) { connected.countDown(); } }
            public void requestKeyFrame() { keyRequests.incrementAndGet(); }
        })) {
            byte[] configuration = {0,0,0,1,0x67,0,0,0,1,0x68};
            server.setConfiguration(configuration);
            try (Socket socket = new Socket("127.0.0.1", server.port())) {
                socket.setSoTimeout(2000);
                new DataOutputStream(socket.getOutputStream()).writeInt(0x44415047);
                check(new DataInputStream(socket.getInputStream()).readLong() > 0, "USB clock synchronization reply");
                check(connected.await(2, TimeUnit.SECONDS), "USB listener connection");
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (keyRequests.get() == 0 && System.nanoTime() < deadline) { Thread.yield(); }
                check(keyRequests.get() == 1, "Request fresh key frame on connect");
                server.publish(new byte[]{99,99}, false, 1000);
                byte[] keyFrame = {0,0,0,1,0x65,5};
                server.publish(keyFrame, true, 2000);
                byte[] configPacket = UsbStreamServer.packet(configuration, 0, 2);
                byte[] framePacket = UsbStreamServer.packet(keyFrame, 2000, 1);
                byte[] received = new byte[configPacket.length + framePacket.length];
                new DataInputStream(socket.getInputStream()).readFully(received);
                byte[] expected = new byte[received.length];
                System.arraycopy(configPacket, 0, expected, 0, configPacket.length);
                System.arraycopy(framePacket, 0, expected, configPacket.length, framePacket.length);
                check(Arrays.equals(received, expected), "Send SPS/PPS then key frame; suppress undecodable inter-frames");
                server.publish(new byte[]{7,8,9}, false, 3000);
                byte[] delta = new byte[23];
                new DataInputStream(socket.getInputStream()).readFully(delta);
                check(Arrays.equals(delta, UsbStreamServer.packet(new byte[]{7,8,9}, 3000, 0)), "Send subsequent frames with timestamps");
                server.close();
                check(socket.getInputStream().read() == -1, "Close releases active connection");
            }
        }
        System.out.println("PASS: " + assertions + " protocol and USB transport assertions");
    }
}
