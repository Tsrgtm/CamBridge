param([switch]$AndroidOnly, [switch]$PluginOnly, [switch]$DesktopOnly, [switch]$SkipDesktop, [string]$ObsDirectory = 'C:\Program Files\obs-studio')
$ErrorActionPreference = 'Stop'
$project = $PSScriptRoot
if($DesktopOnly){& "$project\desktop\Build-Desktop.ps1"; return}
$javaDirectory = Get-ChildItem -LiteralPath "$project\tools\jdk" -Directory | Select-Object -First 1
$llvmDirectory = Get-ChildItem -LiteralPath "$project\tools\llvm" -Directory | Select-Object -First 1
$env:JAVA_HOME = $javaDirectory.FullName
$env:ANDROID_HOME = "$project\tools\android-sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
New-Item -ItemType Directory -Force -Path "$project\dist" | Out-Null
if (!$PluginOnly) {
    $gradle = Get-ChildItem -LiteralPath "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.14-all" -Recurse -Filter gradle.bat -ErrorAction SilentlyContinue | Select-Object -First 1
    $gradlePath = if ($gradle) { $gradle.FullName } else { Join-Path $project 'gradlew.bat' }
    if (!(Test-Path -LiteralPath $gradlePath)) { throw 'The Gradle wrapper is missing. Open the project in Android Studio to build it.' }
    & $gradlePath -p $project --no-daemon --console=plain assembleDebug
    if ($LASTEXITCODE -ne 0) { throw 'Android build failed.' }
    Copy-Item -LiteralPath "$project\app\build\outputs\apk\debug\app-debug.apk" -Destination "$project\dist\CamBridge-0.5.0.apk" -Force
}
if (!$AndroidOnly) {
    $llvm = "$($llvmDirectory.FullName)\bin"
    $obsSource = "$project\tools\obs-source\obs-studio-33.0.3\libobs"
    $build = "$project\obs-plugin\build"
    $ffmpeg = "$project\tools\ffmpeg-source\FFmpeg-n8.0"
    $output = "$project\dist\obs-plugin\droid-alter"
    New-Item -ItemType Directory -Force -Path $build,"$output\bin\64bit","$output\data\adb" | Out-Null
    $exports = & "$llvm\llvm-readobj.exe" --coff-exports "$ObsDirectory\bin\64bit\obs.dll"
    $names = $exports | ForEach-Object { if ($_ -match '^\s+Name: (\S+)') { $Matches[1] } }
    @('LIBRARY obs.dll', 'EXPORTS') + $names | Set-Content -LiteralPath "$build\obs.def" -Encoding ascii
    & "$llvm\llvm-dlltool.exe" -m i386:x86-64 -d "$build\obs.def" -l "$build\obs.lib"
    if ($LASTEXITCODE -ne 0) { throw 'OBS import library generation failed.' }
    foreach ($library in @('avcodec-62','avutil-60')) {
        $symbols = & "$llvm\llvm-readobj.exe" --coff-exports "$ObsDirectory\bin\64bit\$library.dll"
        $names = $symbols | ForEach-Object { if ($_ -match '^\s+Name: (\S+)') { $Matches[1] } }
        @("LIBRARY $library.dll", 'EXPORTS') + $names | Set-Content -LiteralPath "$build\$library.def" -Encoding ascii
        & "$llvm\llvm-dlltool.exe" -m i386:x86-64 -d "$build\$library.def" -l "$build\$library.lib"
        if ($LASTEXITCODE -ne 0) { throw "Import library generation failed: $library" }
    }
    @('#pragma once','#define AV_HAVE_BIGENDIAN 0','#define AV_HAVE_FAST_UNALIGNED 1') | Set-Content -LiteralPath "$ffmpeg\libavutil\avconfig.h" -Encoding ascii
    & "$project\scripts\Build-IconResource.ps1" -IconPath "$project\assets\brand\cambridge.ico" -OutputPath "$build\cambridge.res"
    & "$llvm\clang++.exe" --target=x86_64-w64-mingw32 -std=c++17 -O2 -Wall -Wextra -shared -static `
        "-I$obsSource" "-I$project\obs-plugin" "-I$ffmpeg" `
        "$project\obs-plugin\droid-alter.cpp" "$project\obs-plugin\direct-decoder.cpp" "$project\obs-plugin\exports.def" "$build\cambridge.res" "$build\obs.lib" "$build\avcodec-62.lib" "$build\avutil-60.lib" -lws2_32 `
        -o "$output\bin\64bit\droid-alter.dll"
    if ($LASTEXITCODE -ne 0) { throw 'OBS plugin compilation failed.' }
    foreach ($file in @('adb.exe','AdbWinApi.dll','AdbWinUsbApi.dll')) {
        Copy-Item -LiteralPath "$env:ANDROID_HOME\platform-tools\$file" -Destination "$output\data\adb\$file" -Force
    }
    Copy-Item -LiteralPath "$env:ANDROID_HOME\platform-tools\NOTICE.txt" -Destination "$output\data\adb\NOTICE.txt" -Force
    if (Test-Path -LiteralPath "$project\LICENSE") { Copy-Item -LiteralPath "$project\LICENSE" -Destination "$output\data\LICENSE" -Force }
    Copy-Item -LiteralPath "$project\assets\brand\cambridge.ico" -Destination "$output\data\cambridge.ico" -Force
    foreach ($document in @('README.md','BUILDING.md','NOTICE.md','LICENSE')) {
        Copy-Item -LiteralPath "$project\$document" -Destination "$project\dist\obs-plugin\$document" -Force
    }
    Copy-Item -LiteralPath "$project\Install-OBS-Plugin.ps1" -Destination "$project\dist\obs-plugin\Install-OBS-Plugin.ps1" -Force
    Copy-Item -LiteralPath "$project\Install-Packaged-Plugin.cmd" -Destination "$project\dist\obs-plugin\Install-CamBridge.cmd" -Force
    New-Item -ItemType Directory -Force -Path "$project\dist\obs-plugin\assets\brand","$project\dist\obs-plugin\LICENSES" | Out-Null
    Copy-Item -LiteralPath "$project\assets\brand\cambridge-banner.png" -Destination "$project\dist\obs-plugin\assets\brand\cambridge-banner.png" -Force
    Copy-Item -Path "$project\LICENSES\*" -Destination "$project\dist\obs-plugin\LICENSES" -Force
    Compress-Archive -Path "$project\dist\obs-plugin\droid-alter","$project\dist\obs-plugin\README.md","$project\dist\obs-plugin\BUILDING.md","$project\dist\obs-plugin\assets","$project\dist\obs-plugin\LICENSES","$project\dist\obs-plugin\NOTICE.md","$project\dist\obs-plugin\LICENSE","$project\dist\obs-plugin\Install-OBS-Plugin.ps1","$project\dist\obs-plugin\Install-CamBridge.cmd" -DestinationPath "$project\dist\CamBridge-OBS-Windows-x64-0.5.0.zip" -Force
}
if(!$AndroidOnly -and !$PluginOnly -and !$SkipDesktop){& "$project\desktop\Build-Desktop.ps1"}
Write-Host "Build outputs: $project\dist"
