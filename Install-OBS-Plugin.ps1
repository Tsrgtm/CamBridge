param([string]$ObsPluginDirectory = "$env:ProgramData\obs-studio\plugins")
$ErrorActionPreference = 'Stop'
$source = Join-Path $PSScriptRoot 'dist\obs-plugin\droid-alter'
if (!(Test-Path -LiteralPath $source)) { $source = Join-Path $PSScriptRoot 'droid-alter' }
if (!(Test-Path -LiteralPath "$source\bin\64bit\droid-alter.dll")) { throw 'Build the OBS plugin first using Build.ps1.' }
if (Get-Process obs64 -ErrorAction SilentlyContinue) { throw 'Close OBS Studio before installing the plugin, then run this script again.' }
$destination = Join-Path $ObsPluginDirectory 'droid-alter'
New-Item -ItemType Directory -Force -Path $destination | Out-Null
Copy-Item -Path "$source\*" -Destination $destination -Recurse -Force
Write-Host "Installed to $destination. Open OBS and add the CamBridge USB Camera source."
