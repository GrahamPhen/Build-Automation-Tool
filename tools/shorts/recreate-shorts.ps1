# Re-renders finished takes in the current Short format from their saved replays (render settings come from
# run-queue.log): the old raw / final / phone copy go to the Recycle Bin first, then render, thumbnail and title,
# finish, phone copy - alternating orbit and snap-cut cameras like run-queue. Ends with package-all.
#   recreate-shorts.ps1 -Builds name1,name2,...
param([Parameter(Mandatory)][string[]] $Builds)
$ErrorActionPreference = 'Continue'
$Builds = @($Builds -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ })   # (powershell -File passes "a,b" as one string)
$here = $PSScriptRoot
$logf = Join-Path $here 'recreate-shorts.log'
$shorts = 'C:\Users\Graham\Desktop\Shorts'
$csvPath = 'C:\Users\Graham\Desktop\Shorts Thumbnails and Titles\titles.csv'
function Say($m) { Add-Content $logf ("{0}  {1}" -f (Get-Date -Format 'MM-dd HH:mm:ss'), $m) }
Add-Type -AssemblyName Microsoft.VisualBasic
function Recycle($p) { [Microsoft.VisualBasic.FileIO.FileSystem]::DeleteFile($p, 'OnlyErrorDialogs', 'SendToRecycleBin') }

# the last render settings per build in run-queue.log
$params = @{}; $cur = $null
foreach ($l in Get-Content (Join-Path $here 'run-queue.log')) {
    if ($l -match '=== (\S+) ') { $cur = $Matches[1] }
    elseif ($cur -and $l -match '^\S+ \S+\s+render(?: \(\w+\))?: (replay=.+) music=(\S+)$') { $params[$cur] = @($Matches[1], $Matches[2]) }
}
$ff = (Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages" -Recurse -Filter ffmpeg.exe | Select-Object -First 1).FullName
$styleFile = Join-Path $here 'run-queue.last-style'
Say "recreate started: $($Builds.Count) builds"
foreach ($name in $Builds) {
    if (Test-Path (Join-Path $here 'run-queue.pause')) { Say 'pause file found - stopping'; break }
    if (-not $params[$name]) { Say "${name}: no render settings in run-queue.log - skipped"; continue }
    $common, $music = $params[$name]
    # same cameras as run-queue: tall builds fly-by, the rest alternate orbit / snap cuts
    $w = if ($common -match 'width=(\d+)') { [int]$Matches[1] } else { 0 }; $dp = if ($common -match 'depth=(\d+)') { [int]$Matches[1] } else { 0 }
    $h = if ($common -match 'height=(\d+)') { [int]$Matches[1] } else { 0 }
    $tall = ($h -ge 28 -and $h -ge 0.7 * [math]::Max($w, $dp)) -or $name -match '(?i)castle|tower|spire|lighthouse|windmill|ship|liner|cathedral|church|chapel'
    $style = if ($tall) { 'flyby' } elseif ((Get-Content $styleFile -ErrorAction SilentlyContinue) -eq 'orbit') { 'cuts' } else { 'orbit' }
    $cutName = @{ orbit = 'C-orbit'; cuts = 'D-cuts'; flyby = 'E-flyby' }[$style]
    $dir = Join-Path $shorts $name
    Say "=== $name ($style, $music)"
    foreach ($f in Get-ChildItem -LiteralPath $dir -File | Where-Object { $_.Name -match '-[CDE]-(orbit|cuts|flyby)-.*\.(mp4|timing\.txt)$' -or $_.Name -like '* - final*.mp4' }) { Recycle $f.FullName }
    $renderStart = Get-Date
    try { & (Join-Path $here 'render-take.ps1') -Build $name -Common $common -Jobs "style=$style music=$music" 2>&1 | ForEach-Object { Say "    $_" } } catch { Say "    render error: $_" }
    $raw = Join-Path $dir "$name-$cutName-$music.mp4"
    if (-not ((Test-Path -LiteralPath $raw) -and (Get-Item -LiteralPath $raw).LastWriteTime -gt $renderStart)) { Say "  ${name}: no rendered video"; continue }
    if ($style -ne 'flyby') { Set-Content $styleFile $style }
    # titles of videos that are gone (the other camera's name) must not be picked up again
    $rows = @(Import-Csv $csvPath | Where-Object { -not ($_.Video -like "$name-*" -and -not (Test-Path -LiteralPath (Join-Path $dir $_.Video))) })
    $rows | Export-Csv -NoTypeInformation -Encoding UTF8 $csvPath
    try { & (Join-Path $here 'make-thumbnails.ps1') -Only $name -Force 2>&1 | Select-Object -Last 1 | ForEach-Object { Say "    $_" } } catch { Say "    thumbnail error: $_" }
    # (make-thumbnails gives the title; the cover itself is the approved v2 style)
    try { & (Join-Path $here 'thumb-v2-build.ps1') -Build $name 2>&1 | Select-Object -Last 1 | ForEach-Object { Say "    $_" } } catch { Say "    v2 thumbnail error: $_" }
    $row = Import-Csv $csvPath | Where-Object { $_.Video -eq "$name-$cutName-$music.mp4" } | Select-Object -First 1
    $final = Join-Path $dir "$name - final.mp4"
    try { & (Join-Path $here 'finish-short.ps1') -In $raw -Out $final -Build $name -Display "$($row.Build)" -Title "$($row.Title)" 2>&1 | ForEach-Object { Say "    $_" } } catch { Say "    finish error: $_" }
    if (Test-Path -LiteralPath $final) {
        & $ff -v error -y -i $final -c:v libx264 -preset slow -crf 25 -maxrate 3500k -bufsize 7000k -c:a aac -b:a 160k -movflags +faststart (Join-Path $dir "$name - final (phone size).mp4")
        Say "  $name SHORT READY"
    } else { Say "  ${name}: no final video" }
}
$g = Get-Process javaw -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -match '26\.2' } | Select-Object -First 1
if ($g) { [void]$g.CloseMainWindow(); [void]$g.WaitForExit(120000) }
try { & (Join-Path $here 'package-all.ps1') 2>&1 | Select-Object -Last 1 | ForEach-Object { Say "  $_" } } catch { Say "  package error: $_" }
Say 'recreate finished'
