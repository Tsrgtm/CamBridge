$ErrorActionPreference = 'Stop'
$project = $PSScriptRoot
$jdk = (Get-ChildItem -LiteralPath "$project\tools\jdk" -Directory | Select-Object -First 1).FullName
$llvm = (Get-ChildItem -LiteralPath "$project\tools\llvm" -Directory | Select-Object -First 1).FullName
$classes = "$project\tests\build\classes"
New-Item -ItemType Directory -Force -Path $classes | Out-Null
& "$jdk\bin\javac.exe" -d $classes "$project\app\src\main\java\app\droidalter\camera\AnnexB.java" "$project\app\src\main\java\app\droidalter\camera\UsbStreamServer.java" "$project\tests\TransportTest.java"
if ($LASTEXITCODE -ne 0) { throw 'Protocol test compilation failed.' }
& "$jdk\bin\java.exe" -cp $classes app.droidalter.camera.TransportTest
if ($LASTEXITCODE -ne 0) { throw 'Protocol tests failed.' }
& "$llvm\bin\clang++.exe" --target=x86_64-w64-mingw32 -std=c++17 -O2 -static "-I$project\tools\obs-source\obs-studio-33.0.3\libobs" "-I$project\obs-plugin" "$project\tests\obs-plugin-test.cpp" "$project\obs-plugin\build\obs.lib" -o "$project\tests\build\obs-plugin-test.exe"
if ($LASTEXITCODE -ne 0) { throw 'OBS integration test compilation failed.' }
$env:PATH = "C:\Program Files\obs-studio\bin\64bit;$env:PATH"
& "$project\tests\build\obs-plugin-test.exe" "$project\dist\obs-plugin\droid-alter\bin\64bit\droid-alter.dll" "$project\dist\obs-plugin\droid-alter\data"
if ($LASTEXITCODE -ne 0) { throw 'OBS integration checks failed.' }
