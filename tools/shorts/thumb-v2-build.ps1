# The approved thumbnail style (owner, 2026-10-29: "B with the name") for one build's current Short: a full-frame
# shot of the finished build near the end of the reveal, cropped in a little, the build's name where it covers
# the least, small logo. Replaces "<video>.jpeg" in the thumbnails folder (the old ones are in _originals ...).
#   thumb-v2-build.ps1 -Build <name>
param([Parameter(Mandatory)][string] $Build)
$ErrorActionPreference = 'Stop'
$kit = 'C:\Users\Graham\Desktop\Shorts Thumbnails and Titles'
$dir = "C:\Users\Graham\Desktop\Shorts\$Build"
# the video the final was made from: the newest orbit / snap-cut / fly-by render without a -sNN test suffix
$raw = Get-ChildItem -LiteralPath $dir -Filter "$Build-*.mp4" | Where-Object { $_.Name -match '-(C-orbit|D-cuts|E-flyby)-' -and $_.Name -notmatch '-s\d+\.mp4$' } |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $raw) { "no render for $Build"; return }
$row = Import-Csv (Join-Path $kit 'titles.csv') | Where-Object { $_.Video -eq $raw.Name } | Select-Object -First 1
$name = if ($row -and $row.Build) { $row.Build } else { (Get-Culture).TextInfo.ToTitleCase(($Build -replace '_(TIER|tier)_\d+_?$', '' -replace '[_+]', ' ').Trim()) }
# hand-picked frames (the pilots): seconds (negative = from the end), zoom, focus, where the name goes
$manual = @{
    'Articuno_3'                    = @{ Zoom = 1.0; TextY = 0.88 }                            # the crest is at the top
    'Grand_Ocean_Liner'             = @{ At = 48; Zoom = 1.3; FocusX = 0.53; FocusY = 0.63; TextY = 0.085; Text = 'Ocean Liner' }
}
# default: 2.5 s before the end - the reveal is nearly round to the front, before the evening light turns orange
$p = @{ At = -2.5; Zoom = 1.12; FocusY = 0.6; TextY = -1; Text = $name }
if ($manual.ContainsKey($Build)) { foreach ($k in $manual[$Build].Keys) { $p[$k] = $manual[$Build][$k] } }
& (Join-Path $PSScriptRoot 'thumb-v2.ps1') -Video $raw.FullName -Out (Join-Path $kit ($raw.BaseName + '.jpeg')) -Logo @p
