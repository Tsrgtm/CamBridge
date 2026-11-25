# Build CamBridge

## Prerequisites and build

Windows 10/11 x64, PowerShell 5.1+, Git, internet access, and several GB of free space. The Android APK supports Android 8+ with Camera2; capabilities depend on the camera driver. The companion requires the Windows Media Foundation H.264 decoder (install the Media Feature Pack on Windows N editions). OBS is optional for normal use; building the optional plugin needs OBS 33.0.3 x64 runtime libraries and its matching FFmpeg 8 headers.

```powershell
git clone https://github.com/Tsrgtm/CamBridge.git
cd CamBridge
.\Bootstrap.ps1 -AcceptAndroidLicenses
.\Build.ps1
.\Test.ps1
```

Read the SDK licenses before accepting them. Bootstrap verifies pinned downloads and installs portable toolchains under ignored `tools/`: Temurin JDK 21.0.13.1+2, Gradle 8.14, AGP 8.11.1, SDK 36/build-tools 36.0.0, LLVM-MinGW 20261117 UCRT, OBS 33.0.3 and FFmpeg n8.0 headers. It obtains platform-tools through the SDK manager. Never bypass a changed hash without investigating upstream.

The default build expects OBS in `C:\Program Files\obs-studio`. Pass `-ObsDirectory 'D:\OBS'` for another installation. To build without installing OBS, run `scripts/Prepare-CI.ps1` to fetch the hash-checked official portable runtime, then use `-ObsDirectory tools/obs-runtime` for Build and Test. The standalone companion does not depend on OBS or bundled FFmpeg at runtime.

Build switches: `-AndroidOnly`, `-DesktopOnly`, `-PluginOnly`, or `-SkipDesktop`. Native sources and third-party source needed for the standalone camera are included in `desktop/vendor`; see its provenance guide. No vendor source download is needed for those components.

## Outputs and installation

| Output | Purpose |
| --- | --- |
| `dist/CamBridge-0.5.0.apk` | Development-signed Android preview |
| `dist/CamBridge-Windows-x64-0.5.0.zip` | Companion, 32/64-bit virtual camera, ADB, per-user installer, guides/licenses |
| `dist/CamBridge-OBS-Windows-x64-0.5.0.zip` | Optional OBS source plugin, ADB, installer, guide/licenses |

Use `Install-Android-App.ps1` with an authorized USB phone; `-Serial DEVICE_SERIAL` selects a device. Extract the Windows ZIP and run `Install-CamBridge.cmd`, then open CamBridge from Start. Close CamBridge and camera consumers before updating or uninstalling. The optional OBS plugin has a separate `Install-OBS-Plugin.ps1`; close OBS first. These install commands change the selected device/application; Build and Test do not install anything.

The preview uses `assembleDebug` and your local Android debug key. Keys are not committed. A build made on another machine usually cannot update this APK without uninstalling the prior copy. Production distribution needs a protected release keystore, release signing configuration, and `assembleRelease`. Keep signing keys/passwords outside Git. The preview is not a Play Store release.

## Verification

Run Build before Test. `Test.ps1` checks Annex B framing, bounded transport, authenticated control, raw NV12 packing and ownership, the receiver-ready handshake, and the latest-frame shared memory. It then verifies native OBS registration, Windows H.264 decoding/crop/rotation, and a DirectShow camera consumer graph independent of OBS. `-TransportOnly` skips components requiring installed media runtimes. `gradlew.bat lintDebug` runs Android static checks. CI builds all three artifacts and runs lint and portable regression tests; CI does not claim physical device coverage.

Live device checks are separate: USB/Wi-Fi, reconnect, controls while streaming, full-screen settings drawer, lock/wake, and a recorded clap for A/V synchronization. Only the S24 Ultra has been physically tested so far. Contribute other device results with Android version, public Camera2 capabilities, transport, dimensions and measured FPS. Do not attach pairing keys or private footage.

## Transport and latency

USB uses ADB forwards to phone loopback video 5889 and HTTP control 5891. Wi-Fi uses TLS video 5890 and TLS control 5892 with a pinned certificate and random pairing key. Control exposes `/state` and JSON `/control`; its capability schema drives the PC controls. Phone foreground camera service and a partial wake lock permit streaming after a user starts it while unlocked; OEM background policies can still interrupt it.

The desktop uses Media Foundation H.264 decoding, optional D3D11 acceleration with CPU fallback, SIMD YUV rotation and a single replaceable shared frame. Its DirectShow source is registered for the current Windows user, independently of OBS. Only one companion video producer may run per user. Meeting apps can request different sizes, which are letterboxed without stretching. Audio comes from the recording application's selected microphone.

H.264 is the default. Uncompressed USB sends NV12 frames directly from Camera2 YUV output, bypassing phone encoding and PC video decoding. This is processed YUV, not Bayer sensor RAW. 3840×2160 NV12 at 30 fps requires about 373 MB/s before transport overhead; a slow cable, port, ADB path, driver or PC cannot sustain that. Raw mode keeps the newest pending frame. H.264 queue overflow requests a fresh keyframe rather than replaying an old queue. These bounds reduce accumulated delay; they cannot make sensor exposure, transfer, processing or display instantaneous.

The 20-byte big-endian frame header is `DALT` magic (u32), payload length (u32), timestamp in microseconds (i64), flags (u32). Flags: keyframe 1, codec configuration 2, known realtime sensor clock 4, raw NV12 8. Configuration timestamp carries rotation. Raw payload begins with width, height, rotation and FPS (four big-endian u32), followed by NV12 bytes. Dimensions are even and at most 4096; the desktop rejects oversized packets.

New clients send `DAP2`, receive Android elapsed realtime in nanoseconds (i64) and mode (u32: H.264=0/NV12=1), initialize their decoder, then send `RDY!` before frames begin. Legacy `DAPG` returns only the i64 clock and starts immediately. Wi-Fi first sends `CBG2`, one byte key length (32), and the ASCII pairing key inside TLS.

Legacy Android package `app.droidalter.camera`, OBS source IDs and `droid-alter` module path preserve upgrades. Do not rename them without migration. Visible branding uses CamBridge. The generated master and exported PNG/SVG/ICO assets are under `assets/brand`; the build embeds the multi-size ICO without requiring a resource compiler.
