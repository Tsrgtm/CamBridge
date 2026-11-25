<p align="center"><img src="assets/brand/cambridge-banner.png" alt="CamBridge — your phone, your camera, your OBS" width="850"></p>

# CamBridge

**Use an Android phone as a Windows camera over USB or paired Wi-Fi. Free and open source.**

CamBridge includes an Android camera app, a standalone Windows receiver/control panel, a Windows virtual camera, and an optional native OBS source. The Windows companion does not need OBS installed or running.

**[Downloads](https://github.com/Tsrgtm/CamBridge/releases)** · **[Build guide](BUILDING.md)** · **[Issues](https://github.com/Tsrgtm/CamBridge/issues)** · **[Security](SECURITY.md)**

## Features

- USB video through ADB, or encrypted Wi-Fi with a device certificate and a random pairing key.
- **CamBridge Camera** output in compatible Windows camera applications, with 64-bit and 32-bit DirectShow components.
- Live PC controls generated from the phone's camera capabilities: lens, zoom, focus, torch, exposure, ISO, shutter, white balance, stabilization, processing, resolution, FPS, and bitrate.
- Full-screen phone preview with lens buttons, zoom, flash, front/rear switching, start/stop, and a settings drawer. Opening settings keeps the same preview surface attached.
- Hardware H.264 for efficient USB/Wi-Fi streaming, and optional **uncompressed NV12 over USB** to bypass video encoding and decoding.
- 4K where the selected camera and output format support it; portrait/landscape output and optional mirroring.
- Bounded transport buffers and a replaceable latest-frame Windows output. Older frames are dropped instead of building an ever-longer playback queue.
- An already-started stream can continue when the phone screen is off. Audio is added separately in the receiving Windows app.

## Compatibility

| Part | Requirements |
| --- | --- |
| Phone | Android **8.0+**, a Camera2 video output, and an AVC encoder for H.264 mode. Pure Java APK; no manufacturer-specific camera IDs or native ABI requirement. |
| Windows companion | Windows **10/11 x64**, .NET Framework 4.8 and Windows Media Foundation for H.264. Windows N editions may need Microsoft's Media Feature Pack. |
| Other camera apps | Applications accepting a DirectShow virtual camera. Restart the receiving app after installation. Compatibility with every application is not guaranteed. |
| Optional OBS plugin | OBS Studio **33.0.3, Windows x64**, with its supplied FFmpeg DLLs. |
| USB | A data cable, USB debugging, authorization of this PC, and a working OEM ADB driver if Windows needs one. |

The app discovers capabilities rather than assuming Samsung, Pixel, Xiaomi, OnePlus, or any other brand's lens layout. Fixed-focus cameras, missing manual exposure, restricted physical lenses, and unsupported stream combinations are handled by exposing only reported controls. Android version support is not a claim that every phone has been physically tested. Device testing to date uses a Galaxy S24 Ultra; broader device reports are welcome.

## Install

1. Download `CamBridge-0.5.0.apk` and `CamBridge-Windows-x64-0.5.0.zip` from Releases.
2. On Android, open the APK, allow installation from that file source when asked, and open **CamBridge**. Allow **Camera** and notifications.
3. Extract the Windows ZIP. Run **Install-CamBridge.cmd** under the Windows account that will use the camera. It installs under `%LOCALAPPDATA%\Programs\CamBridge\0.5.0`, registers both camera DLLs for that user, and creates a Start menu shortcut. Administrator rights are normally unnecessary.
4. Open **CamBridge** from Start. Close other CamBridge instances before updating.

The preview APK is development-signed. It can update earlier Droid Alter/CamBridge APKs signed on the same release machine. A build from another machine normally has a different debug signing key and may require uninstalling first. Private signing keys are not in the repository.

## USB connection

1. Enable Android Developer options. Usually: Settings → About phone → tap **Build number** seven times. Some brands place this inside Software information.
2. Enable **USB debugging**, connect a USB **data** cable, unlock the phone, and approve **Allow USB debugging?** for this trusted PC.
3. On the phone, tap **Start camera**. Start it while the phone is unlocked and the app is visible.
4. In the Windows companion select **USB** and click **Connect**. Leave USB serial blank for one connected phone. If several are connected, enter the intended serial from `adb devices -l`.
5. In the receiving app's camera selector choose **CamBridge Camera**. Keep the Windows companion open while using it.

No IP address is required for USB. Closing a receiving app does not stop the phone camera; use the phone's **Stop camera** button or notification when finished.

## Wi-Fi connection

Use the same local network; start at **1080p/30 and 8 Mbps**. A good 5 GHz/6 GHz connection is preferable for high bitrate video. Lower bitrate if the measured FPS falls. The current network test improved from about 3 fps at 40 Mbps to 22 fps at 8 Mbps; wireless performance is not guaranteed. Guest networks and access-point isolation can block device-to-device connections.

**USB-assisted pairing:** connect over USB first, then click **Prepare Wi-Fi** in the PC companion. It obtains the phone's connection link through the trusted USB control channel. Disconnect in the companion, select **Wi-Fi**, and connect using the prepared link. The USB cable can then be removed.

**Wireless-only pairing:** on the phone open **Stream** → enable **encrypted Wi-Fi** → **Copy Wi-Fi connection link**. Transfer that link privately to your PC, paste it into the companion's Wi-Fi field, and connect. It contains the local address, a random 128-bit key, and the certificate fingerprint. Do not post it in issues or screenshots. The companion checks the certificate fingerprint; it does not disable certificate verification globally.

Turning Wi-Fi off/on or restarting the phone service changes the pairing key; prepare/copy a new link. Wi-Fi video uses H.264. If uncompressed USB mode is selected, change Video transport back to H.264 from the controls. The control panel can load even while video is reconnecting.

## Camera controls and full-screen preview

The phone opens with a full-screen preview and visible controls. Tap a lens, pinch/drag zoom, tap the image to focus, toggle **Flash**, or use **Flip** for front/rear. **Settings**, **Exposure**, and **Stream** open the settings drawer without moving the preview surface. **Close settings** returns to the camera overlay. These overlays are not burned into the output video.

The PC's Camera, Exposure, Color, and Stream tabs mirror the phone's supported settings. Unsupported controls are disabled. Turn automatic exposure off before choosing manual ISO/shutter. Select manual focus before setting focus distance. Frame rate and sensor limits constrain shutter duration.

Lens, resolution, FPS, and video-transport changes briefly restart capture. Receiving apps keep their camera connection and may show the last frame or standby during reconnection. Bitrate, zoom, exposure, and other supported camera controls update live. Some phone manufacturers still restrict physical lenses or combinations that their stock camera can use.

## 4K and minimum delay

Start with **H.264, 3840×2160, 30 fps, 24–40 Mbps**, if the camera reports that combination. For weaker phones or links, start at 1280×720/30 and increase resolution. Use fast/off processing and enough light for a short exposure. Stabilization and long exposure can add sensor processing time.

The Windows receiver prefers the high-performance GPU for H.264 when available, with CPU fallback. A 120-frame 4K USB test reached 29 fps on the tested RTX 4070 laptop; other runs, including screen-off capture, were slower. This is throughput, not a measured end-to-end latency or a sustained-performance guarantee.

**Uncompressed USB** sends NV12 camera frames directly; it is uncompressed video, not Bayer/DNG sensor RAW. It avoids the H.264 encoder and decoder, but moves much more data:

| Format at 30 fps | Approximate video payload |
| --- | --- |
| 1280×720 NV12 | 41.5 MB/s |
| 1920×1080 NV12 | 93.3 MB/s |
| 3840×2160 NV12 | **373.2 MB/s / 2.99 Gbit/s** |

These numbers exclude protocol overhead. USB 2 cannot carry uncompressed 4K/30. A USB 3-capable phone, cable, port, and sufficiently fast software path are required. Many charging cables only carry USB 2 data. The preview test on the current USB path delivered about 34 MB/s and 2.8 fps at uncompressed 4K; it does not establish a universal limit for other hardware. H.264 can perform much better on such a link.

Choose **Video transport → Uncompressed NV12** in the PC Stream tab, or the phone's uncompressed USB switch, to test it. Watch the PC's measured FPS and MB/s. Do not assume uncompressed is faster: a single 4K NV12 frame is 12.4 MB. Use H.264 if raw transfers reduce frame rate or increase delay.

**Zero latency is not physically possible.** Sensor readout, exposure, transport, frame conversion, and display refresh take time. CamBridge avoids a playback backlog and uses a receiver-ready handshake before sending frames. PC frame-work timings are not complete camera-to-screen latency. For audio sync, record a visible hand clap, review the recording, and adjust the microphone sync offset in the receiving app if needed.

## Keep streaming while locked

Start capture while unlocked with CamBridge visible, then turn the screen off. The foreground camera service and notification keep the active capture running. If an OEM stops it, allow notifications and set CamBridge's battery usage to **Unrestricted**. This does not bypass Android's restriction on starting camera capture from the background after reboot or force-stop.

## OBS

You can select **CamBridge Camera** in OBS's **Video Capture Device** source using the standalone companion.

Alternatively, install `CamBridge-OBS-Windows-x64-0.5.0.zip`: close OBS, extract it, and run its installer as administrator if ProgramData permissions require it. Add **CamBridge USB Camera**. This native source connects directly over USB and handles H.264 and NV12. Do not have it and the companion compete for the same phone's USB video stream.

The native plugin retains the installed `droid-alter` directory, DLL name, source identifiers, and Android package ID so old scenes and app upgrades remain compatible. Its visible name and artwork are CamBridge.

For vertical recording: OBS Settings → Video → set canvas/output to **1080×1920**, choose 30 fps, set the camera orientation upright, and fit the source to the canvas. Add your microphone separately. OBS's standard source-picker camera symbol is used; the custom CamBridge icon is embedded in the DLL and supplied as an ICO.

## Troubleshooting

| Symptom | Action |
| --- | --- |
| USB device absent or unauthorized | Unlock, approve debugging, try another data cable/direct port, and check `adb devices -l`. File transfer alone does not prove ADB is available. Use the manufacturer's official Windows USB driver if necessary. |
| Camera missing in another app | Restart that app after installation, run CamBridge under the same Windows user, check camera permission in that app, and try a supported DirectShow application. |
| Black preview / camera busy | Stop other camera apps, start CamBridge while unlocked, and try rear auto at 720p/30. Read the phone status and PC error. |
| Unsupported lens or resolution | Select another exposed route or reduce resolution/FPS. Android's public API may expose fewer options than the stock camera. |
| Wi-Fi refuses to connect | Prepare a fresh link, check the same LAN and guest isolation, and use H.264. If the IP changed, prepare/copy the link again. |
| Raw 4K is slow | Check measured MB/s, use a genuine USB 3 path, or return to H.264. Removing compression does not remove bandwidth limits. |
| Wrong aspect or orientation | Change the companion orientation/mirror controls. The virtual camera letterboxes to the receiving app's requested shape. |
| Stream stops when locked | Start while unlocked, allow the foreground notification, and remove battery restrictions. |
| Other app already uses the stream | Close duplicate receivers; one companion publishes one camera output per Windows user. |

Report app versions, phone model/Android version, Windows/app version, selected lens, format, resolution/FPS, and a minimal reproduction. Remove pairing links, device serials, private paths, and personal video from logs/screenshots.

## Uninstall

Stop the camera and close receiving apps/CamBridge. Run `Uninstall-Windows.ps1` from the installed folder to unregister both camera DLLs and remove the shortcut. You can then remove that folder. Uninstall the APK in Android settings. For the optional OBS plugin, close OBS and remove its `droid-alter` plugin folder. Revoke USB debugging authorization when it is no longer needed.

## Open source

CamBridge is **GPL-2.0-or-later**. Source, build/install scripts, tests, brand assets, and third-party notices are included. See [LICENSE](LICENSE), [NOTICE.md](NOTICE.md), and [CONTRIBUTING.md](CONTRIBUTING.md). The virtual-camera component adapts OBS/libdshowcapture; optimized YUV processing uses libyuv. CamBridge is independent of OBS, Google, Samsung, and DroidCam.
