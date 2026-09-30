# Restart between takes to install a newer StartBuild: the queue is held back (renamed) while the current
# take runs, so the mod cannot start the next take before the restart. When the take ends, the recording
# game (26.2 only - never the other Minecraft) is closed like clicking X, the newest jar installed, and the
# game relaunched with the first held line armed; the rest goes back into the queue.
$me = Join-Path $env:TEMP 'startbuild-overnight.log'
function Say($m) { Add-Content $me ("{0}  {1}" -f (Get-Date -Format 'HH:mm:ss'), $m) }
$inst = Join-Path $env:APPDATA 'PrismLauncher\instances\BuildRecording\minecraft'
$log = Join-Path $inst 'logs\latest.log'
$queue = Join-Path $inst 'config\startbuild-queue.txt'
$hold = Join-Path $inst 'config\startbuild-queue.hold'
function RecGame { Get-Process javaw -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -match '26\.2' } | Select-Object -First 1 }

if (Test-Path $queue) { Move-Item $queue $hold -Force }
$g = RecGame
Say "restart-2: queue held; waiting for the take in pid $($g.Id) to end"
$startLines = (Get-Content $log).Count
while ($true) {
    Start-Sleep -Seconds 20
    if (-not (Get-Process -Id $g.Id -ErrorAction SilentlyContinue)) { Say 'recording game already gone'; break }
    $end = Get-Content $log | Select-Object -Skip $startLines | Select-String '\[StartBuild\] (Done:|.*Stopped:)' | Select-Object -First 1
    if ($end) { Say "take ended: $($end.Line)"; break }
}
Start-Sleep -Seconds 45
$p = Get-Process -Id $g.Id -ErrorAction SilentlyContinue
if ($p) {
    Say 'closing the recording game (window close)'
    [void]$p.CloseMainWindow()
    for ($i = 0; $i -lt 36 -and -not $p.HasExited; $i++) { Start-Sleep -Seconds 5; $p.Refresh() }
    if (-not $p.HasExited) { Say 'did not exit in 3 min - ending it'; Stop-Process -Id $g.Id -Force; Start-Sleep 10 }
}
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
