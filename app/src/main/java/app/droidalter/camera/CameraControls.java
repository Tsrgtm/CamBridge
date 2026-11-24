// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (c) 2026 CamBridge contributors
package app.droidalter.camera;

final class CameraControls {
    float zoom = 1f;
    int exposureCompensation;
    boolean torch;
    int focusMode = 3;
    float focusDistance;
    boolean autoExposure = true;
    boolean exposureLock;
    int iso = 200;
    long shutterNs = 16_666_666L;
    int whiteBalance = 1;
    boolean whiteBalanceLock;
    boolean opticalStabilization = true;
    boolean videoStabilization;
    int noiseReduction = 1;
    int edgeEnhancement = 1;

    CameraControls copy() {
        CameraControls copy = new CameraControls();
        copy.zoom = zoom; copy.exposureCompensation = exposureCompensation; copy.torch = torch;
        copy.focusMode = focusMode; copy.focusDistance = focusDistance; copy.autoExposure = autoExposure;
        copy.exposureLock = exposureLock; copy.iso = iso; copy.shutterNs = shutterNs;
        copy.whiteBalance = whiteBalance; copy.whiteBalanceLock = whiteBalanceLock;
        copy.opticalStabilization = opticalStabilization; copy.videoStabilization = videoStabilization;
        copy.noiseReduction = noiseReduction; copy.edgeEnhancement = edgeEnhancement;
        return copy;
    }
}
