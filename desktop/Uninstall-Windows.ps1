param([string]$InstallDirectory=$PSScriptRoot)
$ErrorActionPreference='Stop'
if(Get-Process CamBridge -ErrorAction SilentlyContinue){throw 'Close CamBridge and apps using its camera before uninstalling.'}
$result=Start-Process -FilePath "$env:WINDIR\System32\regsvr32.exe" -ArgumentList @('/s','/u',('"'+"$InstallDirectory\CamBridgeCamera64.dll"+'"')) -Wait -PassThru -WindowStyle Hidden
if($result.ExitCode -ne 0){throw '64-bit camera unregistration failed'}
$result=Start-Process -FilePath "$env:WINDIR\SysWOW64\regsvr32.exe" -ArgumentList @('/s','/u',('"'+"$InstallDirectory\CamBridgeCamera32.dll"+'"')) -Wait -PassThru -WindowStyle Hidden
if($result.ExitCode -ne 0){throw '32-bit camera unregistration failed'}
$shortcut="$env:APPDATA\Microsoft\Windows\Start Menu\Programs\CamBridge.lnk"
if(Test-Path -LiteralPath $shortcut){Remove-Item -LiteralPath $shortcut}
Write-Host "Camera unregistered. You can now remove the installation folder: $InstallDirectory"
