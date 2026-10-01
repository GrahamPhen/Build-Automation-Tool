# B cinematic + C orbit Shorts (+ outro copies + thumbnails/titles) for the four rebuilds of 2026-09-30/10-01.
# Keys from the takes' log lines (recording=true / building (N of N) / last block placed) and the schematics'
# non-air boxes (tools\shorts\extent.mjs).
$here = $PSScriptRoot
$logf = Join-Path $here 'rebuild-shorts.log'
function Say($m) { Add-Content $logf ("{0}  {1}" -f (Get-Date -Format 'MM-dd HH:mm:ss'), $m) }
$takes = @(
    @('Farmer_House_TIER_1_', 'replay=2026-10-01T01_07_35.zip centerX=-21865 centerZ=-33405 baseY=94 width=23 depth=17 height=19 terraformEnd=7240 buildEnd=14940 frontYaw=160', 'aria_math', 'runaway_slowed'),
    @('Dragon_s_Gate_TIER_1_', 'replay=2026-09-30T18_11_22.zip centerX=-33565 centerZ=-31606 baseY=64 width=23 depth=13 height=17 terraformEnd=300 buildEnd=8700 frontYaw=160', 'haunt_muskie', 'middle_of_the_night_slowed'),
    @('house_enchanted', 'replay=2026-09-30T20_31_24.zip centerX=-20459.5 centerZ=-34679.5 baseY=74 width=64 depth=66 height=43 terraformEnd=69500 buildEnd=146560 frontYaw=160', 'aria_math', 'runaway_slowed'),
    @('Grim_Reaper', 'replay=2026-10-01T00_53_44.zip centerX=-21504 centerZ=-33728 baseY=144 width=95 depth=127 height=170 terraformEnd=49900 buildEnd=312880 frontYaw=160', 'haunt_muskie', 'middle_of_the_night_slowed')
)
Say "starting $($takes.Count) rebuilds"
foreach ($t in $takes) {
    Say "$($t[0]): rendering"
    & (Join-Path $here 'render-take.ps1') -Build $t[0] -Common $t[1] -Jobs "style=cinematic music=$($t[2])", "style=orbit music=$($t[3])" 2>&1 | ForEach-Object { Say "  $_" }
    & (Join-Path $here 'add-outro-all.ps1') -Only $t[0] 2>&1 | ForEach-Object { Say "  outro: $_" }
    Say "$($t[0]): done"
}
& (Join-Path $here 'make-thumbnails.ps1') 2>&1 | ForEach-Object { Say "  thumbs: $_" }
Say 'all takes finished'
