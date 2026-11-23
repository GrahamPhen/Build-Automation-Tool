# Waits (cheaply, no output) while run-queue.ps1 works, and ends only when something needs a look: a build that was
# not saved, an error, the queue stopping, or a game log that has been silent for 20 minutes. Then prints what happened.
#   watch-queue.ps1 [-Minutes 840] [-StopOnReady]        (-StopOnReady: also end when the next Short is ready)
param([int] $Minutes = 840, [switch] $StopOnReady)
$q = Join-Path $PSScriptRoot '..\shorts\run-queue.log'
$g = "$env:APPDATA\PrismLauncher\instances\BuildRecording\minecraft\logs\latest.log"
$pat = 'build: (?!saved)|error|FAILED|no final|no rendered|no replay|no extent|cannot time|stopping|run-queue finished'
if ($StopOnReady) { $pat += '|SHORT READY' }
$start = @(Get-Content $q).Count
$deadline = (Get-Date).AddMinutes($Minutes)
while ((Get-Date) -lt $deadline) {
    Start-Sleep 60
    $hit = @(Get-Content $q | Select-Object -Skip $start | Where-Object { $_ -match $pat })
    if ($hit) { $hit | Select-Object -Last 8 | ForEach-Object { $_.Substring(0, [math]::Min(250, $_.Length)) }; break }
    # (only while the game is open: between takes the log is quiet anyway)
    $game = Get-Process javaw -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -match '26\.2' }
    if ($game -and (Test-Path $g) -and ((Get-Date) - (Get-Item $g).LastWriteTime).TotalMinutes -gt 20) { 'game log silent for 20 min'; break }
    # a build that keeps logging but gets nowhere (2026-11-22: endless bone meal): same placed/broken/left for 20 min
    if ($game -and (Test-Path $g)) {
        $p = Get-Content $g -Tail 400 | Where-Object { $_ -match 'progress: .* - (\d+ placed, \d+ broken, \d+ left)' } | Select-Object -Last 1
        if ($p -and $p -match '(\d+ placed, \d+ broken, \d+ left)') {
            if ($Matches[1] -ne $last) { $last = $Matches[1]; $since = Get-Date }
            elseif (((Get-Date) - $since).TotalMinutes -gt 20) { $s = "no progress for 20 min: $p"; $s.Substring(0, [math]::Min(250, $s.Length)); break }
        }
    }
}
"end $(Get-Date -Format HH:mm)"
