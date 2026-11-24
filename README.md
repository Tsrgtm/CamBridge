<p align="center"><img src="assets/brand/cambridge-banner.png" alt="CamBridge — your phone, your camera, your OBS" width="850"></p>

# CamBridge

**Turn an Android phone into a USB camera for OBS Studio on Windows. Free and open source.**

Hardware H.264 encoding on the phone, a direct native OBS decoder, and camera controls you can change while streaming. No account, subscription, watermark, or Wi-Fi connection is required.

**[Download APK and Windows plugin](https://github.com/Tsrgtm/CamBridge/releases)** · **[Build from source](BUILDING.md)** · **[Report an issue](https://github.com/Tsrgtm/CamBridge/issues)**

## What it does

- Android camera app with a matching Windows x64 OBS source plugin.
- Portrait or landscape output, automatic lens orientation, and optional mirroring.
- Logical and physical Camera2 lens discovery, including compatible telephoto lenses.
- Live zoom, tap to focus, manual focus, torch, exposure compensation, ISO, shutter, white balance, stabilization, noise reduction, and sharpening where supported.
- Live bitrate changes. Changing lens, resolution, or frame rate briefly restarts capture while OBS recording continues.
- Foreground camera service that keeps an already-started stream running when the phone locks.
- USB transport through ADB and direct decoding without a media-player playback queue.

This is an early **0.4.0 preview**. Tested with a Samsung Galaxy S24 Ultra and **OBS Studio 33.0.3 for Windows x64**. Other phones and OBS versions are not yet verified. Android 10 or newer is required. Microphone audio is added separately in OBS; the APK sends video only. This is an OBS source, not a system-wide webcam driver.

## 1. Install the phone app

1. Download `CamBridge-0.4.0.apk` from Releases onto your phone.
2. Open it and allow that browser or file manager to install this APK when Android asks.
3. Open **CamBridge** and allow **Camera** access. Allow notifications so the running camera and **Stop camera** action stay visible.
4. Enable Android **Developer options**: on Samsung, Settings → About phone → Software information → tap **Build number** seven times, then enter your PIN.
5. Open Settings → Developer options and enable **USB debugging**.

You can update the previously installed Droid Alter app directly with this APK. Its internal application ID is deliberately unchanged. Preview APKs currently use the development signing key from the release build machine; that key is not included in the source repository. A locally rebuilt APK will normally require uninstalling the published APK first. Camera preferences may be lost on uninstall.

## 2. Install the OBS plugin

1. Install **OBS Studio 33.0.3, Windows x64** from [OBS](https://obsproject.com/). This preview depends on OBS's `avcodec-62.dll` and `avutil-60.dll`.
2. Close OBS completely.
3. Download and **extract** `CamBridge-OBS-Windows-x64-0.4.0.zip` to a folder. Do not run it inside the ZIP.
4. Right-click **Install-CamBridge.cmd** → **Run as administrator**. The installer copies the plugin into `C:\ProgramData\obs-studio\plugins\droid-alter`.
5. Open OBS. In **Sources**, click **+** → **CamBridge USB Camera**.

Manual installation: copy the extracted `droid-alter` folder into `C:\ProgramData\obs-studio\plugins\`, preserving `bin\64bit\droid-alter.dll` and `data\adb\adb.exe`. The old folder, DLL, and source identifiers are kept intentionally so existing OBS scenes and saved ADB paths continue working. Do not install a second copy under another folder.

The DLL contains the CamBridge Windows icon, and `data\cambridge.ico` is included for shortcuts. OBS uses its standard camera symbol in the source picker; it does not display a custom per-plugin logo there.

## 3. Connect over USB

1. Connect the unlocked phone directly to the PC with a **USB data cable**. A charging-only cable will not work.
2. Accept **Allow USB debugging?** on the phone. Only authorize a PC you trust. No IP address is needed.
3. In CamBridge, choose your camera and tap **Start USB camera**. Keep the app visible until the stream starts.
4. In the OBS source properties, keep **Connect camera** enabled. Leave USB serial blank when only one USB phone is attached. Click **Reconnect USB** if necessary.
5. The ADB path should point to the bundled `data\adb\adb.exe`. If using your own Android platform-tools, select that `adb.exe` instead.
6. Once video is visible, you may lock the phone. To stop, unlock and use the app button or tap **Stop camera** in the foreground notification.

If the stream stops after locking, set CamBridge's battery usage to **Unrestricted** in Android app settings, then start it again while unlocked. Vendor power management can vary. CamBridge does not start camera capture from a locked screen after a reboot or force-stop.

## 4. Record vertical video

1. In OBS → Settings → **Video**, set Base (Canvas) Resolution to **1080x1920**, Output (Scaled) Resolution to **1080x1920**, and Common FPS to **30**.
2. In the CamBridge source properties choose **Automatic** orientation. If your phone's orientation metadata differs, choose **Portrait · 90°** or **Portrait · 270°** until upright.
3. Right-click the source → Transform → **Fit to screen** (`Ctrl+F`). Use **Mirror horizontally** in source properties if desired.
4. Add **Audio Input Capture** for your PC microphone. In Settings → Output choose your recording folder and format. MKV is a useful recording choice; OBS can remux it afterward.
5. Click **Start Recording** in OBS. CamBridge's phone button starts the camera stream; recording is performed on the PC.

For landscape recording, use a 1920x1080 canvas and an appropriate landscape orientation. Keeping **Use native camera resolution** disabled gives OBS a stable Full HD source size across phone resolution changes.

## Camera controls while recording

| Panel | Controls |
| --- | --- |
| Camera | Lens, zoom slider/pinch, tap focus, focus mode/manual focus, torch, optical/video stabilization, preview grid |
| Exposure | Automatic exposure, exposure compensation, AE lock, manual ISO and shutter |
| Color | White balance preset and lock, noise reduction, edge enhancement |
| Stream | Resolution, frame rate, bitrate |

Controls are discovered from each camera's capabilities. An unavailable control may be disabled or omitted. Turn **Automatic exposure** off to set ISO and shutter. Shutter duration is limited by the current frame rate and sensor. Fixed-focus lenses do not offer manual focus. Some camera settings interact; stabilization and heavy processing can crop the image or add latency.

Lens, resolution, and FPS changes cause a brief interruption while the camera/encoder reopens. OBS keeps recording and may hold the last frame during the switch. Bitrate and supported camera controls apply live. The app's grid is a composition aid, not a burned-in overlay.

### Galaxy S24 Ultra lenses

Discovery on the test phone found seven Camera2 routes: rear auto, ultra wide (~14mm equivalent), main wide (~22mm), telephoto 3x (~61mm), telephoto 5x (~113mm), and two front routes (~25mm and ~30mm). Routes can share a physical sensor or represent a crop; seven routes do not mean seven physical cameras. The app selects physical cameras inside a logical camera session where Android requires it.

Samsung's stock camera may offer extra modes or processing unavailable to third-party Camera2 apps. CamBridge exposes what Android reports; it cannot unlock vendor-private cameras, guarantee every lens/resolution combination, or provide the stock camera's entire feature set.

## Latency and audio sync

USB still has camera exposure, sensor processing, H.264 encoding, transport, decoding, and display delay. CamBridge avoids a playback queue; **zero delay is not promised**. The earlier approximately one-second buffered delay was removed on the test setup. Decoder log timings are only decode/delivery work, not complete camera-to-screen latency.

Start with 1080p/30, 16–24 Mbps, automatic exposure, and fast processing. If needed, try a lower resolution, shorter manual shutter with enough light, and disable stabilization/high quality processing. Avoid long exposures in dim light. Higher FPS can help on supported hardware, but is not guaranteed to reduce delay.

For lip sync, record a visible hand clap with your microphone, review the result, then use OBS → Advanced Audio Properties → **Sync Offset** to delay the microphone until it matches the video. Measure your own setup; a fixed guessed offset is unreliable. Preview refresh and the recording can differ.

## Troubleshooting

| Symptom | Try this |
| --- | --- |
| No USB device / no video | Unlock the phone, enable USB debugging, accept the authorization prompt, try a known data cable and another direct USB port. File transfer working alone does not prove ADB is enabled. |
| Samsung shows file transfer but no ADB | Toggle USB debugging off/on and reconnect. If Windows still lacks the ADB interface, install the official [Samsung USB driver](https://developer.samsung.com/android-usb-driver). |
| `unauthorized` | Unlock the phone and accept the debugging prompt. If absent, revoke USB debugging authorizations in Developer options and reconnect. |
| Multiple devices | Run `adb devices -l` and enter the intended USB device serial in OBS source properties. Each OBS source needs a unique PC USB port number. |
| Camera source missing | Close OBS, check the plugin directory layout, avoid duplicate DLLs, and confirm Windows x64 OBS 33.0.3. Open OBS Help → Log Files → View Current Log and search for `droid-alter`. |
| Missing FFmpeg DLL / plugin won't load | Use the supported OBS version. Do not download random individual DLLs into the OBS folder. |
| Connected but black | Start the phone camera, allow Camera permission, close other camera apps, try rear auto at 1080p/30, then click Reconnect USB. |
| Lens switch fails | Try rear auto and a lower resolution/FPS. Not every physical camera supports every stream combination. Include the route and setting in an issue. |
| Wrong rotation | Try the explicit 90° or 270° portrait option and fit the source to the canvas again. |
| Stops on lock | Start while unlocked, allow the foreground notification, and remove battery restrictions. |
| Old source name in a scene | OBS scene instance names are user labels; rename it in Sources. The source type is now CamBridge USB Camera. |

ADB diagnostics from the extracted plugin folder:

```powershell
.\droid-alter\data\adb\adb.exe devices -l
.\droid-alter\data\adb\adb.exe forward --list
```

For an issue, include phone model/Android version, OBS version, APK/plugin version, selected camera route/resolution/FPS, and relevant OBS logs. Remove device serials and personal paths before posting.

## Uninstall

Stop the stream, close OBS, remove `C:\ProgramData\obs-studio\plugins\droid-alter`, and uninstall CamBridge from Android. Existing OBS scenes will show the missing source until you remove it. Revoke this PC's USB debugging authorization if you no longer need it.

## Source and license

`app/` contains the Android Camera2/MediaCodec app. `obs-plugin/` contains the native OBS source and decoder. `tests/` contains transport and libobs integration checks. `assets/brand/` contains the logo, Android-source mark, PNG sizes, and multi-resolution Windows ICO.

CamBridge source is licensed under **GPL-2.0-or-later**; see [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md). The project is independent of DroidCam, Samsung, Google, and OBS. Third-party names identify compatibility only. No proprietary DroidCam code or assets are included.