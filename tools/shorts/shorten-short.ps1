# Cuts a build's Short down to ~24 s (owner, 2026-11-09: all Shorts 20-30 s; 2026-10-31: "we can skip parts of
# the build and cut around") from its snap-cut (D) render, without the game: the empty site as the build starts,
# then a slice from the middle of each later camera angle (every jump in progress is also a cut to a new angle, so
# nothing jumps within a shot), then the end of the reveal on the front. The song runs on unbroken under the cuts.
# The 36 s final and phone copy move to "<build>\_long version"; finish-short makes the new final from the cut.
#   shorten-short.ps1 -Build <name> [-Target 24]
param([Parameter(Mandatory)][string] $Build, [double] $Target = 24)
$ErrorActionPreference = 'Stop'
$bin = Split-Path (Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages" -Recurse -Filter ffmpeg.exe | Select-Object -First 1).FullName
$ff = Join-Path $bin 'ffmpeg.exe'; $fp = Join-Path $bin 'ffprobe.exe'
$inv = [Globalization.CultureInfo]::InvariantCulture
function N($x) { ([double]$x).ToString('0.###', $inv) }
$dir = "C:\Users\Graham\Desktop\Shorts\$Build"
$raw = Get-ChildItem -LiteralPath $dir -Filter "$Build-D-cuts-*.mp4" | Where-Object { $_.Name -notmatch '-s\d+\.mp4$' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $raw) { throw "no snap-cut render for $Build" }
$timing = @{}
foreach ($l in Get-Content -LiteralPath ([IO.Path]::ChangeExtension($raw.FullName, '.timing.txt'))) { if ($l -match '^(\w+)=(.+)$') { $timing[$Matches[1]] = @($Matches[2] -split ',' | ForEach-Object { [double]::Parse($_, $inv) }) } }
$dur = [double]::Parse((& $fp -v error -show_entries format=duration -of csv=p=0 $raw.FullName).Trim(), $inv)
$b0 = $timing.build[0]; $b1 = $timing.build[1]
$bounds = @($b0) + @($timing.cuts | Where-Object { $_ -gt $b0 + 0.5 -and $_ -le $b1 + 0.01 })
if ($bounds.Count -lt 2) { $bounds += $b1 }                       # (no cut during the build: one long shot)

# pieces (start, end) in the render: [opening: 1.2 s of empty site + the first blocks] [one slice per later angle] [reveal]
$hook = 1.2                                                        # (finish-short's flash-forward)
$open0 = [math]::Max(0, $b0 - 1.2); $open1 = [math]::Min($b0 + 2.0, $bounds[1] - 0.2)
$reveal = [math]::Min(5.5, $dur - $b1)
$mids = $bounds.Count - 2                                          # later angles during the build
$slice = if ($mids -gt 0) { ($Target - $hook - ($open1 - $open0) - $reveal) / $mids } else { 0 }
$pieces = @(, @($open0, $open1))
for ($k = 1; $k -le $mids; $k++) {
    $s = $bounds[$k]; $e = $bounds[$k + 1]; $len = [math]::Min([math]::Max(1.2, $slice), $e - $s - 0.2)
    $m = ($s + $e) / 2; $pieces += , @(($m - $len / 2), ($m + $len / 2))
}
$pieces += , @(($dur - $reveal), $dur)

$work = Join-Path $env:TEMP ('shorten-' + [guid]::NewGuid().ToString('N').Substring(0, 8)); New-Item -ItemType Directory $work | Out-Null
$f = @(); $cat = ''; $t = 0.0; $cuts = @()
for ($i = 0; $i -lt $pieces.Count; $i++) {
    $f += "[0:v]trim=start=$(N $pieces[$i][0]):end=$(N $pieces[$i][1]),setpts=PTS-STARTPTS[p$i]"; $cat += "[p$i]"
    if ($i -gt 0) { $cuts += $t }
    $t += $pieces[$i][1] - $pieces[$i][0]
}
$total = $t
$f += "$($cat)concat=n=$($pieces.Count):v=1:a=0[v]"
$f += "[0:a]atrim=0:$(N $total),asetpts=PTS-STARTPTS,afade=t=out:st=$(N ($total - 0.8)):d=0.8[a]"
$cut = Join-Path $work ($raw.BaseName + '.mp4')
& $ff -v error -y -i $raw.FullName -filter_complex ($f -join ';') -map '[v]' -map '[a]' -c:v libx264 -preset fast -crf 12 -pix_fmt yuv420p -c:a aac -b:a 256k $cut
if ($LASTEXITCODE -ne 0) { throw "ffmpeg failed cutting $Build" }
# where it builds and cuts in the new timeline, for finish-short's block sounds and sweeps
$buildEnd = $total - $reveal
Set-Content -LiteralPath ([IO.Path]::ChangeExtension($cut, '.timing.txt')) -Encoding ascii -Value @(
    "build=$(N ($b0 - $open0)),$(N $buildEnd)", "cuts=$(($cuts | ForEach-Object { N $_ }) -join ',')")

# keep the long version beside it, then make the new final + phone copy
$long = Join-Path $dir '_long version'; New-Item -ItemType Directory -Force $long | Out-Null
foreach ($old in Get-ChildItem -LiteralPath $dir -Filter "$Build - final*.mp4") { Move-Item -LiteralPath $old.FullName (Join-Path $long $old.Name) -Force }
$row = Import-Csv 'C:\Users\Graham\Desktop\Shorts Thumbnails and Titles\titles.csv' | Where-Object { $_.Video -eq $raw.Name } | Select-Object -First 1
$final = Join-Path $dir "$Build - final.mp4"
& (Join-Path $PSScriptRoot 'finish-short.ps1') -In $cut -Out $final -Build $Build -Display "$($row.Build)" -Title "$($row.Title)"
& $ff -v error -y -i $final -c:v libx264 -preset slow -crf 21 -maxrate 5500k -bufsize 11000k -c:a aac -b:a 160k -movflags +faststart (Join-Path $dir "$Build - final (phone size).mp4")
if ($LASTEXITCODE -ne 0) { throw "phone copy failed for $Build" }
Remove-Item -LiteralPath $work -Recurse -Force
"$Build : $($pieces.Count) pieces, $(N ($total + $hook)) s"
