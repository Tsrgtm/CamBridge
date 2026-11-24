param([switch]$AcceptAndroidLicenses)
$ErrorActionPreference='Stop'
$ProgressPreference='SilentlyContinue'
$project=$PSScriptRoot
$archives=@(
    @{Name='jdk';Url='https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13.1%2B2/OpenJDK21U-jdk_x64_windows_hotspot_21.0.13.1_2.zip';Hash='BBEBEA1469996B276683C9299C227B294F85C847039C82633218186A4AD4FBF2'},
    @{Name='llvm';Url='https://github.com/mstorsjo/llvm-mingw/releases/download/20261117/llvm-mingw-20261117-ucrt-x86_64.zip';Hash='4D002E330A0F703625AA5CCBC37E96F8F824413E163BFC7336774AABD2C3A412'},
    @{Name='android-tools';Url='https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip';Hash='98B565CB657B012DAE6794CEFC0F66AE1EFB4690C699B78A614B4A6A3505B003'},
    @{Name='obs-source';Url='https://github.com/obsproject/obs-studio/archive/refs/tags/33.0.3.zip';Hash='85A9D6D3DCDB6C972A445E8F0B3C03E1DB9D914E9666C3A94BF0A15DF4CADCE7'},
    @{Name='ffmpeg-source';Url='https://github.com/FFmpeg/FFmpeg/archive/refs/tags/n8.0.zip';Hash='775DB720F4821967C360B7CECB277540A9EB8D72A9AF1CD7C1D2849C4C8C881C'}
)
New-Item -ItemType Directory -Force "$project\tools" | Out-Null
foreach($archive in $archives){
    $zip="$project\tools\$($archive.Name).zip"
    if(!(Test-Path -LiteralPath $zip)){
        Write-Host "Downloading $($archive.Name)..."
        Invoke-WebRequest -UseBasicParsing -Uri $archive.Url -OutFile $zip
    }
    if((Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash -ne $archive.Hash){throw "Checksum mismatch: $zip. Do not use the archive; investigate its upstream version."}
    $folder="$project\tools\$($archive.Name)"
    if(!(Test-Path -LiteralPath $folder)){Expand-Archive -LiteralPath $zip -DestinationPath $folder}
}
$env:JAVA_HOME=(Get-ChildItem "$project\tools\jdk" -Directory | Select-Object -First 1).FullName
$sdk="$project\tools\android-sdk"
$sdkManager="$sdk\cmdline-tools\latest\bin\sdkmanager.bat"
if(!(Test-Path -LiteralPath $sdkManager)){
    New-Item -ItemType Directory -Force "$sdk\cmdline-tools\latest" | Out-Null
    Copy-Item -Path "$project\tools\android-tools\cmdline-tools\*" -Destination "$sdk\cmdline-tools\latest" -Recurse -Force
}
if(!(Test-Path -LiteralPath "$sdk\licenses\android-sdk-license")){
    if(!$AcceptAndroidLicenses){throw 'Read the Android SDK licenses, then rerun with -AcceptAndroidLicenses to accept them and install the SDK.'}
    1..100 | ForEach-Object {'y'} | & $sdkManager "--sdk_root=$sdk" --licenses
    if($LASTEXITCODE -ne 0){throw 'Android SDK license acceptance failed.'}
}
& $sdkManager "--sdk_root=$sdk" 'platform-tools' 'platforms;android-36' 'build-tools;36.0.0'
if($LASTEXITCODE -ne 0){throw 'Android SDK installation failed.'}
Write-Host 'Toolchains ready. Install OBS Studio 33.0.3 (Windows x64), then run .\Build.ps1.'