# Renders the owner's two approved cuts - B cinematic and C orbit, each with its own song matched to the build -
# for every take in the backlog below, then adds the MineSurvive outro copies (with-outro\). Cuts that already
# exist in Desktop\Shorts\<build>\ are skipped. Stop between takes: create bc-shorts.pause next to this script.
$ErrorActionPreference = 'Continue'
$here = $PSScriptRoot
$shorts = 'C:\Users\Graham\Desktop\Shorts'
$logf = Join-Path $here 'bc-shorts.log'
function Say($m) { $l = "{0}  {1}" -f (Get-Date -Format 'MM-dd HH:mm:ss'), $m; Add-Content $logf $l }
$spooky = @('haunt_muskie', 'middle_of_the_night_slowed')
$poke = @('royalty', 'heat_waves_slowed')
$calm = @('aria_math', 'runaway_slowed')
$hype = @('trap_royalty', 'colossus')
function Songs($b) {
    if ($b -match '(?i)coffin|bat_|ghost|skull|halloween|haunt|magma|dragon') { return $spooky }
    if ($b -match '(?i)ninetales|pikachu|squirtle|vaporeon|rayquaza|pokemon|bulbasaur|mimikyu') { return $poke }
    if ($b -match '(?i)construction|vehicle') { return $hype }
    return $calm
}
# Camera/timing keys per take (from build\queue-start-20260929\finish-then-edit\backlog-manifest.json, plus
# Mimikyu_House - its 09-29 early logs were lost; terraformEnd/buildEnd from the 02:03 progress line and the
# Done time - and the Dragon's Gate retry).
$manifest = Join-Path $here '..\..\build\queue-start-20260929\finish-then-edit\backlog-manifest.json'
$takes = New-Object System.Collections.Generic.List[object]
foreach ($t in (Get-Content -LiteralPath $manifest -Raw | ConvertFrom-Json).takes) {
    if ($t.common) { $takes.Add([pscustomobject]@{ build = $t.build; common = $t.common }) }
}
$takes.Add([pscustomobject]@{ build = 'Mimikyu_House'; common = 'replay=2026-09-29T05_23_05.zip centerX=-20537.5 centerZ=-25957 baseY=70 width=92 depth=93 height=79 terraformEnd=289400 buildEnd=524760 frontYaw=160' })
$takes.Add([pscustomobject]@{ build = 'Dragon_s_Gate_TIER_1_'; common = 'replay=2026-09-30T09_46_25.zip centerX=-24518 centerZ=-28866 baseY=64 width=23 depth=13 height=17 terraformEnd=640 buildEnd=4200 frontYaw=160' })

Say "starting: $($takes.Count) takes (B + C)"
foreach ($t in $takes) {
    if (Test-Path (Join-Path $here 'bc-shorts.pause')) { Say 'pause file found - stopping'; break }
    $s = Songs $t.build
    $jobs = @()
    foreach ($w in @(@('cinematic', 'B', $s[0]), @('orbit', 'C', $s[1]))) {
        $out = "$($t.build)-$($w[1])-$($w[0])-$($w[2]).mp4"
        if (Test-Path (Join-Path "$shorts\$($t.build)" $out)) { continue }
        $jobs += "style=$($w[0]) music=$($w[2])"
    }
    if ($jobs.Count) {
        Say "$($t.build): rendering $($jobs -join ' | ')"
        & (Join-Path $here 'render-take.ps1') -Build $t.build -Common $t.common -Jobs $jobs 2>&1 | ForEach-Object { Say "  $_" }
    } else { Say "$($t.build): B and C already there" }
    & (Join-Path $here 'add-outro-all.ps1') -Only $t.build 2>&1 | ForEach-Object { Say "  outro: $_" }
    Say "$($t.build): done"
}
Say 'all takes finished'
