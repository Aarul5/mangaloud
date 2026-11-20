# Makes the Manga Loud relay start when you sign in to Windows, and come back if it stops.
#
#   powershell -ExecutionPolicy Bypass -File relay\install-autostart.ps1
#
# It registers a scheduled task for your user that runs run-relay.ps1 hidden. run-relay.ps1 restarts
# the relay whenever it exits. The task also re-checks every few minutes, so if the keep-alive loop
# itself is killed, it comes back too. Run it again any time to update the task.
# uninstall-autostart.ps1 removes it.

param([int]$CheckEveryMinutes = 5)

$ErrorActionPreference = 'Stop'
$taskName = 'MangaLoudRelay'
$here = $PSScriptRoot
$wrapper = Join-Path $here 'run-relay.ps1'

# Full paths, so the task does not depend on PATH being ready at sign-in.
$node = (Get-Command node).Source
$claude = (Get-Command claude).Source
$user = "$env:USERDOMAIN\$env:USERNAME"

$arguments = "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$wrapper`" -Node `"$node`" -Claude `"$claude`""
$action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument $arguments -WorkingDirectory $here

$atSignIn = New-ScheduledTaskTrigger -AtLogOn -User $user
$recheck = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) -RepetitionInterval (New-TimeSpan -Minutes $CheckEveryMinutes)

$settings = New-ScheduledTaskSettingsSet `
  -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable `
  -MultipleInstances IgnoreNew `
  -ExecutionTimeLimit ([TimeSpan]::Zero) `
  -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1)

$principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited

Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $atSignIn, $recheck `
  -Settings $settings -Principal $principal -Force `
  -Description 'Keeps the Manga Loud relay (relay\server.mjs) running for the phone app.' | Out-Null

Start-ScheduledTask -TaskName $taskName
Write-Host "Installed and started the '$taskName' task."
Write-Host "Relay log: $(Join-Path $here 'logs\relay.log')"
