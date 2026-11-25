// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (c) 2026 CamBridge contributors
package app.droidalter.camera;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.MediaCodec;
import android.util.SizeF;
import java.util.*;

final class CameraCatalog {
    static final class Lens {
        final String cameraId, physicalId, key, title, shortName;
        final CameraCharacteristics characteristics;
        final boolean front, automatic;
        final float equivalentFocal;
        Lens(String cameraId, String physicalId, CameraCharacteristics characteristics, boolean automatic) {
            this.cameraId = cameraId; this.physicalId = physicalId; this.characteristics = characteristics;
            this.automatic = automatic;
            this.key = cameraId + ":" + (physicalId == null ? "auto" : physicalId);
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            front = facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;
            float[] focal = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            SizeF sensor = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            equivalentFocal = focal != null && focal.length > 0 && sensor != null
                    ? (float) (focal[0] * 43.2666 / Math.hypot(sensor.getWidth(), sensor.getHeight())) : 0;
            String name;
            if (front) { name = "Front"; }
            else if (automatic) { name = "Rear auto"; }
            else if (equivalentFocal < 18) { name = "Ultra wide"; }
            else if (equivalentFocal < 40) { name = "Main wide"; }
            else if (equivalentFocal < 95) { name = "Telephoto"; }
            else { name = "Long telephoto"; }
            shortName = front ? "Front" : automatic ? "Auto" : equivalentFocal > 0 ? String.format(Locale.US,"%.0fmm",equivalentFocal) : "Rear";
            title = name + (equivalentFocal > 0 && !automatic ? String.format(Locale.US, " · %.0f mm", equivalentFocal) : "")
                    + "  [" + (physicalId == null ? cameraId : cameraId + "/" + physicalId) + "]";
        }
    }

    static List<Lens> discover(CameraManager manager) throws Exception {
        List<Lens> lenses = new ArrayList<>();
        Set<String> publicIds = new TreeSet<>(Arrays.asList(manager.getCameraIdList()));
        Set<String> includedPhysicalIds = new HashSet<>();
        for (String id : publicIds) {
            CameraCharacteristics characteristics; try { characteristics = manager.getCameraCharacteristics(id); } catch (Exception unavailable) { continue; }
            Set<String> physicalIds = (android.os.Build.VERSION.SDK_INT >= 28 ? characteristics.getPhysicalCameraIds() : Collections.<String>emptySet());
            if (physicalIds.isEmpty()) { continue; }
            lenses.add(new Lens(id, null, characteristics, true));
            for (String physical : physicalIds) {
                try {
                    CameraCharacteristics info = manager.getCameraCharacteristics(physical);
                    if (publicIds.contains(physical)) { lenses.add(new Lens(physical, null, info, false)); }
                    else { lenses.add(new Lens(id, physical, info, false)); }
                    includedPhysicalIds.add(physical);
                } catch (Exception error) { android.util.Log.w("CamBridge", "Physical camera " + physical + " unavailable", error); }
            }
        }
        for (String id : publicIds) {
            CameraCharacteristics info; try { info = manager.getCameraCharacteristics(id); } catch (Exception unavailable) { continue; }
            if (includedPhysicalIds.contains(id) || (android.os.Build.VERSION.SDK_INT >= 28 && !info.getPhysicalCameraIds().isEmpty())) { continue; }
            if (info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) == null
                    || info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(MediaCodec.class) == null) { continue; }
            lenses.add(new Lens(id, null, info, false));
        }
        lenses.sort(Comparator.comparing((Lens lens) -> lens.front).thenComparing(lens -> !lens.automatic)
                .thenComparingDouble(lens -> lens.equivalentFocal));

        return lenses;
    }
}
