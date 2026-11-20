# Keeps the Manga Loud relay running: starts server.mjs, and starts it again whenever it stops.
# install-autostart.ps1 runs this hidden at sign-in. You can also run it by hand to test it.
# Output goes to relay\logs\relay.log.

param(
  [string]$Node = 'node',
  [string]$Claude = '',
  [int]$RestartDelaySeconds = 10,
  [int]$MaxLogBytes = 5MB
)

$here = $PSScriptRoot
$logDir = Join-Path $here 'logs'
$log = Join-Path $logDir 'relay.log'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

if ($Claude) { $env:CLAUDE_BIN = $Claude }

function Write-Log([string]$message) {
  Add-Content -Path $log -Value ('[{0:yyyy-MM-dd HH:mm:ss}] {1}' -f (Get-Date), $message) -Encoding utf8
}

# Only one copy of this loop at a time, however it was started.
$mutex = New-Object System.Threading.Mutex($false, 'Local\MangaLoudRelay')
$owned = $false
try { $owned = $mutex.WaitOne(0) } catch [System.Threading.AbandonedMutexException] { $owned = $true }
if (-not $owned) { exit 0 }

try {
  Write-Log "Keep-alive started (pid $PID)."
  while ($true) {
    if ((Test-Path $log) -and (Get-Item $log).Length -gt $MaxLogBytes) {
      Move-Item -Force $log "$log.1"
    }

    Write-Log 'Starting relay.'
    # cmd does the redirect so node's output and errors append to the log as plain text.
    & cmd.exe /c "`"$Node`" server.mjs >> `"$log`" 2>&1"
    Write-Log "Relay stopped with exit code $LASTEXITCODE. Restarting in $RestartDelaySeconds seconds."
    Start-Sleep -Seconds $RestartDelaySeconds
  }
}
finally {
  $mutex.ReleaseMutex()
}
