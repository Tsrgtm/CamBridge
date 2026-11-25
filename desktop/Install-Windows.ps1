param([string]$Destination="$env:LOCALAPPDATA\Programs\CamBridge\0.5.0")
$ErrorActionPreference='Stop'
if(Get-Process CamBridge -ErrorAction SilentlyContinue){throw 'Close CamBridge before installing an update.'}
New-Item -ItemType Directory -Force $Destination | Out-Null
if([IO.Path]::GetFullPath($PSScriptRoot).TrimEnd('\') -ne [IO.Path]::GetFullPath($Destination).TrimEnd('\')){Copy-Item "$PSScriptRoot\*" $Destination -Recurse -Force}
$registration = Start-Process -FilePath "$env:WINDIR\System32\regsvr32.exe" -ArgumentList @('/s',('"' + "$Destination\CamBridgeCamera64.dll" + '"')) -Wait -PassThru -WindowStyle Hidden
if($registration.ExitCode -ne 0){throw '64-bit camera registration failed. Close camera apps and try again.'}
$registration = Start-Process -FilePath "$env:WINDIR\SysWOW64\regsvr32.exe" -ArgumentList @('/s',('"' + "$Destination\CamBridgeCamera32.dll" + '"')) -Wait -PassThru -WindowStyle Hidden
if($registration.ExitCode -ne 0){throw '32-bit camera registration failed.'}
$shell=New-Object -ComObject WScript.Shell
$shortcut=$shell.CreateShortcut("$env:APPDATA\Microsoft\Windows\Start Menu\Programs\CamBridge.lnk")
$shortcut.TargetPath="$Destination\CamBridge.exe";$shortcut.WorkingDirectory=$Destination;$shortcut.IconLocation="$Destination\cambridge.ico";$shortcut.Save()
Write-Host "Installed CamBridge Camera. Open CamBridge from Start, connect your phone, then select CamBridge Camera in your app."
