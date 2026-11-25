param([switch]$NativeOnly)
$ErrorActionPreference='Stop'
$project=Split-Path $PSScriptRoot -Parent
$llvm=Join-Path (Get-ChildItem "$project\tools\llvm" -Directory | Select-Object -First 1).FullName 'bin'
$native="$PSScriptRoot\native"
$vendor="$PSScriptRoot\vendor"
$output="$project\dist\CamBridge-Windows"
$build="$PSScriptRoot\build"
New-Item -ItemType Directory -Force $output,$build | Out-Null
& "$project\scripts\Build-IconResource.ps1" -IconPath "$project\assets\brand\cambridge.ico" -OutputPath "$build\cambridge.res"
$yuv="$vendor\libyuv"
$yuvBuild="$build\libyuv"
New-Item -ItemType Directory -Force $yuvBuild | Out-Null
$newestHeader=(Get-ChildItem "$yuv\include" -Recurse -File | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1).LastWriteTimeUtc
$yuvObjects=@()
foreach($source in (Get-ChildItem "$yuv\source" -Filter '*.cc')){
    $object=Join-Path $yuvBuild ($source.BaseName+'.obj');$yuvObjects+=$object
    if(!(Test-Path -LiteralPath $object) -or (Get-Item -LiteralPath $object).LastWriteTimeUtc -lt $source.LastWriteTimeUtc -or (Get-Item -LiteralPath $object).LastWriteTimeUtc -lt $newestHeader){
        & "$llvm\clang++.exe" --target=x86_64-w64-mingw32 -std=c++17 -O3 -DNDEBUG -DLIBYUV_DISABLE_JPEG "-I$yuv\include" -c $source.FullName -o $object
        if($LASTEXITCODE -ne 0){throw "Optimized YUV compilation failed: $($source.Name)"}
    }
}
& "$llvm\llvm-ar.exe" rcs "$yuvBuild\yuv.lib" @yuvObjects
if($LASTEXITCODE -ne 0){throw 'Optimized YUV archive failed'}
$shared=@("$native\latest-frame.cpp","$vendor\obs\tiny-nv12-scale.c")
$flags=@('-std=c++17','-O2','-fms-extensions','-include',"$native\mingw-compat.h",'-DUNICODE','-D_UNICODE','-D_M_X64','-static',"-I$vendor\obs","-I$vendor\libdshowcapture",'-Wno-inconsistent-missing-override','-Wno-int-to-pointer-cast','-Wno-deprecated','-Wno-register','-Wno-ignored-attributes','-Wno-deprecated-declarations')
& "$llvm\clang++.exe" --target=x86_64-w64-mingw32 @flags -shared "-I$yuv\include" "$native\receiver.cpp" @shared "$yuvBuild\yuv.lib" "$build\cambridge.res" -lmfplat -lmfuuid -lwmcodecdspuuid -ld3d11 -ldxgi -ldxguid -lstrmiids -luuid -lole32 -loleaut32 -o "$output\CamBridgeCore.dll"
if($LASTEXITCODE -ne 0){throw 'Windows receiver build failed'}
$sources=@('output-filter.cpp','dshow-base.cpp','dshow-enum.cpp','dshow-formats.cpp','dshow-media-type.cpp','log.cpp') | ForEach-Object {"$vendor\libdshowcapture\source\$_"}
foreach($arch in @(@('x86_64-w64-mingw32','64'),@('i686-w64-mingw32','32'))){
    $archFlags=$flags | Where-Object {$_ -ne '-D_M_X64'}
    $archFlags+= $(if($arch[1] -eq '64'){'-D_M_X64'}else{'-D_M_IX86'})
    & "$llvm\clang++.exe" "--target=$($arch[0])" @archFlags -shared -DVIRTUALCAM_AVAILABLE "-I$vendor\obs\include" "$vendor\obs\virtualcam-filter.cpp" "$vendor\obs\virtualcam-module.cpp" "$vendor\obs\sleepto.c" "$native\placeholder.cpp" "$native\guids.cpp" @sources @shared "$native\virtualcam.def" "$build\cambridge.res" -lstrmiids -lole32 -loleaut32 -lwinmm -luuid -lsetupapi -lksuser -lgdi32 -ladvapi32 -lshell32 -o "$output\CamBridgeCamera$($arch[1]).dll"
    if($LASTEXITCODE -ne 0){throw "Virtual camera $($arch[1])-bit build failed"}
}
if(!$NativeOnly){
    $csc="$env:WINDIR\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
    & $csc /nologo /target:winexe /platform:x64 /optimize+ "/win32icon:$project\assets\brand\cambridge.ico" /reference:System.Windows.Forms.dll /reference:System.Drawing.dll /reference:System.Web.Extensions.dll /reference:System.Web.dll "/out:$output\CamBridge.exe" "$PSScriptRoot\CamBridge.cs"
    if($LASTEXITCODE -ne 0){throw 'Windows companion build failed'}
}
New-Item -ItemType Directory -Force "$output\adb" | Out-Null
foreach($file in @('adb.exe','AdbWinApi.dll','AdbWinUsbApi.dll','NOTICE.txt')){Copy-Item "$project\tools\android-sdk\platform-tools\$file" "$output\adb\$file" -Force}
Copy-Item "$PSScriptRoot\Install-Windows.ps1","$PSScriptRoot\Install-CamBridge.cmd","$PSScriptRoot\Uninstall-Windows.ps1" $output -Force
Copy-Item "$project\README.md","$project\BUILDING.md","$project\NOTICE.md","$project\LICENSE" $output -Force
New-Item -ItemType Directory -Force "$output\LICENSES" | Out-Null
Copy-Item "$project\LICENSES\*" "$output\LICENSES" -Recurse -Force
Copy-Item "$project\assets\brand\cambridge.ico" $output -Force
New-Item -ItemType Directory -Force "$output\assets\brand" | Out-Null
Copy-Item "$project\assets\brand\cambridge-banner.png" "$output\assets\brand" -Force
Compress-Archive -Path "$output\*" -DestinationPath "$project\dist\CamBridge-Windows-x64-0.5.0.zip" -Force
Write-Host "Windows companion: $output"
