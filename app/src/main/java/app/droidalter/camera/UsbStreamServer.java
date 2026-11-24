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
        default long clockTimeNs() { return System.nanoTime(); }
    }
    private final Listener listener;
    private final ServerSocket server;
    private final ArrayBlockingQueue<byte[]> packets = new ArrayBlockingQueue<>(2);
    private volatile Socket client;
    private volatile boolean running = true;
    private volatile boolean waitingForKeyFrame = true;
    private byte[] configuration = new byte[0];
    private volatile boolean realtimeTimestamps;
    private volatile int rotation;

    UsbStreamServer(int port, Listener listener) throws IOException {
        this.listener = listener;
        server = new ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"));
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
            packets.offer(packet(configuration, rotation, 2));
            waitingForKeyFrame = false;
        }
        if (!packets.offer(packet(frame, presentationTimeUs, (keyFrame ? 1 : 0) | (realtimeTimestamps ? 4 : 0)))) {
            disconnect();
        }
    }

    private void acceptLoop() {
        while (running) {
            try (Socket socket = server.accept()) {
                socket.setTcpNoDelay(true);
                socket.setSendBufferSize(64 * 1024);
                socket.setSoTimeout(2000);
                if (new DataInputStream(socket.getInputStream()).readInt() != 0x44415047) {
                    throw new IOException("Unsupported USB protocol");
                }
                new DataOutputStream(socket.getOutputStream()).writeLong(listener.clockTimeNs());
                synchronized (this) {
                    packets.clear();
                    waitingForKeyFrame = true;
                    client = socket;
                }
                listener.onConnection(true);
                listener.requestKeyFrame();
                OutputStream output = socket.getOutputStream();
                while (running && client == socket && !socket.isClosed()) {
                    byte[] packet = packets.poll(1, TimeUnit.SECONDS);
                    if (packet != null) { output.write(packet); }
                }
            } catch (IOException ignored) {
                // Reconnection is expected when an OBS source is removed or restarted.
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
        packets.clear();
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
