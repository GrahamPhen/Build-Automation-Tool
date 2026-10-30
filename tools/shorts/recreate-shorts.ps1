# Re-renders finished takes in the current Short format from their saved replays (render settings come from
# run-queue.log): the old raw / final / phone copy go to the Recycle Bin first, then render, thumbnail and title,
# finish, phone copy - alternating orbit and snap-cut cameras like run-queue. Ends with package-all.
#   recreate-shorts.ps1 -Builds name1,name2,...
param([Parameter(Mandatory)][string[]] $Builds, [switch] $Keep)
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
. (Join-Path $here 'shorts-meta.ps1')
$styleFile = Join-Path $here 'run-queue.last-style'
Say "recreate started: $($Builds.Count) builds"
foreach ($name in $Builds) {
    if (Test-Path (Join-Path $here 'run-queue.pause')) { Say 'pause file found - stopping'; break }
    if (-not $params[$name]) { Say "${name}: no render settings in run-queue.log - skipped"; continue }
    $common, $music = $params[$name]
    if ($music -eq 'runaway_slowed') { $music = Get-NextHouseSong }          # copyright: never Runaway again
    # same camera as run-queue: snap cuts (the owner's favourite; no fly-by - far too jumpy)
    $style = 'cuts'
    $dir = Join-Path $shorts $name
    # -Keep: same song as the Short has now (a quality-only re-render, e.g. 30 -> 60 fps)
    if ($Keep) {
        $cur = Get-ChildItem -LiteralPath $dir -Filter "$name-*.mp4" -ErrorAction SilentlyContinue | Where-Object { $_.Name -match '-[CDE]-(orbit|cuts|flyby)-(.+)\.mp4$' -and $_.Name -notmatch '-s\d+\.mp4$' } |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if ($cur -and $cur.Name -match '-[CDE]-(orbit|cuts|flyby)-(.+)\.mp4$') { $music = $Matches[2] }
    }
    $cutName = @{ orbit = 'C-orbit'; cuts = 'D-cuts'; flyby = 'E-flyby' }[$style]
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
        & $ff -v error -y -i $final -c:v libx264 -preset slow -crf 21 -maxrate 5500k -bufsize 11000k -c:a aac -b:a 160k -movflags +faststart (Join-Path $dir "$name - final (phone size).mp4")
        Say "  $name SHORT READY"
    } else { Say "  ${name}: no final video" }
}
$g = Get-Process javaw -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -match '26\.2' } | Select-Object -First 1
if ($g) { [void]$g.CloseMainWindow(); [void]$g.WaitForExit(120000) }
try { & (Join-Path $here 'package-all.ps1') 2>&1 | Select-Object -Last 1 | ForEach-Object { Say "  $_" } } catch { Say "  package error: $_" }
Say 'recreate finished'
