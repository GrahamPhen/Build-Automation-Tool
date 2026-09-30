# Waits (cheaply, no output) until the running take ends or fails, then prints what happened.
# Survives the game's log rotation at midnight and restarts. Use it instead of polling progress.
#   watch-take.ps1 [-Minutes 240]
param([int] $Minutes = 240)
$l = "$env:APPDATA\PrismLauncher\instances\BuildRecording\minecraft\logs\latest.log"
$start = (Get-Content $l).Count
$deadline = (Get-Date).AddMinutes($Minutes)
while ((Get-Date) -lt $deadline) {
    Start-Sleep 60
    $all = Get-Content $l
    if ($all.Count -lt $start) { $start = 0 }        # new log file (midnight or a restart)
    $new = $all | Select-Object -Skip $start | Select-String '\[StartBuild\] (Done:|.*Stopped|No such|Giving up|giving up)|error during'
    if ($new) {
        $new | Select-Object -Last 3 | ForEach-Object {
            $s = $_.Line -replace '^\[(\d\d:\d\d:\d\d)\] \[[^\]]*\]: \[StartBuild\] ', '$1 '
            $s.Substring(0, [Math]::Min(300, $s.Length))
        }
        "hops: " + ($all | Select-Object -Skip $start | Select-String '\] hop to').Count
        break
    }
}
"end $(Get-Date -Format HH:mm)"
