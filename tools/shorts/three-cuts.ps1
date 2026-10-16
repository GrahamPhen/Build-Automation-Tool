# Claude's edit pass: for every READY take in the backlog manifest, render three different versions
# (A tripod, B cinematic, C orbit), each with its own song matched to the build's theme, then the outro copies.
# Existing exports are never overwritten. Stop between takes by creating three-cuts.pause next to this script.
$ErrorActionPreference = 'Continue'
$here = $PSScriptRoot
$manifest = 'C:\Users\Graham\Codex\Build-Automation-Tool\build\queue-start-20260929\finish-then-edit\backlog-manifest.json'
$exports = "$env:APPDATA\PrismLauncher\instances\BuildRecording\minecraft\flashback\exports"
$logf = Join-Path $here 'three-cuts.log'
function Say($m) { $l = "{0}  {1}" -f (Get-Date -Format 'HH:mm:ss'), $m; Add-Content $logf $l }
$spooky = @('haunt_muskie', 'middle_of_the_night_slowed', 'colossus')
$poke   = @('royalty', 'heat_waves_slowed', 'biome_fest')
$calm   = @('aria_math', 'runaway_slowed', 'taswell')
$hype   = @('trap_royalty', 'colossus', 'royalty')
function Songs($b) {
  if ($b -match '(?i)coffin|bat_|ghost|skull|halloween|haunt|magma') { return $spooky }
  if ($b -match '(?i)ninetales|pikachu|squirtle|vaporeon|rayquaza|pokemon|bulbasaur|mimikyu') { return $poke }
  if ($b -match '(?i)construction|vehicle') { return $hype }
  return $calm
}
$m = Get-Content -LiteralPath $manifest -Raw | ConvertFrom-Json
$takes = @($m.takes | Where-Object { $_.status -eq 'READY_AFTER_CURRENT_DONE' })
Say "starting: $($takes.Count) takes"
foreach ($t in $takes) {
  if (Test-Path (Join-Path $here 'three-cuts.pause')) { Say 'pause file found - stopping'; break }
  $s = Songs $t.build
  $want = @(@('tripod', 'A', $s[2]), @('cinematic', 'B', $s[0]), @('orbit', 'C', $s[1]))
  $jobs = @()
  foreach ($w in $want) {
    $out = "$($t.build)-$($w[1])-$($w[0])-$($w[2]).mp4"
    if (Test-Path (Join-Path $exports $out)) { Say "  exists, skipping $out"; continue }
    $jobs += "style=$($w[0]) music=$($w[2])"
  }
  if (-not $jobs.Count) { Say "$($t.build): all three already done"; continue }
  Say "$($t.build): rendering $($jobs -join ' | ')"
  & (Join-Path $here 'render-take.ps1') -Build $t.build -Common $t.common -Jobs $jobs 2>&1 | ForEach-Object { Say "  $_" }
  & (Join-Path $here 'add-outro-all.ps1') -Only $t.build 2>&1 | ForEach-Object { Say "  outro: $_" }
  Say "$($t.build): done"
}
Say 'all takes finished'
