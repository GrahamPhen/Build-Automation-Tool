# Build + edit, one take at a time: launch the next build hands-free, wait for it, then straight away make its
# Short in the approved style (orbit cut ending square on the build's front, music, MineSurvive outro, a
# phone-size copy and a thumbnail) before the next build starts.
#   run-queue.ps1 [-DelayMinutes 0]
# Builds come from <instance>\config\startbuild-run.txt ("name wish" per line; edit it any time - the first line
# is taken when the previous one is finished). Stop after the current take: create run-queue.pause here.
param([int] $DelayMinutes = 0)
$ErrorActionPreference = 'Continue'
$here = $PSScriptRoot
$inst = "$env:APPDATA\PrismLauncher\instances\BuildRecording\minecraft"
$list = "$inst\config\startbuild-run.txt"
$logf = Join-Path $here 'run-queue.log'
$launcher = 'C:\Users\Graham\Codex\MineSurvive\tools\startbuild-launch.ps1'
function Say($m) { Add-Content $logf ("{0}  {1}" -f (Get-Date -Format 'MM-dd HH:mm:ss'), $m) }
function RecGame { Get-Process javaw -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -match '26\.2' } | Select-Object -First 1 }
function CloseGame {
    $g = RecGame
    if ($g) { [void]$g.CloseMainWindow(); if (-not $g.WaitForExit(120000)) { Stop-Process -Id $g.Id -Force }; Start-Sleep 8 }
}
function Songs($b) {
    if ($b -match '(?i)coffin|bat_|ghost|skull|halloween|haunt|magma|dragon|necro|reaper|pumpkin') { return 'middle_of_the_night_slowed' }
    if ($b -match '(?i)pokemon|rowlet|alakazam|mew|blastoise|articuno|pikachu|eevee') { return 'heat_waves_slowed' }
    if ($b -match '(?i)castle|tower|spire|watchtower|liner|ship|sanctuary|chapel|portal') { return 'colossus' }
    return 'runaway_slowed'
}
$inv = [Globalization.CultureInfo]::InvariantCulture

if ($DelayMinutes -gt 0) { Say "waiting $DelayMinutes min before starting"; Start-Sleep -Seconds ($DelayMinutes * 60) }
Say 'run-queue started'
while ($true) {
    if (Test-Path (Join-Path $here 'run-queue.pause')) { Say 'pause file found - stopping'; break }
    $lines = @(Get-Content $list -ErrorAction SilentlyContinue | Where-Object { $_.Trim() -and -not $_.Trim().StartsWith('#') })
    if (-not $lines.Count) { Say 'list empty - done'; break }
    $line = $lines[0].Trim()
    Set-Content $list ($lines | Select-Object -Skip 1) -Encoding ascii
    $name, $wish = $line -split '\s+', 2
    Say "=== $name ($wish)"

    # 1. build it
    CloseGame
    $launchTime = Get-Date
    & powershell -NoProfile -ExecutionPolicy Bypass -File $launcher -Build $name -Wish "$wish" *> $null
    $log = "$inst\logs\latest.log"
    Start-Sleep 60
    $ev = @{}; $cursor = 0; $result = $null
    $deadline = (Get-Date).AddHours(10)
    while ((Get-Date) -lt $deadline -and -not $result) {
        Start-Sleep 20
        $all = @(Get-Content $log -ErrorAction SilentlyContinue)
        if ($all.Count -lt $cursor) { $cursor = 0 }                                # rotated (midnight / restart)
        foreach ($l in $all[$cursor..($all.Count - 1)]) {
            if ($l -notmatch '^\[(\d\d:\d\d:\d\d)\].*\[StartBuild\]' -or $l -match 'CHAT') { continue }
            $t = [datetime]::ParseExact($Matches[1], 'HH:mm:ss', $inv)
            $t = (Get-Date).Date.Add($t.TimeOfDay); if ($t -gt (Get-Date).AddMinutes(1)) { $t = $t.AddDays(-1) }
            if ($t -lt $launchTime.AddMinutes(-1)) { continue }
            if ($l -match ("recording=true " + [regex]::Escape($name) + "\.\w+ at (-?\d+), (-?\d+), (-?\d+)")) { $ev.R = $t; $ev.O = @([int]$Matches[1], [int]$Matches[2], [int]$Matches[3]) }
            elseif ($l -match 'building \((\d+) of \1\)') { $ev.T = $t }
            elseif ($l -match 'last block placed') { $ev.L = $t }
            elseif ($l -match 'Done: .*Recording saved') { $ev.D = $t; $result = 'saved' }
            elseif ($l -match 'Done: |Giving up|Stopped') { $result = 'failed: ' + ($l -replace '^.*\[StartBuild\] ', '') }
        }
        $cursor = $all.Count
        if (-not $result -and -not (RecGame) -and (Get-Date) -gt $launchTime.AddMinutes(5)) { $result = 'game gone' }
    }
    if (-not $result) { $result = 'timeout' }
    Say "$name build: $result"
    if ($result -ne 'saved' -or -not $ev.R -or -not $ev.L) { continue }

    # 2. its Short, straight away
    Start-Sleep 40
    $replay = Get-ChildItem "$inst\flashback\replays" -Filter *.zip | Where-Object { [math]::Abs(($_.LastWriteTime - $ev.D).TotalSeconds) -lt 90 } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $replay) { Say "  no replay file found near $($ev.D)"; continue }
    $tmp = Join-Path $env:TEMP ("rq-" + [guid]::NewGuid().ToString('N').Substring(0, 8)); New-Item -ItemType Directory $tmp | Out-Null
    Copy-Item -LiteralPath "$inst\schematics\$name.litematic" $tmp
    $ext = node (Join-Path $here 'extent.mjs') $tmp | Select-Object -First 1
    if ($ext -notmatch 'x (\d+)-(\d+) y (\d+)-(\d+) z (\d+)-(\d+) \| w (\d+) h (\d+) d (\d+)') { Say "  no extent: $ext"; continue }
    $cx = $ev.O[0] + ([int]$Matches[1] + [int]$Matches[2]) / 2; $cz = $ev.O[2] + ([int]$Matches[5] + [int]$Matches[6]) / 2
    $w = $Matches[7]; $h = $Matches[8]; $dp = $Matches[9]
    $T = if ($ev.T) { [int](($ev.T - $ev.R).TotalSeconds * 20) } else { 20 }
    $B = [int](($ev.L - $ev.R).TotalSeconds * 20)
    $common = "replay=$($replay.Name) centerX=$($cx.ToString($inv)) centerZ=$($cz.ToString($inv)) baseY=$($ev.O[1]) width=$w depth=$dp height=$h terraformEnd=$T buildEnd=$B"
    $music = Songs $name
    Say "  render: $common music=$music"
    & (Join-Path $here 'render-take.ps1') -Build $name -Common $common -Jobs "style=orbit music=$music" 2>&1 | ForEach-Object { Say "    $_" }
    & (Join-Path $here 'add-outro-all.ps1') -Only $name 2>&1 | ForEach-Object { Say "    outro: $_" }
    $final = "C:\Users\Graham\Desktop\Shorts\$name\with-outro\$name-C-orbit-$music.mp4"
    if (Test-Path -LiteralPath $final) {
        $ff = (Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages" -Recurse -Filter ffmpeg.exe | Select-Object -First 1).FullName
        & $ff -v error -y -i $final -c:v libx264 -preset slow -crf 25 -maxrate 3500k -bufsize 7000k -c:a aac -b:a 160k -movflags +faststart "C:\Users\Graham\Desktop\Shorts\$name\$name - final (phone size).mp4"
        & (Join-Path $here 'make-thumbnails.ps1') -Only $name 2>&1 | Select-Object -Last 1 | ForEach-Object { Say "    $_" }
        Say "  $name SHORT READY"
    } else { Say "  ${name}: no final video" }
}
CloseGame
Say 'run-queue finished'
