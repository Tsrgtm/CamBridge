# Build CamBridge

## Windows prerequisites

- Windows x64, PowerShell 5.1 or newer, Git, and internet access for dependencies.
- OBS Studio **33.0.3 x64** installed in `C:\Program Files\obs-studio` (or pass `-ObsDirectory`).
- Several GB of free disk space for toolchains and extracted sources.

```powershell
git clone https://github.com/Tsrgtm/CamBridge.git
cd CamBridge
powershell -ExecutionPolicy Bypass -File .\Bootstrap.ps1 -AcceptAndroidLicenses
powershell -ExecutionPolicy Bypass -File .\Build.ps1
powershell -ExecutionPolicy Bypass -File .\Test.ps1
```

Read and agree to the Android SDK licenses before using `-AcceptAndroidLicenses`. Bootstrap downloads into ignored `tools/`, verifies pinned archive SHA-256 hashes, and installs Android SDK 36, build-tools 36.0.0, and platform-tools. It does not install OBS. If a download's hash changes, investigate upstream rather than disabling verification.

Pinned toolchain: Temurin JDK 21.0.13.1+2, Gradle wrapper 8.14, Android Gradle Plugin 8.11.1, LLVM-MinGW 20261117 UCRT, OBS headers 33.0.3, FFmpeg headers n8.0. Platform-tools is obtained from Android's SDK manager and carries its own upstream notices. Runtime FFmpeg DLLs come from your OBS installation, not from this source tree.

`Build.ps1 -AndroidOnly` builds just the APK. `-PluginOnly` builds just the native plugin (still requires the bootstrapped tool paths). Use `-ObsDirectory 'D:\OBS'` for another OBS installation. The build script generates import libraries from that OBS installation; incompatible OBS/FFmpeg major versions are not supported by this preview.

## Outputs

| File | Purpose |
| --- | --- |
| `dist/CamBridge-0.4.0.apk` | Development-signed Android preview |
| `dist/CamBridge-OBS-Windows-x64-0.4.0.zip` | DLL, bundled ADB, installer, icon, licenses, and guide |
| `dist/obs-plugin/droid-alter/` | Uncompressed plugin for local installation |

```powershell
.\Install-Android-App.ps1
# Close OBS first. Administrator rights may be required for ProgramData.
.\Install-OBS-Plugin.ps1
```

`Install-Android-App.ps1 -Serial DEVICE_SERIAL` chooses a specific attached phone. It requires authorized USB debugging. These install commands change your phone/OBS installation; build and test do not.

## Signing

The current build deliberately uses `assembleDebug`. Android creates a local debug signing key under your user profile; no private signing key is in Git. Builds from different machines generally cannot update each other's APKs. To distribute production builds, configure a protected release keystore outside the repository, add a release signing configuration, and build `assembleRelease`. Never commit signing keys or passwords. The published 0.4.0 preview is not a Play Store release.

## Tests

`Test.ps1` compiles and runs 15 standalone Java protocol/transport assertions, then builds a native integration harness against installed libobs. It verifies DLL load, source registration/construction, portrait/landscape dimensions, and connection controls. Run Build before Test so the import library and DLL exist. Null-graphics cleanup diagnostics can appear in the headless harness.

Device verification is separate: check each supported camera route, controls during an OBS recording, reconnect, screen lock, portrait/landscape, and a recorded clap for A/V sync. Camera behavior depends on hardware; passing transport tests does not validate every sensor or measure end-to-end latency.

## Architecture and compatibility

The phone exposes a loopback-only TCP server on port 5889. ADB forwards a PC loopback port to it. MediaCodec emits H.264 AVC Annex B packets. The plugin uses a direct FFmpeg decoder and unbuffered OBS async video, with rotation/mirroring during rendering. Audio is handled separately by OBS.

The 20-byte big-endian packet header is magic `DALT` (uint32), payload length (uint32), timestamp in microseconds (int64), and flags (uint32). Flags: keyframe 1, codec configuration 2, known realtime sensor clock 4. A configuration packet's timestamp carries sensor orientation (0/90/180/270). The `DAPG` clock probe returns Android elapsed realtime in nanoseconds. Protocol and Android package identifiers retain legacy names to preserve interoperability.

Do not rename `app.droidalter.camera`, source ID `droid_alter_usb_camera`, internal decoder ID `droid_alter_direct_decoder`, or the installed `droid-alter` module directory without an upgrade migration. Visible labels, launcher icons, and release artifacts use CamBridge.

## Brand assets

`assets/brand/cambridge-mark.png` is the transparent master. `cambridge-banner.png` and `cambridge-logo.svg` are repository branding. `cambridge.ico` contains 16, 24, 32, 48, 64, 128, and 256px PNG entries; the plugin build embeds them as native Win32 icon resources using `scripts/Build-IconResource.ps1`. Android uses the matching adaptive launcher icon and a monochrome camera notification icon. Small PNG exports are also included.

The master was created with OpenAI image generation and reviewed for this project; it is not an existing third-party camera app's logo. Brand artwork is included under the repository license to the extent rights apply. The SVG wordmark uses system font fallbacks and embeds its symbol, so no font download is required.