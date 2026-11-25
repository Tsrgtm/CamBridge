$ErrorActionPreference='Stop'
$ProgressPreference='SilentlyContinue'
$project=Split-Path $PSScriptRoot -Parent
$zip="$project\tools\obs-runtime.zip"
$url='https://github.com/obsproject/obs-studio/releases/download/33.0.3/OBS-Studio-33.0.3-Windows-x64.zip'
if(!(Test-Path -LiteralPath $zip)){Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $zip}
if((Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash -ne '18A68C325ADBDA6F6082D5281021A8D1E35A6432842800618E8690B71925C107'){throw 'OBS runtime checksum mismatch'}
if(!(Test-Path -LiteralPath "$project\tools\obs-runtime\bin\64bit\obs.dll")){Expand-Archive -LiteralPath $zip -DestinationPath "$project\tools\obs-runtime" -Force}
