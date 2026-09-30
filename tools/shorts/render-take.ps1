# Renders one take's Shorts: closes the 26.2 game, writes the render flag, relaunches to the title screen,
# waits for every job to finish, verifies each video and copies it to the Desktop Shorts folder.
#   render-take.ps1 -Build charmander_80 -Common "replay=... centerX=..." -Jobs "style=cinematic music=royalty","style=orbit music=heat_waves_slowed"
param([string] $Build, [string] $Common, [string[]] $Jobs, [string[]] $Extra = @())
$inst = "$env:APPDATA\PrismLauncher\instances\BuildRecording\minecraft"
$log = "$inst\logs\latest.log"
$exports = "$inst\flashback\exports"
$dest = "C:\Users\Graham\Desktop\Shorts\$Build"
$readme = 'C:\Users\Graham\Desktop\Shorts\README.txt'
$me = Join-Path $env:TEMP 'startbuild-render.log'
function Say($m) { $l = "{0}  {1}" -f (Get-Date -Format 'HH:mm:ss'), $m; Add-Content $me $l; $l }
function RecGame { Get-Process javaw -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -match '26\.2' } | Select-Object -First 1 }

$g = RecGame
if ($g) {
    Say "closing 26.2 game pid $($g.Id)"
    [void]$g.CloseMainWindow()
    if (-not $g.WaitForExit(90000)) { Say 'did not exit in 90 s - ending it'; Stop-Process -Id $g.Id -Force; Start-Sleep 10 }
    Start-Sleep 8
}

$lines = @(); $outs = @()
for ($k = 0; $k -lt $Jobs.Count; $k++) {
    $j = $Jobs[$k]
    $style = if ($j -match 'style=(\w+)') { $Matches[1] } else { 'orbit' }
    $music = if ($j -match 'music=(\S+)') { $Matches[1] } else { 'none' }
    $letter = @{ cinematic = 'B'; orbit = 'C'; tripod = 'A' }[$style]
    $suffix = if ($j -match 'timeOfDay=(\d+)') { "-t$($Matches[1])" } else { '' }
    $out = "$Build-$letter-$style-$music$suffix.mp4"
    $lines += "$Common $j output=$out"
    $outs += $out
}
Set-Content -Path "$inst\config\startbuild-render" -Value $lines -Encoding ascii
Say "flag written for $Build ($($lines.Count) jobs)"
$started = Get-Date
& powershell -NoProfile -ExecutionPolicy Bypass -File 'C:\Users\Graham\Codex\MineSurvive\tools\startbuild-launch.ps1' *> $null
Say 'launched'

# Wait for the new log (its first line is stamped after the launch), then for all jobs.
$launchTod = $started.AddSeconds(-10).TimeOfDay
for ($w = 0; $w -lt 40; $w++) {
    Start-Sleep 15
    $first = Get-Content $log -TotalCount 1 -ErrorAction SilentlyContinue
    if ($first -match '^\[(\d\d:\d\d:\d\d)\]') {
        $d = ([TimeSpan]::Parse($Matches[1]) - $launchTod).TotalHours
        if ($d -ge 0 -or $d -lt -12) { break }                     # (the second: past midnight)
    }
}
$deadline = (Get-Date).AddMinutes(20 + 25 * $lines.Count)
$result = 'timeout'
while ((Get-Date) -lt $deadline) {
    Start-Sleep 30
    $t = Get-Content $log -ErrorAction SilentlyContinue
    $fin = @($t | Select-String 'render: finished').Count
    $bad = $t | Select-String 'render: (failed|no such replay|the replay did not open|no music file)|music FAILED' | Select-Object -First 1
    if ($bad) { $result = "error: $($bad.Line)"; if ($bad.Line -notmatch 'no music|music FAILED') { break } }
    if ($fin -ge $lines.Count) { $result = 'ok'; break }
    if (-not (RecGame) -and ((Get-Date) -gt $started.AddMinutes(4))) { $result = 'game gone'; break }
}
Say "$Build render result: $result"
Get-Content $log | Select-String '\[StartBuild\] render:' | ForEach-Object { Say ("  " + ($_.Line -replace '^.*\[StartBuild\] render: ', '')) } | Out-Null

New-Item -ItemType Directory -Force $dest | Out-Null
$sh = New-Object -ComObject Shell.Application
$ns = $sh.Namespace($exports)
foreach ($o in $outs) {
    $p = Join-Path $exports $o
    if (-not (Test-Path $p)) { Say "MISSING $o"; continue }
    $i = $ns.ParseName($o)
    $dur = [math]::Round($i.ExtendedProperty('System.Media.Duration') / 1e7, 1)
    $wh = "$($i.ExtendedProperty('System.Video.FrameWidth'))x$($i.ExtendedProperty('System.Video.FrameHeight'))"
    $ab = $i.ExtendedProperty('System.Audio.EncodingBitrate')
    $ok = ($dur -ge 25 -and $dur -le 65 -and $wh -eq '1080x1920' -and $ab)
    Say ("{0}: {1}s {2} audio={3} {4}" -f $o, $dur, $wh, $ab, $(if ($ok) { 'OK' } else { 'CHECK' }))
    Copy-Item $p $dest -Force
    $rest = $o.Substring($Build.Length + 1) -replace '\.mp4$', ''     # B-cinematic-royalty[-t9000]
    $variant = ($rest -split '-')[0..1] -join '-'
    $mus = $rest.Substring($variant.Length + 1)
    Add-Content $readme ("{0,-24} {1,-13} {2,-32} {3,5}s  {4}{5}" -f $Build, $variant, $mus, $dur, (Join-Path $dest $o), $(if ($ok) { '' } else { '  (CHECK)' }))
}
Say "$Build done"
