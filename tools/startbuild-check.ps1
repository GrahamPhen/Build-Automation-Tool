<#
    StartBuild run check (2.x natural builder)
    ==========================================
    Reports what the latest run is doing, straight from the game log (the source of truth):
    whether Minecraft is running, the last terraform / progress / watchdog / shut-in lines, and
    whether the take has finished (Done:) or been stopped (Stopped:).

    Usage:  powershell -ExecutionPolicy Bypass -File tools\startbuild-check.ps1 [-Instance BuildRecording] [-Last 3]
#>
[CmdletBinding()]
param(
    [string] $Instance = 'BuildRecording',
    [int] $Last = 3
)

$mc  = Join-Path $env:APPDATA "PrismLauncher\instances\$Instance\minecraft"
$log = Join-Path $mc 'logs\latest.log'

Write-Output "=== StartBuild check $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') ==="

$procs = @(Get-Process javaw -ErrorAction SilentlyContinue)
if ($procs.Count -eq 0) {
    Write-Output 'GAME: not running'
} else {
    foreach ($p in $procs) {
        Write-Output ("GAME: running pid={0} started={1:HH:mm:ss} responding={2}" -f $p.Id, $p.StartTime, $p.Responding)
    }
}

if (-not (Test-Path $log)) {
    Write-Output "no log at $log"
    exit 0
}
$li = Get-Item $log
Write-Output ("LOG: {0:N0}KB, last written {1:HH:mm:ss} ({2:N0}s ago)" -f ($li.Length / 1KB), $li.LastWriteTime,
    (New-TimeSpan -Start $li.LastWriteTime -End (Get-Date)).TotalSeconds)

# Only StartBuild's own log lines; chat echoes of the same messages are dropped.
$sb = @(Get-Content $log | Where-Object { $_ -match '\[StartBuild\]' -and $_ -notmatch '\[CHAT\]' })

$sections = [ordered]@{
    'terraform plan'  = 'terraform plan:'
    'terraforming'    = 'terraforming:.*done'
    'progress'        = '\[StartBuild\] progress:'
    'watchdog'        = 'watchdog:'
    'shut in'         = 'shut in:'
    'done'            = 'Done:'
    'stopped'         = 'Stopped:'
}
foreach ($name in $sections.Keys) {
    $hits = @($sb | Where-Object { $_ -match $sections[$name] })
    if ($hits.Count -eq 0) { continue }
    Write-Output ("--- {0} ({1} line(s), last {2}) ---" -f $name, $hits.Count, [Math]::Min($Last, $hits.Count))
    $hits | Select-Object -Last $Last | ForEach-Object { Write-Output ('  ' + $_.Trim()) }
}

$verdict = 'no StartBuild run in this log'
$lastLine = $sb | Where-Object { $_ -match 'Done:|Stopped:|progress:|terraform' } | Select-Object -Last 1
if ($lastLine -match 'Done:') { $verdict = 'finished (Done)' }
elseif ($lastLine -match 'Stopped:') { $verdict = 'stopped' }
elseif ($lastLine -and $procs.Count -gt 0) {
    $verdict = 'in progress'
    $recent = @($sb | Where-Object { $_ -match 'progress: (\d+) placed' } | Select-Object -Last 3 |
                ForEach-Object { if ($_ -match 'progress: (\d+) placed') { $Matches[1] } })
    if ($recent.Count -ge 3 -and @($recent | Select-Object -Unique).Count -eq 1) {
        $verdict = "in progress but NOT placing - placed count stuck at $($recent[0]) for the last 3 progress lines"
    }
}
elseif ($lastLine) { $verdict = 'game closed before Done/Stopped - the take may not have been saved' }
Write-Output "VERDICT: $verdict"
