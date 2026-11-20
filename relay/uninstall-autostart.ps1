# Removes the Manga Loud relay autostart task and stops the relay it started.
#
#   powershell -ExecutionPolicy Bypass -File relay\uninstall-autostart.ps1

$taskName = 'MangaLoudRelay'
$wrapper = Join-Path $PSScriptRoot 'run-relay.ps1'

if (Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue) {
  Stop-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
  Unregister-ScheduledTask -TaskName $taskName -Confirm:$false
  Write-Host "Removed the '$taskName' task."
} else {
  Write-Host "No '$taskName' task was installed."
}

# Stop the keep-alive loop and everything under it (cmd, then node), so nothing restarts the relay.
# Only processes started by run-relay.ps1 are touched, not other node apps.
$all = Get-CimInstance Win32_Process
function Get-Tree([int]$id) {
  $id
  $all | Where-Object { $_.ParentProcessId -eq $id } | ForEach-Object { Get-Tree $_.ProcessId }
}
$loops = $all | Where-Object { $_.Name -eq 'powershell.exe' -and $_.CommandLine -like "*$wrapper*" }
$ids = $loops | ForEach-Object { Get-Tree $_.ProcessId }
$ids | ForEach-Object { Stop-Process -Id $_ -Force -ErrorAction SilentlyContinue }

if ($ids) { Write-Host "Stopped the relay ($(@($ids).Count) processes)." } else { Write-Host 'The relay was not running from autostart.' }
