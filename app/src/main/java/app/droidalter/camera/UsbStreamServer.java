// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (c) 2026 CamBridge contributors
package app.droidalter.camera;

import java.io.IOException;
import java.io.OutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Loopback listener exposed to the PC exclusively through an ADB USB forward. */
final class UsbStreamServer implements AutoCloseable {
    interface Listener {
        void onConnection(boolean connected);
        void requestKeyFrame();
        default void onError(String message) {}
        default long clockTimeNs() { return System.nanoTime(); }
    }
    private final Listener listener;
    private final ServerSocket server;
    private static final class FramePacket {
        final byte[] data; final int length; final Runnable release;
        FramePacket(byte[] data,int length,Runnable release){this.data=data;this.length=length;this.release=release;}
        void done(){if(release!=null){release.run();}}
    }
    private final ArrayBlockingQueue<FramePacket> packets = new ArrayBlockingQueue<>(2);
    private volatile boolean rawMode;
    void setRawMode(boolean value){rawMode=value;}
    boolean connected(){return client!=null;}
    private boolean offer(byte[] data){return packets.offer(new FramePacket(data,data.length,null));}
    private void clearPackets(){FramePacket packet;while((packet=packets.poll())!=null){packet.done();}}
    synchronized boolean publishRaw(byte[] data,int width,int height,int fps,long pts,Runnable release){
        if(client==null){return false;}
        int length=36+width*height*3/2;
        ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN).putInt(0x44414c54).putInt(length-20).putLong(pts).putInt(8|(realtimeTimestamps?4:0)).putInt(width).putInt(height).putInt(rotation).putInt(fps);
        // One in-flight frame and one replaceable pending frame. Never replay a raw backlog.
        clearPackets();return packets.offer(new FramePacket(data,length,release));
    }
    private volatile Socket client;
    private volatile boolean running = true;
    private volatile boolean waitingForKeyFrame = true;
    private byte[] configuration = new byte[0];
    private volatile boolean realtimeTimestamps;
    private volatile int rotation;

    UsbStreamServer(int port, Listener listener) throws IOException {
        this(new ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")), null, listener);
    }

    private final String token;
    UsbStreamServer(ServerSocket socket, String token, Listener listener) throws IOException {
        this.token = token;
        this.listener = listener;
        server = socket;
        Thread thread = new Thread(this::acceptLoop, "usb-video-server");
        thread.start();
    }

    synchronized void setConfiguration(byte[] bytes) { configuration = bytes.clone(); }
    int port() { return server.getLocalPort(); }
    void setRealtimeTimestamps(boolean value) { realtimeTimestamps = value; }
    void setRotation(int value) { rotation = value; }

    static byte[] packet(byte[] payload, long presentationTimeUs, int flags) {
        return ByteBuffer.allocate(20 + payload.length).order(ByteOrder.BIG_ENDIAN)
                .putInt(0x44414c54).putInt(payload.length).putLong(presentationTimeUs).putInt(flags).put(payload).array();
    }

    synchronized void publish(byte[] frame, boolean keyFrame, long presentationTimeUs) {
        if (client == null) { return; }
        if (waitingForKeyFrame) {
            if (!keyFrame || configuration.length == 0) { return; }
            offer(packet(configuration, rotation, 2));
            waitingForKeyFrame = false;
        }
        if (!offer(packet(frame, presentationTimeUs, (keyFrame ? 1 : 0) | (realtimeTimestamps ? 4 : 0)))) {
            clearPackets(); waitingForKeyFrame=true; listener.requestKeyFrame();
        }
    }

    private void acceptLoop() {
        while (running) {
            try (Socket socket = server.accept()) {
                socket.setTcpNoDelay(true);
                socket.setSendBufferSize(rawMode ? 2 * 1024 * 1024 : 64 * 1024);
                socket.setSoTimeout(5000);
                if (socket instanceof javax.net.ssl.SSLSocket) { javax.net.ssl.SSLSocket tls = (javax.net.ssl.SSLSocket)socket; tls.setEnabledProtocols(new String[]{"TLSv1.2"}); tls.startHandshake(); }
                DataInputStream input = new DataInputStream(socket.getInputStream());
                if (token != null) {
                    if (input.readInt() != 0x43424732 || input.readUnsignedByte() != 32) { throw new IOException("Pairing required"); }
                    byte[] secret = new byte[32]; input.readFully(secret);
                    if (!java.security.MessageDigest.isEqual(secret, token.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) { throw new IOException("Pairing failed"); }
                }
                int protocol=input.readInt();
                if (protocol != 0x44415047 && protocol != 0x44415032) {
                    throw new IOException("Unsupported USB protocol");
                }
                DataOutputStream hello=new DataOutputStream(socket.getOutputStream());hello.writeLong(listener.clockTimeNs());
                if(protocol==0x44415032){hello.writeInt(rawMode?1:0);if(input.readInt()!=0x52445921){throw new IOException("Receiver not ready");}}
                synchronized (this) {
                    clearPackets();
                    waitingForKeyFrame = true;
                    client = socket;
                }
                listener.onConnection(true);
                listener.requestKeyFrame();
                OutputStream output = socket.getOutputStream();
                while (running && client == socket && !socket.isClosed()) {
                    FramePacket packet = packets.poll(1, TimeUnit.SECONDS);
                    if (packet != null) { try { output.write(packet.data,0,packet.length); } finally { packet.done(); } }
                }
            } catch (IOException ignored) {
                if (running) { listener.onError(ignored.toString()); }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            } finally {
                synchronized (this) { disconnect(); }
                listener.onConnection(false);
            }
        }
    }

    private void disconnect() {
        Socket previous = client;
        client = null;
        clearPackets();
        waitingForKeyFrame = true;
        if (previous != null) {
            try { previous.close(); } catch (IOException ignored) { }
        }
    }

    @Override public synchronized void close() {
        running = false;
        disconnect();
        try { server.close(); } catch (IOException ignored) { }
    }
}
