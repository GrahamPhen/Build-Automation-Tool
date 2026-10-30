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
        if ($all.Count -le $cursor) { continue }                                 # nothing new (the range would run backwards)
        foreach ($l in $all[$cursor..($all.Count - 1)]) {
            if ($l -notmatch '^\[(\d\d:\d\d:\d\d)\].*\[StartBuild\]' -or $l -match 'CHAT') { continue }
            $t = [datetime]::ParseExact($Matches[1], 'HH:mm:ss', $inv)
            $t = (Get-Date).Date.Add($t.TimeOfDay); if ($t -gt (Get-Date).AddMinutes(1)) { $t = $t.AddDays(-1) }
            if ($t -lt $launchTime.AddMinutes(-1)) { continue }
            if ($l -match ("recording=true " + [regex]::Escape($name) + "\.\w+ at (-?\d+), (-?\d+), (-?\d+)")) { $ev = @{ R = $t; O = @([int]$Matches[1], [int]$Matches[2], [int]$Matches[3]) } }   # a retry starts its timings afresh
            elseif ($l -cmatch '\] building \((\d+) of \1\)') { $ev.T = $t }
            elseif ($l -cmatch 'progress: building - (\d+) placed') { if (-not $ev.P) { $ev.P = @() }; $ev.P += , @($t, [int]$Matches[1]) }
            elseif ($l -cmatch 'last block placed') { $ev.L = $t }
            elseif ($l -cmatch '\[StartBuild\] Done: .*Recording saved') { $ev.D = $t; $result = 'saved' }
            elseif ($l -cmatch '\[StartBuild\] (Done: |Hands-free take: no usable site|.*Giving up|.*Stopped)') { $result = 'failed: ' + ($l -replace '^.*\[StartBuild\] ', '') }
            # (case-sensitive on purpose: "digging done:" read as a finish closed the castle take mid-dig)
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
    $by = $ev.O[1] + [int]$Matches[3]                                         # lowest non-air layer, not the region corner
    if (-not $ev.T) { Say "  no 'building (N of N)' line - cannot time the edit, skipped"; continue }   # (fell back to 20: a slow-motion opening)
    $T = [int](($ev.T - $ev.R).TotalSeconds * 20)
    $B = [int](($ev.L - $ev.R).TotalSeconds * 20)
    # blocks placed over time (the progress lines) -> the renderer spends the timelapse where the build grows
    $prog = ($ev.P | ForEach-Object { "{0}:{1}" -f [int](($_[0] - $ev.R).TotalSeconds * 20), $_[1] }) -join ','
    $common = "replay=$($replay.Name) centerX=$($cx.ToString($inv)) centerZ=$($cz.ToString($inv)) baseY=$by width=$w depth=$dp height=$h terraformEnd=$T buildEnd=$B"
    if ($prog) { $common += " progress=$prog" }
    if ($wish -match '(?i)snow|frozen|ice') { $common += ' dusk=0' }     # sunset + shader snow fog looked orange
    $music = Songs $name
    # Tall builds (towers, castles, ships...) get the drone fly-by - it climbs alongside them as they go up; the rest
    # alternate between the smooth orbit and snap cuts (a new angle every ~4 s). (Owner's picks, 2026-10-29.)
    $styleFile = Join-Path $here 'run-queue.last-style'
    $tall = ([int]$h -ge 28 -and [int]$h -ge 0.7 * [math]::Max([int]$w, [int]$dp)) -or $name -match '(?i)castle|tower|spire|lighthouse|windmill|ship|liner|cathedral|church|chapel'
    $style = if ($tall) { 'flyby' } elseif ((Get-Content $styleFile -ErrorAction SilentlyContinue) -eq 'orbit') { 'cuts' } else { 'orbit' }
    $cutName = @{ orbit = 'C-orbit'; cuts = 'D-cuts'; flyby = 'E-flyby' }[$style]
    Say "  render ($style): $common music=$music"
    $renderStart = Get-Date
    try { & (Join-Path $here 'render-take.ps1') -Build $name -Common $common -Jobs "style=$style music=$music" 2>&1 | ForEach-Object { Say "    $_" } } catch { Say "    render error: $_" }
    $raw = "C:\Users\Graham\Desktop\Shorts\$name\$name-$cutName-$music.mp4"
    if ((Test-Path -LiteralPath $raw) -and $style -ne 'flyby') { Set-Content $styleFile $style }   # (fly-bys don't break the orbit/cuts turn-taking)
    # only a video rendered (and checked) just now - a rebuild's earlier take of the same name must not be finished
    if ((Test-Path -LiteralPath $raw) -and (Get-Item -LiteralPath $raw).LastWriteTime -gt $renderStart) {
        # a stopping error in a child script used to end this whole loop (it died after Pinecrest's thumbnail)
        try { & (Join-Path $here 'make-thumbnails.ps1') -Only $name 2>&1 | Select-Object -Last 1 | ForEach-Object { Say "    $_" } } catch { Say "    thumbnail error: $_" }
        $row = Import-Csv 'C:\Users\Graham\Desktop\Shorts Thumbnails and Titles\titles.csv' | Where-Object { $_.Video -eq "$name-$cutName-$music.mp4" } | Select-Object -First 1
        # hook opening + hook text + server banner + seamless loop (replaces the separate 4.6 s end card)
        $final = "C:\Users\Graham\Desktop\Shorts\$name\$name - final.mp4"
        try { & (Join-Path $here 'finish-short.ps1') -In $raw -Out $final -Build $name -Display "$($row.Build)" -Title "$($row.Title)" 2>&1 | ForEach-Object { Say "    $_" } } catch { Say "    finish error: $_" }
        if (Test-Path -LiteralPath $final) {
            $ff = (Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages" -Recurse -Filter ffmpeg.exe | Select-Object -First 1).FullName
            & $ff -v error -y -i $final -c:v libx264 -preset slow -crf 25 -maxrate 3500k -bufsize 7000k -c:a aac -b:a 160k -movflags +faststart "C:\Users\Graham\Desktop\Shorts\$name\$name - final (phone size).mp4"
            Say "  $name SHORT READY"
        } else { Say "  ${name}: no final video" }
    } else { Say "  ${name}: no rendered video" }
}
CloseGame
# upload kit for everything: thumbnails, descriptions with hashtags, one upload sheet
try { & (Join-Path $here 'package-all.ps1') 2>&1 | Select-Object -Last 1 | ForEach-Object { Say "  $_" } } catch { Say "  package error: $_" }
Say 'run-queue finished'
