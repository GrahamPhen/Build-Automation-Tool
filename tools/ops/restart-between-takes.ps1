# Restart between takes to install a newer StartBuild: the queue is held back (renamed) while the current
# take runs, so the mod cannot start the next take before the restart. When the take ends, the recording
# game (26.2 only - never the other Minecraft) is closed like clicking X, the newest jar installed, and the
# game relaunched with the first held line armed; the rest goes back into the queue.
$ErrorActionPreference = 'Stop'
$me = Join-Path $env:TEMP 'startbuild-overnight.log'
function Say($m) { Add-Content $me ("{0}  {1}" -f (Get-Date -Format 'HH:mm:ss'), $m) }
$inst = Join-Path $env:APPDATA 'PrismLauncher\instances\BuildRecording\minecraft'
$log = Join-Path $inst 'logs\latest.log'
$queue = Join-Path $inst 'config\startbuild-queue.txt'
$hold = Join-Path $inst 'config\startbuild-queue.hold'
function RecGame {
    $games = @(Get-Process javaw -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -match '\b26\.2\b' })
    if ($games.Count -gt 1) { throw 'More than one Minecraft 26.2 window is open; leaving the queue held.' }
    if ($games.Count -eq 1) { return $games[0] }
}
function SameRecGame {
    $p = Get-Process -Id $gameId -ErrorAction SilentlyContinue
    if ($p -and ($p.ProcessName -ne 'javaw' -or $p.StartTime -ne $gameStarted -or $p.MainWindowTitle -notmatch '\b26\.2\b')) {
        throw "Recording process identity changed for pid $gameId; leaving the queue held."
    }
    return $p
}
function TakeState {
    if (-not (Test-Path -LiteralPath $log) -or (Get-Item -LiteralPath $log).LastWriteTime -lt $gameStarted) { return 'Unknown' }
    # Ignore unrelated messages after Done. A later search/preparation/recording means a new take is active.
    $event = Get-Content -LiteralPath $log | Select-String '\[StartBuild\] (Done:|.*Stopped:|left the world during a take|recording discarded|autorun flag found:|queue: next take|hands-free take:|findsite |hands-free: |natural build of |Preparing |recording=|progress:|last block placed)' | Select-Object -Last 1
    if (-not $event) { return 'Unknown' }
    if ($event.Line -match '\[StartBuild\] (Done:|.*Stopped:|left the world during a take|recording discarded)') { return 'Idle' }
    return 'Active'
}
$g = RecGame
if (-not $g) { Say 'no Minecraft 26.2 window found; queue preserved, no restart'; exit 1 }
$gameId = $g.Id
$gameStarted = $g.StartTime
if (Test-Path -LiteralPath $queue) {
    if (Test-Path -LiteralPath $hold) { throw 'Both queue and held queue exist; refusing to overwrite either.' }
    Move-Item -LiteralPath $queue -Destination $hold
}
if (-not (Test-Path -LiteralPath $hold)) { throw 'No held queue found; no restart.' }
Say "restart-2: queue held; checking the take in pid $gameId"
$waiting = $false
while ($true) {
    if (-not (SameRecGame)) { Say 'recording game already gone'; break }
    if ((TakeState) -eq 'Idle') {
        Say 'take already ended; allowing the recording save to finish'
        Start-Sleep -Seconds 45
        if (-not (SameRecGame) -or (TakeState) -eq 'Idle') { break }
        # A take that started just before the queue was held must also finish before closing.
    }
    if (-not $waiting) { Say "waiting for the active take in pid $gameId to end"; $waiting = $true }
    Start-Sleep -Seconds 20
}
$p = SameRecGame
if ($p) {
    Say 'closing the recording game (window close)'
    [void]$p.CloseMainWindow()
    for ($i = 0; $i -lt 36 -and -not $p.HasExited; $i++) { Start-Sleep -Seconds 5; $p.Refresh() }
    if (-not $p.HasExited) {
        $p = SameRecGame
        if ($p) { Say 'did not exit in 3 min - ending the same 26.2 process'; Stop-Process -Id $gameId -Force; Start-Sleep 10 }
    }
}
if (SameRecGame) { throw 'Recording game is still open; no install or relaunch.' }
Say 'game closed'
Start-Sleep -Seconds 10

$lines = @(Get-Content $hold)
$first = $null; $rest = New-Object System.Collections.Generic.List[string]
foreach ($l in $lines) { if (-not $first -and $l.Trim() -and -not $l.Trim().StartsWith('#')) { $first = $l.Trim() } else { $rest.Add($l) } }
Set-Content $queue $rest -Encoding ascii
Remove-Item $hold -Force
if (-not $first) { Say 'queue empty - not relaunching'; exit 0 }
$name, $wish = $first -split '\s+', 2
$launcher = 'C:\Users\Graham\Codex\MineSurvive\tools\startbuild-launch.ps1'
# The launcher now ignores other Minecraft games and answers Prism's low-memory prompt itself.
$out = & powershell -NoProfile -ExecutionPolicy Bypass -File $launcher -Build $name -Wish "$wish" 2>&1 | Out-String
Say ("launcher: " + (($out -split "`n" | Where-Object { $_ -match 'installed from|auto-build|FAIL|low-memory|told to start' }) -join ' | '))
Say "relaunched with '$first' armed; queue now: $((Get-Content $queue | Where-Object { $_ -and -not $_.StartsWith('#') }) -join ', ')"
