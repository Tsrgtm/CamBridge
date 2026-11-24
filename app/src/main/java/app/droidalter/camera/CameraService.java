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
    void setControls(CameraControls value) { controls = value.copy(); engine.controls(controls); }

    void startCapture(CameraCatalog.Lens lens, Size size, int fps, int bitrateMbps, SurfaceTexture preview) {
        if (streaming) { return; }
        startForegroundService(new Intent(this, CameraService.class));
        streaming = true;
        status = "Starting low-latency USB camera…";
        startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        if (!wakeLock.isHeld()) { wakeLock.acquire(); }
        captureLens = lens; captureSize = size; captureFps = fps; captureBitrate = bitrateMbps;
        engine.controls(controls);
        engine.start(lens, size, fps, bitrateMbps, preview);
    }

    void updateCapture(CameraCatalog.Lens lens, Size size, int fps, int bitrateMbps, SurfaceTexture preview) {
        if (!streaming) { return; }
        boolean restart = captureLens == null || !captureLens.key.equals(lens.key) || !size.equals(captureSize) || fps != captureFps;
        boolean bitrateChanged = bitrateMbps != captureBitrate;
        captureLens = lens; captureSize = size; captureFps = fps; captureBitrate = bitrateMbps;
        if (restart) { engine.reconfigure(lens, size, fps, bitrateMbps, preview); }
        else if (bitrateChanged) { engine.setBitrate(bitrateMbps); }
    }

    void stopCapture() {
        streaming = false;
        engine.stop();
        releaseForeground();
    }

    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, CameraService.class).setAction("STOP"), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_camera_notification)
                .setContentTitle("CamBridge · USB camera active").setContentText("Streaming continues when the phone locks")
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
        else if (streaming) { startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA); }
        else { stopSelf(); }
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public void onDestroy() {
        streaming = false;
        listener = null;
        if (wakeLock.isHeld()) { wakeLock.release(); }
        engine.release();
        super.onDestroy();
    }
}
