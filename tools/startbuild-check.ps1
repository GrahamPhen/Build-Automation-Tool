<#
    StartBuild unattended-run check.
    ==============================
    Answers one question: is the build actually progressing, or is it stalled?

    Evidence used, strongest first:
      1. StartBuild's own chat/log messages (stall warnings, restarts, stops) - authoritative.
      2. Whether the Flashback temp recording is still growing - if the world is changing at all.
      3. Whether the log is still being written.
    Deliberately NOT used: world region-file mtimes. That misled once already - no world save may have
    flushed chunks, so an untouched region file proves nothing.

    Usage:  powershell -ExecutionPolicy Bypass -File tools\startbuild-check.ps1
#>
[CmdletBinding()]
param(
    [string] $Instance = 'BuildRecording',
    [switch] $Quiet
)

$mc  = Join-Path $env:APPDATA "PrismLauncher\instances\$Instance\minecraft"
$log = Join-Path $mc 'logs\latest.log'
$lines = @()

function Add-Line { param([string] $m) $script:lines += $m }

Add-Line "=== StartBuild check: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') ==="

# ---- process -------------------------------------------------------------------------------------
$procs = @(Get-Process javaw -ErrorAction SilentlyContinue)
if ($procs.Count -eq 0) {
    Add-Line 'GAME: not running'
} else {
    foreach ($p in $procs) {
        Add-Line ("GAME: pid={0} started={1:HH:mm:ss} responding={2} cpu={3:N0}s private={4:N0}MB" -f `
            $p.Id, $p.StartTime, $p.Responding, $p.TotalProcessorTime.TotalSeconds, ($p.PrivateMemorySize64 / 1MB))
    }
}

if (-not (Test-Path $log)) {
    Add-Line "no log at $log"
    $lines | ForEach-Object { Write-Output $_ }
    return
}

$li = Get-Item $log
$logAge = (New-TimeSpan -Start $li.LastWriteTime -End (Get-Date)).TotalSeconds
Add-Line ("LOG: {0:N0}KB modified {1:HH:mm:ss} ({2:N0}s ago)" -f ($li.Length / 1KB), $li.LastWriteTime, $logAge)

# ---- live world activity: weak evidence only ------------------------------------------------------
# Flashback flushes the recording in bursts, so "no growth in 10 seconds" does NOT mean nothing is
# happening. This line misled a real diagnosis once (it reported "world is static" during a run that had
# placed 160 blocks). Treat it as a hint, never as the verdict - StartBuild's own stall counters below are
# the authority, because they fire when placements genuinely stop.
$temp = Join-Path $mc 'flashback\temp\recording'
if (Test-Path $temp) {
    $first = (Get-ChildItem $temp -Recurse -File -ErrorAction SilentlyContinue | Measure-Object Length -Sum).Sum
    Start-Sleep -Seconds 10
    $second = (Get-ChildItem $temp -Recurse -File -ErrorAction SilentlyContinue | Measure-Object Length -Sum).Sum
    $delta = $second - $first
    Add-Line ("RECORDING: {0:N2}MB -> {1:N2}MB  delta={2:N0}KB in 10s  (weak hint only: Flashback writes in bursts)" -f `
        ($first / 1MB), ($second / 1MB), ($delta / 1KB))
} else {
    Add-Line 'RECORDING: no flashback temp folder (nothing being recorded)'
}

# ---- StartBuild's own view ------------------------------------------------------------------------
$content = Get-Content $log
Add-Line '--- StartBuild events (log order, chat echoes removed) ---'
$content | Select-String -Pattern '\[StartBuild\]' |
    Where-Object { $_.Line -notmatch '\[System\] \[CHAT\]' } |
    Select-Object -Last 30 | ForEach-Object { Add-Line ('  ' + $_.Line.Trim()) }

# ---- /buildstatus output, if the player has run it ------------------------------------------------
$status = $content | Select-String -Pattern 'state=|buildActive=|timing=' |
    Where-Object { $_.Line -notmatch '\[System\] \[CHAT\]' } | Select-Object -Last 4
if ($status) {
    Add-Line '--- last /buildstatus ---'
    $status | ForEach-Object { Add-Line ('  ' + $_.Line.Trim()) }
}

# ---- health counters ------------------------------------------------------------------------------
Add-Line '--- counters (a flood here is a bug: each should be 0 or tiny) ---'
foreach ($pattern in @('no blocks placed', 'Build resumed', 'Restarting the build',
                       'placed nothing for', 'Auto-stocked', 'Auto-stock stopped',
                       'Baritone is no longer recording', 'stayed stuck')) {
    $n = @($content | Select-String -Pattern $pattern -SimpleMatch).Count
    Add-Line ("  {0,-32} {1}" -f $pattern, $n)
}

# ---- Baritone's own messages ----------------------------------------------------------------------
Add-Line '--- last Baritone chat ---'
$baritone = $content | Select-String -Pattern '\[CHAT\] \[Baritone\]' | Select-Object -Last 6
if ($baritone) { $baritone | ForEach-Object { Add-Line ('  ' + $_.Line.Trim()) } }
else { Add-Line '  (none)' }

# ---- replays --------------------------------------------------------------------------------------
Add-Line '--- replays, newest first ---'
Get-ChildItem (Join-Path $mc 'flashback\replays') -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 4 |
    ForEach-Object { Add-Line ("  {0:MM-dd HH:mm:ss}  {1}  {2:N1}MB" -f $_.LastWriteTime, $_.Name, ($_.Length / 1MB)) }

# ---- verdict --------------------------------------------------------------------------------------
# Based on StartBuild's own counters, which are the authority. The stall detector is known to fire, so
# its SILENCE while a build is running is real evidence that placements are still happening - unlike the
# recording-size hint above, which reports false negatives because Flashback writes in bursts.
Add-Line '--- verdict ---'
$stalled = @($content | Select-String -Pattern 'placed nothing for|looks stuck').Count
$restarts = @($content | Select-String -Pattern 'Restarting the build').Count
$gaveUp = @($content | Select-String -Pattern 'stayed stuck').Count
$saved = @($content | Select-String -Pattern 'Recording stopped and saved').Count
$requested = @($content | Select-String -Pattern 'Build requested').Count
if ($gaveUp -gt 0) {
    Add-Line '  Baritone stalled through every restart; StartBuild should have stopped and SAVED.'
} elseif ($restarts -gt 0 -and $saved -eq 0) {
    Add-Line "  StartBuild has restarted the build $restarts time(s) - it is trying to self-heal."
} elseif ($stalled -gt 0 -and $saved -eq 0) {
    Add-Line '  A stall was detected. Watch for a restart line next.'
} elseif ($saved -gt 0) {
    Add-Line '  A run has already been stopped and saved this session.'
} elseif ($requested -gt 0) {
    Add-Line '  Build requested and no stall warning since: placements are still happening.'
    Add-Line '  Run /buildstatus in game for the layer number and placement count.'
} else {
    Add-Line '  No build has been started in this session.'
}

$lines | ForEach-Object { Write-Output $_ }
