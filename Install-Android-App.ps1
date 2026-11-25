param([string]$Serial = '')
$ErrorActionPreference = 'Stop'
$adb = Join-Path $PSScriptRoot 'tools\android-sdk\platform-tools\adb.exe'
$apk = Join-Path $PSScriptRoot 'dist\CamBridge-0.5.0.apk'
if (!(Test-Path -LiteralPath $apk)) { throw 'Build the APK first using Build.ps1.' }
$arguments = @(if ($Serial) { '-s'; $Serial } else { '-d' })
& $adb @arguments install -r $apk
if ($LASTEXITCODE -ne 0) { throw 'Installation failed. Check the USB connection and authorize this computer on the phone.' }
Write-Host 'CamBridge installed. Open it on the phone and allow camera access.'
