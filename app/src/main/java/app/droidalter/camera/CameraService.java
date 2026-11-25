// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (c) 2026 CamBridge contributors
package app.droidalter.camera;

import android.app.*;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraManager;
import android.os.*;
import android.util.Size;

/** Owns capture independently of the activity and its preview surface. */
public final class CameraService extends Service {
    private final LocalBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private CameraEngine engine;
    private PowerManager.WakeLock wakeLock;
    private volatile CameraEngine.Listener listener;
    private volatile boolean streaming;
    private volatile String status = "Ready · start camera, then connect from OBS";
    private static final String CHANNEL = "usb_camera";
    private CameraCatalog.Lens captureLens;
    private Size captureSize;
    private int captureFps = 30;
    private int captureBitrate = 24;
    private CameraControls controls = new CameraControls();
    private boolean rawMode,modeChanged;
    boolean rawMode(){return rawMode;}
    void setRawMode(boolean value){if(rawMode!=value){rawMode=value;modeChanged=true;engine.setRawMode(value);}}
    private RemoteServer usbControl, wifiControl;
    private PairingIdentity identity;
    private CameraRemote remote;
    private final java.util.concurrent.atomic.AtomicLong revision = new java.util.concurrent.atomic.AtomicLong();
    void remoteChanged() { revision.incrementAndGet(); if (listener != null) { listener.controlsChanged(); } }
    long revision() { return revision.get(); }
    boolean wireless() { return identity != null; }
    String pairingLink() {
        if (identity == null) { return "Wi-Fi is off. USB is always available."; }
        String address = "PHONE_IP";
        try {
            android.net.ConnectivityManager connectivity = (android.net.ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
            for (android.net.Network network : connectivity.getAllNetworks()) {
                android.net.NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
                if (capabilities == null || !capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) { continue; }
                android.net.LinkProperties properties = connectivity.getLinkProperties(network);
                if (properties != null) { for (android.net.LinkAddress link : properties.getLinkAddresses()) {
                    java.net.InetAddress ip = link.getAddress();
                    if (ip instanceof java.net.Inet4Address && !ip.isLoopbackAddress()) { address = ip.getHostAddress(); break; }
                } }
                if (!address.equals("PHONE_IP")) { break; }
            }
            java.util.Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            outer: while (address.equals("PHONE_IP") && interfaces.hasMoreElements()) {
                java.net.NetworkInterface device = interfaces.nextElement(); String name = device.getName();
                if (!(name.startsWith("wlan") || name.startsWith("wifi") || name.startsWith("ap") || name.startsWith("swlan"))) { continue; }
                java.util.Enumeration<java.net.InetAddress> values = device.getInetAddresses();
                while (values.hasMoreElements()) { java.net.InetAddress ip = values.nextElement(); if (ip instanceof java.net.Inet4Address && ip.isSiteLocalAddress() && !ip.isLoopbackAddress()) { address = ip.getHostAddress(); break outer; } }
            }
        } catch (Exception ignored) {}
        return "cambridge://" + address + ":5890?key=" + identity.code + "&fingerprint=" + identity.fingerprint;
    }
    String pairingDetails() {
        if (identity == null) { return "USB needs no Wi-Fi. Enable Wi-Fi only when you want a wireless connection."; }
        return "Paste the connection link into CamBridge on your PC. Only share it with a trusted PC.\n\n" + pairingLink() + "\n\nTurning Wi-Fi off invalidates this pairing key.";
    }
    void setWireless(boolean enabled) throws Exception {
        if (wifiControl != null) { wifiControl.close(); wifiControl = null; }
        identity = enabled ? new PairingIdentity(this) : null;
        engine.setWireless(identity);
        if (identity != null) { wifiControl = new RemoteServer(identity.tls.getServerSocketFactory().createServerSocket(5892, 4), identity.code, this::remoteRequest); }
    }
    private String remoteRequest(String method, String path, String body) throws Exception {
        java.util.concurrent.FutureTask<String> task = new java.util.concurrent.FutureTask<>(() -> {
            if (path.equals("/control") && method.equals("POST")) {
                org.json.JSONObject changes = new org.json.JSONObject(body);
                if (changes.has("wireless")) { setWireless(changes.getBoolean("wireless")); changes.remove("wireless"); remoteChanged(); }
                org.json.JSONObject result = changes.length() == 0 ? remote.state() : remote.apply(changes);
                return result.put("revision", revision()).put("wifiConnectionLink", identity == null ? org.json.JSONObject.NULL : pairingLink()).toString();
            }
            if (!path.equals("/state") || !method.equals("GET")) { throw new IllegalArgumentException("Unsupported request"); }
            return remote.state().put("revision", revision()).put("wifiConnectionLink", identity == null ? org.json.JSONObject.NULL : pairingLink()).toString();
        });
        main.post(task); return task.get(2200, java.util.concurrent.TimeUnit.MILLISECONDS);
    }
    public final class LocalBinder extends Binder { CameraService service() { return CameraService.this; } }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "USB camera streaming", NotificationManager.IMPORTANCE_LOW));
        wakeLock = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CamBridge:UsbCamera");
        engine = new CameraEngine((CameraManager) getSystemService(CAMERA_SERVICE), new CameraEngine.Listener() {
            public void status(String value) {
                status = value;
                main.post(() -> {
                    if (listener != null) { listener.status(value); }
                    if (streaming) { notifications.notify(1, notification()); }
                });
            }
            public void failed(String value) {
                status = value;
                main.post(() -> {
                    streaming = false;
                    releaseForeground();
                    if (listener != null) { listener.failed(value); }
                });
            }
        });
        initializeRemote();
    }

    private void initializeRemote() {
        remote = new CameraRemote(this);
        try { usbControl = new RemoteServer(new java.net.ServerSocket(5891, 4, java.net.InetAddress.getByName("127.0.0.1")), null, this::remoteRequest); }
        catch (Exception error) { status = "Control port: " + error.getMessage(); }
    }
    CameraEngine engine() { return engine; }
    boolean isStreaming() { return streaming; }
    String status() { return status; }
    void setListener(CameraEngine.Listener value) { listener = value; }
    CameraCatalog.Lens captureLens() { return captureLens; }
    Size captureSize() { return captureSize; }
    int captureFps() { return captureFps; }
    int captureBitrate() { return captureBitrate; }
    CameraControls controls() { return controls.copy(); }
    void setControls(CameraControls value) { controls = value.copy(); engine.controls(controls); revision.incrementAndGet(); }

    void startCapture(CameraCatalog.Lens lens, Size size, int fps, int bitrateMbps, SurfaceTexture preview) {
        if (streaming) { return; }
        startForegroundService(new Intent(this, CameraService.class));
        streaming = true;
        status = "Starting low-latency camera…";
        beginForeground();
        if (!wakeLock.isHeld()) { wakeLock.acquire(); }
        captureLens = lens; captureSize = size; captureFps = fps; captureBitrate = bitrateMbps;
        engine.controls(controls);
        engine.start(lens, size, fps, bitrateMbps, preview);
    }

    void updateCapture(CameraCatalog.Lens lens, Size size, int fps, int bitrateMbps, SurfaceTexture preview) {
        if (!streaming) { return; }
        boolean restart = modeChanged || captureLens == null || !captureLens.key.equals(lens.key) || !size.equals(captureSize) || fps != captureFps;
        boolean bitrateChanged = bitrateMbps != captureBitrate;
        captureLens = lens; captureSize = size; captureFps = fps; captureBitrate = bitrateMbps;
        modeChanged=false;
        if (restart) { status="Applying camera settings…"; engine.reconfigure(lens, size, fps, bitrateMbps, preview); }
        else if (bitrateChanged) { engine.setBitrate(bitrateMbps); }
    }

    void stopCapture() {
        streaming = false;
        engine.stop();
        releaseForeground();
    }

    private void beginForeground() {
        if (Build.VERSION.SDK_INT >= 29) { startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA); }
        else { startForeground(1, notification()); }
    }
    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, CameraService.class).setAction("STOP"), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_camera_notification)
                .setContentTitle("CamBridge · camera active").setContentText("Streaming continues when the phone locks")
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "Stop camera", stop).build()).build();
    }

    private void releaseForeground() {
        if (wakeLock.isHeld()) { wakeLock.release(); }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) { stopCapture(); }
        else if (streaming) { beginForeground(); }
        else { stopSelf(); }
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public void onDestroy() {
        streaming = false;
        listener = null;
        if (wakeLock.isHeld()) { wakeLock.release(); }
        if (usbControl != null) { usbControl.close(); }
        if (wifiControl != null) { wifiControl.close(); }
        engine.release();
        super.onDestroy();
    }
}
