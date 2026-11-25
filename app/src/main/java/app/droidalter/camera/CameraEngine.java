// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (c) 2026 CamBridge contributors
package app.droidalter.camera;

import android.annotation.SuppressLint;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.MeteringRectangle;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

final class CameraEngine {
    interface Listener { void status(String message); void failed(String message); default void controlsChanged() {} }
    private final CameraManager manager;
    private final Listener listener;
    private final HandlerThread cameraThread = new HandlerThread("camera-control");
    private final Handler handler;
    private final AtomicBoolean encoding = new AtomicBoolean(false);
    private CameraDevice camera;
    private CameraCaptureSession session;
    private CaptureRequest.Builder request;
    private CameraCharacteristics characteristics;
    private MediaCodec encoder;
    private Surface inputSurface;
    private Surface previewSurface;
    private SurfaceTexture previewTexture;
    private volatile boolean previewAttached;
    private Surface configuredPreviewSurface;
    private Size captureSize;
    private int captureFps;
    private int sessionGeneration;
    private UsbStreamServer server;
    private volatile UsbStreamServer wifiServer;
    private PairingIdentity identity;
    private UsbStreamServer.Listener streamListener;
    private volatile byte[] streamConfiguration = new byte[0];
    SurfaceTexture activePreview() { return previewAttached ? previewTexture : null; }
    void setWireless(PairingIdentity value) {
        handler.post(() -> {
            identity = value;
            if (wifiServer != null) { wifiServer.close(); wifiServer = null; }
            if (running && identity != null && !rawMode) { try { startWifi(); } catch (Exception error) { listener.status("Wi-Fi: " + error.getMessage()); } }
        });
    }
    private void startWifi() throws Exception {
        java.net.ServerSocket socket = identity.tls.getServerSocketFactory().createServerSocket(5890, 2);
        wifiServer = new UsbStreamServer(socket, identity.code, streamListener);
        wifiServer.setConfiguration(streamConfiguration);
        Integer source = characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
        wifiServer.setRealtimeTimestamps(source != null && source == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME);
        Integer orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        wifiServer.setRotation(orientation == null ? 90 : orientation);
    }
    private void configuration(byte[] bytes) {
        streamConfiguration = bytes;
        server.setConfiguration(bytes);
        UsbStreamServer wifi = wifiServer; if (wifi != null) { wifi.setConfiguration(bytes); }
    }
    private Thread drainThread;
    private volatile boolean rawMode;
    private android.media.ImageReader rawReader;
    private HandlerThread rawThread;
    void setRawMode(boolean value){handler.post(()->rawMode=value);}
    private void startRaw(Size size,int startGeneration){
        rawReader=android.media.ImageReader.newInstance(size.getWidth(),size.getHeight(),android.graphics.ImageFormat.YUV_420_888,3);
        inputSurface=rawReader.getSurface();
        final java.util.concurrent.ArrayBlockingQueue<byte[]> pool=new java.util.concurrent.ArrayBlockingQueue<>(3);
        for(int i=0;i<3;i++){pool.add(new byte[36+size.getWidth()*size.getHeight()*3/2]);}
        final Nv12Packer packer=new Nv12Packer();rawThread=new HandlerThread("uncompressed-camera");rawThread.start();
        rawReader.setOnImageAvailableListener(reader->{
            if(!running||generation!=startGeneration){return;}
            try(android.media.Image image=reader.acquireLatestImage()){
                if(image==null){return;}if(server==null||!server.connected()){return;}byte[] buffer=pool.poll();if(buffer==null){return;}
                boolean sent=false;
                try{
                    android.graphics.Rect crop=image.getCropRect();android.media.Image.Plane[] planes=image.getPlanes();
                    packer.pack(planes[0].getBuffer(),planes[0].getRowStride(),planes[0].getPixelStride(),planes[1].getBuffer(),planes[1].getRowStride(),planes[1].getPixelStride(),planes[2].getBuffer(),planes[2].getRowStride(),planes[2].getPixelStride(),crop.left,crop.top,crop.width(),crop.height(),buffer,36);
                    UsbStreamServer output=server;
                    if(output!=null){sent=output.publishRaw(buffer,crop.width(),crop.height(),captureFps,image.getTimestamp()/1000,()->pool.offer(buffer));}
                }finally{if(!sent){pool.offer(buffer);}}
            }catch(Exception error){if(running&&generation==startGeneration){handler.post(()->fail("Uncompressed camera: "+error.getMessage()));}}
        },new Handler(rawThread.getLooper()));
    }
    private volatile boolean running;
    private int generation;
    private CameraControls controls = new CameraControls();
    private CameraCatalog.Lens lens;
    private CameraCharacteristics logicalCharacteristics;

    CameraEngine(CameraManager manager, Listener listener) {
        this.manager = manager;
        this.listener = listener;
        cameraThread.start();
        handler = new Handler(cameraThread.getLooper());
    }

    void start(CameraCatalog.Lens lens, Size size, int framesPerSecond, int bitrateMbps, SurfaceTexture preview) {
        handler.post(() -> startOnCameraThread(lens, size, framesPerSecond, bitrateMbps, preview));
    }

    void reconfigure(CameraCatalog.Lens lens, Size size, int fps, int bitrateMbps, SurfaceTexture preview) {
        handler.post(() -> {
            stopOnCameraThread();
            listener.status("Switching camera settings · output resumes automatically");
            startOnCameraThread(lens, size, fps, bitrateMbps, preview);
        });
    }

    void setBitrate(int bitrateMbps) {
        handler.post(() -> {
            if (encoder == null || !running) { return; }
            Bundle parameters = new Bundle();
            Range<Integer> range = encoder.getCodecInfo().getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).getVideoCapabilities().getBitrateRange();
            parameters.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, range.clamp(bitrateMbps * 1_000_000));
            try { encoder.setParameters(parameters); }
            catch (Exception error) { listener.status("Bitrate: " + error.getMessage()); }
        });
    }

    @SuppressLint("MissingPermission")
    private void startOnCameraThread(CameraCatalog.Lens selectedLens, Size size, int fps, int mbps, SurfaceTexture preview) {
        if (running) { return; }
        running = true;
        final int startGeneration = ++generation;
        captureSize = size;
        captureFps = fps;
        try {
            lens = selectedLens;
            characteristics = lens.characteristics;
            logicalCharacteristics = manager.getCameraCharacteristics(lens.cameraId);
            if(rawMode){startRaw(size,startGeneration);}else{
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            MediaCodecInfo.VideoCapabilities capabilities = encoder.getCodecInfo()
                    .getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).getVideoCapabilities();
            if (!capabilities.areSizeAndRateSupported(size.getWidth(), size.getHeight(), fps)) {
                throw new IllegalArgumentException("The hardware encoder does not support this resolution / frame rate");
            }
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, size.getWidth(), size.getHeight());
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, capabilities.getBitrateRange().clamp(mbps * 1_000_000));
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
            format.setInteger(MediaFormat.KEY_PRIORITY, 0);
            format.setInteger(MediaFormat.KEY_LATENCY, 0);
            format.setInteger(MediaFormat.KEY_OPERATING_RATE, fps);
            for (MediaCodecInfo.CodecProfileLevel level : encoder.getCodecInfo().getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).profileLevels) {
                if (level.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline) {
                    format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
                    break;
                }
            }
            try { encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); }
            catch (IllegalArgumentException | MediaCodec.CodecException unsupportedOptionalKeys) {
                encoder.release();
                encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
                MediaFormat basic = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, size.getWidth(), size.getHeight());
                basic.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                basic.setInteger(MediaFormat.KEY_BIT_RATE, capabilities.getBitrateRange().clamp(mbps * 1_000_000));
                basic.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
                basic.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
                basic.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
                encoder.configure(basic, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            }
            inputSurface = encoder.createInputSurface();
            encoder.start();
            }
            streamListener = new UsbStreamServer.Listener() {
                public void onConnection(boolean connected) {
                    if (running) { listener.status(connected ? (rawMode ? "USB connected · uncompressed NV12" : "Camera connected · live H.264") : "Ready · connect CamBridge on your PC"); }
                }
                public void requestKeyFrame() {
                    handler.post(() -> {
                        if (encoder == null || !running) { return; }
                        Bundle parameters = new Bundle();
                        parameters.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                        try { encoder.setParameters(parameters); } catch (IllegalStateException ignored) { }
                    });
                }
                public void onError(String message) { android.util.Log.w("CamBridge", "Transport: " + message); }
                public long clockTimeNs() { return android.os.SystemClock.elapsedRealtimeNanos(); }
            };
            server = new UsbStreamServer(5889, streamListener);
            server.setRawMode(rawMode);
            if (identity != null && !rawMode) { startWifi(); }
            Integer timestampSource = characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
            server.setRealtimeTimestamps(timestampSource != null && timestampSource == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME);
            Integer sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            server.setRotation(sensorOrientation == null ? 90 : sensorOrientation);
            Size previewSize = choosePreviewSize(size);
            if (preview != null) { preview.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight()); }
            previewSurface = preview == null ? null : new Surface(preview);
            previewTexture = preview;
            previewAttached = preview != null;
            encoding.set(true);
            if(!rawMode){drainThread = new Thread(this::drainEncoder, "h264-encoder-output"); drainThread.start();}
            manager.openCamera(lens.cameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice device) {
                    if (!running || generation != startGeneration) { device.close(); return; }
                    camera = device;
                    createCaptureSession(fps, startGeneration);
                }
                @Override public void onDisconnected(CameraDevice device) {
                    device.close();
                    if (running && generation == startGeneration) { fail("Camera disconnected. Stop and reconnect."); }
                }
                @Override public void onError(CameraDevice device, int error) {
                    device.close();
                    if (running && generation == startGeneration) { fail("Camera error " + error + ". Close other camera apps and try again."); }
                }
            }, handler);
        } catch (Exception error) { fail("Could not start: " + error.getMessage()); }
    }

    @SuppressWarnings("deprecation")
    private Size choosePreviewSize(Size recordingSize) {
        android.hardware.camera2.params.StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] sizes = map == null ? null : map.getOutputSizes(SurfaceTexture.class);
        if (sizes != null) {
            for (Size candidate : new Size[]{new Size(1280, 720), new Size(640, 480)}) {
                if ((long) candidate.getWidth() * recordingSize.getHeight() == (long) candidate.getHeight() * recordingSize.getWidth()
                        && Arrays.asList(sizes).contains(candidate)) { return candidate; }
            }
            for (Size candidate : sizes) {
                if (candidate.getWidth() <= 1920 && candidate.getHeight() <= 1080) { return candidate; }
            }
        }
        return recordingSize;
    }

    private void createCaptureSession(int fps, int startGeneration) {
        final int captureSessionGeneration = ++sessionGeneration;
        final Surface sessionPreview = previewSurface, sessionInput = inputSurface;
        try {
            List<OutputConfiguration> outputs = new ArrayList<>();
            for (Surface surface : Arrays.asList(sessionPreview, sessionInput)) {
                if (surface == null) { continue; }
                OutputConfiguration output = new OutputConfiguration(surface);
                if (android.os.Build.VERSION.SDK_INT >= 28 && lens.physicalId != null) { output.setPhysicalCameraId(lens.physicalId); }
                outputs.add(output);
            }
            CameraCaptureSession.StateCallback callback = new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession configuredSession) {
                    if (!running || camera == null || generation != startGeneration || sessionGeneration != captureSessionGeneration) { configuredSession.close(); return; }
                    session = configuredSession;
                    configuredPreviewSurface = sessionPreview;
                    try {
                        request = android.os.Build.VERSION.SDK_INT < 28 || lens.physicalId == null ? camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                                : camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD, Collections.singleton(lens.physicalId));
                        request.addTarget(sessionInput);
                        if (previewAttached && sessionPreview != null) { request.addTarget(sessionPreview); }
                        Range<Integer>[] ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
                        Range<Integer> selected = null;
                        if (ranges != null) {
                            for (Range<Integer> range : ranges) {
                                if (range.contains(fps) && (selected == null || range.getLower() > selected.getLower())) {
                                    selected = range;
                                }
                            }
                        }
                        if (selected == null) { throw new IllegalArgumentException("Selected frame rate is unavailable for this camera"); }
                        request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, selected);
                        applyControls();
                        listener.status("Ready · connect CamBridge on your PC");
                    } catch (Exception error) { fail("Camera configuration: " + error.getMessage()); }
                }
                @Override public void onConfigureFailed(CameraCaptureSession failedSession) {
                    if (running && generation == startGeneration) {
                        fail("This resolution / frame rate combination is unavailable. Try 1080p at 30 fps.");
                    }
                }
            };

            if (android.os.Build.VERSION.SDK_INT >= 28) { camera.createCaptureSession(new SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, command -> handler.post(command), callback)); }
            else { camera.createCaptureSessionByOutputConfigurations(outputs, callback, handler); }
        } catch (Exception error) { fail("Capture session: " + error.getMessage()); }
    }

    void detachPreview() {
        handler.post(() -> {
            previewAttached = false;
            if (request != null && configuredPreviewSurface != null && session != null) {
                request.removeTarget(configuredPreviewSurface);
                try { session.setRepeatingRequest(request.build(), null, handler); }
                catch (Exception error) { listener.status("Preview detached · USB stream continues"); }
            }
        });
    }

    void attachPreview(SurfaceTexture texture) {
        handler.post(() -> {
            if (!running || camera == null) { return; }
            if (texture == previewTexture) {
                previewAttached = true;
                if (request != null && configuredPreviewSurface != null) {
                    request.addTarget(configuredPreviewSurface);
                    try { applyControls(); } catch (Exception ignored) { }
                }
                return;
            }
            if (session != null) { session.close(); session = null; }
            request = null;
            if (previewSurface != null) { previewSurface.release(); }
            if (previewTexture != null && !previewAttached) { previewTexture.release(); }
            previewTexture = texture;
            previewAttached = true;
            Size size = choosePreviewSize(captureSize);
            texture.setDefaultBufferSize(size.getWidth(), size.getHeight());
            previewSurface = new Surface(texture);
            createCaptureSession(captureFps, generation);
        });
    }

    void controls(CameraControls value) {
        CameraControls snapshot = value.copy();
        handler.post(() -> {
            controls = snapshot;
            try { applyControls(); } catch (Exception error) { listener.status("Control unavailable: " + error.getMessage()); }
        });
    }

    private boolean supports(int[] values, int desired) {
        if (values != null) { for (int value : values) { if (value == desired) { return true; } } }
        return false;
    }

    private <T> void set(CaptureRequest.Key<T> key, T value) {
        if (android.os.Build.VERSION.SDK_INT >= 28 && lens.physicalId != null) {
            List<CaptureRequest.Key<?>> physicalKeys = logicalCharacteristics.getAvailablePhysicalCameraRequestKeys();
            if (physicalKeys != null && physicalKeys.contains(key)) {
                request.setPhysicalCameraKey(key, value, lens.physicalId);
                return;
            }
        }
        request.set(key, value);
    }

    private void applyControls() throws CameraAccessException {
        if (request == null || session == null || characteristics == null) { return; }
        Range<Integer> compensation = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
        set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, compensation == null ? 0 : compensation.clamp(controls.exposureCompensation));
        Float maximum = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
        Rect active = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        Range<Float> zoomRange = android.os.Build.VERSION.SDK_INT >= 30 ? characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) : null;
        if (android.os.Build.VERSION.SDK_INT >= 30 && lens.physicalId == null && zoomRange != null) {
            request.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRange.clamp(controls.zoom));
        } else if (active != null) {
            if (android.os.Build.VERSION.SDK_INT >= 30) { request.set(CaptureRequest.CONTROL_ZOOM_RATIO, 1f); }
            float amount = Math.max(1f, Math.min(controls.zoom, maximum == null ? 1f : maximum));
            int halfWidth = (int) (active.width() / amount / 2);
            int halfHeight = (int) (active.height() / amount / 2);
            set(CaptureRequest.SCALER_CROP_REGION, new Rect(active.centerX() - halfWidth, active.centerY() - halfHeight,
                    active.centerX() + halfWidth, active.centerY() + halfHeight));
        }
        if (Boolean.TRUE.equals(logicalCharacteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE))) {
            request.set(CaptureRequest.FLASH_MODE, controls.torch ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
        }
        int[] modes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        if (modes != null) {
            int desired = controls.focusMode;
            boolean available = false;
            for (int mode : modes) { if (mode == desired) { available = true; break; } }
            if (available) { set(CaptureRequest.CONTROL_AF_MODE, desired); }
        }
        Float minimumFocus = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        if (controls.focusMode == CaptureRequest.CONTROL_AF_MODE_OFF && minimumFocus != null && minimumFocus > 0) {
            set(CaptureRequest.LENS_FOCUS_DISTANCE, Math.max(0, Math.min(controls.focusDistance, minimumFocus)));
        }
        boolean manualAvailable = supports(characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES), CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR);
        if (!controls.autoExposure && manualAvailable) {
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            Range<Integer> sensitivity = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            Range<Long> exposureTimes = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            long period = 1_000_000_000L / captureFps;
            long shutter = Math.min(controls.shutterNs, period - 100_000);
            set(CaptureRequest.SENSOR_SENSITIVITY, sensitivity == null ? controls.iso : sensitivity.clamp(controls.iso));
            set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTimes == null ? shutter : exposureTimes.clamp(shutter));
            set(CaptureRequest.SENSOR_FRAME_DURATION, period);
        } else { set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON); }
        if (Boolean.TRUE.equals(characteristics.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE))) {
            set(CaptureRequest.CONTROL_AE_LOCK, controls.autoExposure && controls.exposureLock);
        }
        if (supports(characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES), controls.whiteBalance)) {
            set(CaptureRequest.CONTROL_AWB_MODE, controls.whiteBalance);
        }
        if (Boolean.TRUE.equals(characteristics.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE))) {
            set(CaptureRequest.CONTROL_AWB_LOCK, controls.whiteBalanceLock);
        }
        if (supports(characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION), CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)) {
            set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, controls.opticalStabilization ? CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON : CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF);
        }
        if (supports(characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES), CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)) {
            set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, controls.videoStabilization ? CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON : CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
        }
        if (supports(characteristics.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES), controls.noiseReduction)) {
            set(CaptureRequest.NOISE_REDUCTION_MODE, controls.noiseReduction);
        }
        if (supports(characteristics.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES), controls.edgeEnhancement)) {
            set(CaptureRequest.EDGE_MODE, controls.edgeEnhancement);
        }
        session.setRepeatingRequest(request.build(), null, handler);
    }

    void focusAt(float x, float y) {
        handler.post(() -> {
            if (request == null || session == null) { return; }
            try {
                Rect active = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
                if (active == null) { return; }
                int half = Math.max(25, active.width() / 24);
                int centerX = active.left + (int) (Math.max(0, Math.min(1, x)) * active.width());
                int centerY = active.top + (int) (Math.max(0, Math.min(1, y)) * active.height());
                int left = Math.max(active.left, Math.min(active.right - half * 2, centerX - half));
                int top = Math.max(active.top, Math.min(active.bottom - half * 2, centerY - half));
                MeteringRectangle[] region = {new MeteringRectangle(new Rect(left, top, left + half * 2, top + half * 2), MeteringRectangle.METERING_WEIGHT_MAX)};
                Integer afRegions = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
                Integer aeRegions = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE);
                if (afRegions != null && afRegions > 0) { set(CaptureRequest.CONTROL_AF_REGIONS, region); }
                if (aeRegions != null && aeRegions > 0) { set(CaptureRequest.CONTROL_AE_REGIONS, region); }
                if (supports(characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES), CaptureRequest.CONTROL_AF_MODE_AUTO)) {
                    controls.focusMode = CaptureRequest.CONTROL_AF_MODE_AUTO;
                    set(CaptureRequest.CONTROL_AF_MODE, controls.focusMode);
                    set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
                    session.capture(request.build(), null, handler);
                    set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
                    session.setRepeatingRequest(request.build(), null, handler);
                }
            } catch (Exception error) { listener.status("Tap focus: " + error.getMessage()); }
        });
    }

    private void drainEncoder() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (encoding.get()) {
                int index = encoder.dequeueOutputBuffer(info, 10_000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat format = encoder.getOutputFormat();
                    ByteArrayOutputStream config = new ByteArrayOutputStream();
                    for (String key : new String[]{"csd-0", "csd-1"}) {
                        ByteBuffer bytes = format.getByteBuffer(key);
                        if (bytes != null) { byte[] normalized = AnnexB.normalize(bytes.duplicate()); config.write(normalized, 0, normalized.length); }
                    }
                    configuration(config.toByteArray());
                } else if (index >= 0) {
                    try {
                        ByteBuffer output = encoder.getOutputBuffer(index);
                        if (output != null && info.size > 0) {
                            output.position(info.offset); output.limit(info.offset + info.size);
                            byte[] bytes = AnnexB.normalize(output);
                            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                configuration(bytes);
                            } else {
                                server.publish(bytes, (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0, info.presentationTimeUs);
                                UsbStreamServer wifi = wifiServer;
                                if (wifi != null) { wifi.publish(bytes, (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0, info.presentationTimeUs); }
                            }
                        }
                    } finally { encoder.releaseOutputBuffer(index, false); }
                }
            }
        } catch (Exception error) {
            if (encoding.get()) { handler.post(() -> fail("Encoder: " + error.getMessage())); }
        }
    }

    private void fail(String reason) { android.util.Log.e("CamBridge", reason); stopOnCameraThread(); listener.failed(reason); }
    void stop() { handler.post(this::stopOnCameraThread); }
    void release() { handler.post(() -> { stopOnCameraThread(); cameraThread.quitSafely(); }); }

    private void stopOnCameraThread() {
        running = false;
        generation++;
        request = null; configuredPreviewSurface = null;
        if (session != null) { session.close(); session = null; }
        if (camera != null) { camera.close(); camera = null; }
        encoding.set(false);
        if (server != null) { server.close(); }
        if (wifiServer != null) { wifiServer.close(); wifiServer = null; }
        streamConfiguration = new byte[0];
        if (drainThread != null) {
            try { drainThread.join(1500); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            drainThread = null;
        }
        server = null;
        if (encoder != null) {
            try { encoder.stop(); } catch (IllegalStateException ignored) { }
            encoder.release(); encoder = null;
        }
        if(rawReader!=null){rawReader.setOnImageAvailableListener(null,null);}
        if(rawThread!=null){rawThread.quitSafely();try{rawThread.join(1500);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}rawThread=null;}
        if(rawReader!=null){rawReader.close();rawReader=null;}
        if (inputSurface != null) { inputSurface.release(); inputSurface = null; }
        if (previewSurface != null) { previewSurface.release(); previewSurface = null; }
        if (previewTexture != null && !previewAttached) { previewTexture.release(); }
        previewTexture = null;
        listener.status("Stopped · camera released");
    }
}
