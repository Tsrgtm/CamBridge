param([switch]$TransportOnly,[string]$ObsDirectory='C:\Program Files\obs-studio')
$ErrorActionPreference='Stop'
$project=$PSScriptRoot
$jdk=(Get-ChildItem "$project\tools\jdk" -Directory | Select-Object -First 1).FullName
$llvm=Join-Path (Get-ChildItem "$project\tools\llvm" -Directory | Select-Object -First 1).FullName 'bin'
$build="$project\tests\build";$classes="$build\classes"
New-Item -ItemType Directory -Force $classes | Out-Null
$java="$project\app\src\main\java\app\droidalter\camera"
& "$jdk\bin\javac.exe" -d $classes "$java\AnnexB.java" "$java\UsbStreamServer.java" "$java\RemoteServer.java" "$java\Nv12Packer.java" "$project\tests\TransportTest.java" "$project\tests\RemoteServerTest.java" "$project\tests\Nv12PackerTest.java" "$project\tests\RawTransportTest.java"
if($LASTEXITCODE -ne 0){throw 'Java tests did not compile'}
foreach($test in @('TransportTest','RemoteServerTest','Nv12PackerTest','RawTransportTest')){
    & "$jdk\bin\java.exe" -cp $classes "app.droidalter.camera.$test"
    if($LASTEXITCODE -ne 0){throw "Java regression failed: $test"}
}
& "$llvm\clang++.exe" --target=x86_64-w64-mingw32 -std=c++17 -O2 -static -Wno-register -Wno-deprecated "-I$project\desktop\vendor\obs" "$project\tests\latest-frame-test.cpp" "$project\desktop\native\latest-frame.cpp" "$project\desktop\vendor\obs\tiny-nv12-scale.c" -o "$build\latest-frame-test.exe"
if($LASTEXITCODE -ne 0){throw 'Latest-frame tests did not compile'}
& "$build\latest-frame-test.exe"
if($LASTEXITCODE -ne 0){throw 'Latest-frame regression failed'}
if($TransportOnly){return}
$env:PATH="$ObsDirectory\bin\64bit;$env:PATH"
& "$llvm\clang++.exe" --target=x86_64-w64-mingw32 -std=c++17 -O2 -static "-I$project\tools\obs-source\obs-studio-33.0.3\libobs" "-I$project\obs-plugin" "$project\tests\obs-plugin-test.cpp" "$project\obs-plugin\build\obs.lib" -o "$build\obs-plugin-test.exe"
if($LASTEXITCODE -ne 0){throw 'OBS tests did not compile'}
& "$build\obs-plugin-test.exe" "$project\dist\obs-plugin\droid-alter\bin\64bit\droid-alter.dll" "$project\dist\obs-plugin\droid-alter\data"
if($LASTEXITCODE -ne 0){throw 'OBS source regression failed'}
& "$llvm\clang++.exe" --target=x86_64-w64-mingw32 -std=c++17 -O2 -static "-I$project\tools\ffmpeg-source\FFmpeg-n8.0" "$project\tests\desktop-camera-test.cpp" "$project\obs-plugin\build\avcodec-62.lib" "$project\obs-plugin\build\avutil-60.lib" -lstrmiids -luuid -lole32 -o "$build\desktop-camera-test.exe"
if($LASTEXITCODE -ne 0){throw 'Desktop video tests did not compile'}
& "$build\desktop-camera-test.exe" "$project\dist\CamBridge-Windows\CamBridgeCore.dll" "$project\dist\CamBridge-Windows\CamBridgeCamera64.dll"
if($LASTEXITCODE -ne 0){throw 'Standalone camera regression failed'}
