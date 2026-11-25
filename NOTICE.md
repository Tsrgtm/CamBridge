# Notices

CamBridge source: Copyright (c) 2026 CamBridge contributors. GPL-2.0-or-later; see LICENSE. Distributed without warranty. This is an independent project, with no endorsement by OBS Studio, Android, Samsung, Microsoft or DroidCam.

The standalone Windows camera contains adapted OBS Studio 33.0.3 virtual-camera, scaler and utility sources (GPL-2.0-or-later, plus retained permissive utility notices), selected libdshowcapture source (LGPL-2.1-or-later), and libyuv source (BSD plus patent grant). Complete corresponding sources used by this build, original headers, upstream revisions and modification notes are in `desktop/vendor`. See `LICENSES/LGPL-2.1.txt`, `LICENSES/libyuv-BSD.txt`, and libyuv's PATENTS/AUTHORS. The statically linked LGPL portions can be modified/relinked by rebuilding the included GPL source.

Windows binaries include LLVM/MinGW runtime portions. Their Apache-2.0-with-LLVM-exception, MinGW-w64, winpthreads and other runtime notices are retained under `LICENSES`. Windows system libraries are supplied by Windows. The companion uses the installed Media Foundation decoder and does not redistribute Microsoft's media components.

Windows packages bundle unmodified Android platform-tools files (`adb.exe`, `AdbWinApi.dll`, `AdbWinUsbApi.dll`); retain `adb/NOTICE.txt` (or `droid-alter/data/adb/NOTICE.txt` in the OBS package). The optional OBS plugin dynamically uses the OBS and FFmpeg libraries provided by OBS. Toolchain downloads are excluded from Git.

The Gradle wrapper is from Gradle 8.14 under Apache-2.0 and retains upstream headers. See https://github.com/gradle/gradle/blob/v8.14.0/LICENSE . GitHub Actions are pinned to upstream commit IDs in the workflow.

The CamBridge logo master was generated for this project with OpenAI image generation; no third-party camera app logo was copied. Brand exports accompany the source under the repository license to the extent rights apply. System font fallbacks require no font redistribution.
