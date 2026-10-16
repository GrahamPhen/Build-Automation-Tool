# After the running take: hold the queue, back up the old cuts, and re-render three builds with the new
# front-aware camera (ninetales faces north, Mimikyu_House east, house_6_80 south) for review.
$here = $PSScriptRoot
$inst = "$env:APPDATA\PrismLauncher\instances\BuildRecording\minecraft"
$logf = Join-Path $here 'front-test.log'
function Say($m) { Add-Content $logf ("{0}  {1}" -f (Get-Date -Format 'MM-dd HH:mm:ss'), $m) }
$log = "$inst\logs\latest.log"
$start = (Get-Content $log).Count
Say 'waiting for the running take to end'
while ($true) {
    Start-Sleep 30
    $all = Get-Content $log
    if ($all.Count -lt $start) { $start = 0 }
    if ($all | Select-Object -Skip $start | Select-String '\[StartBuild\] (Done:|.*Stopped:)') { break }
}
$q = "$inst\config\startbuild-queue.txt"
if (Test-Path $q) { Move-Item $q "$inst\config\startbuild-queue.hold" -Force }
Say 'take ended; queue held'
Start-Sleep 45
$shorts = 'C:\Users\Graham\Desktop\Shorts'
$tests = @(
    @('ninetales', 'replay=2026-09-27T20_44_20.zip centerX=-22376 centerZ=-25684.5 baseY=77 width=75 depth=94 height=80 terraformEnd=50620 buildEnd=183854', @('style=cinematic music=royalty', 'style=orbit music=heat_waves_slowed')),
    @('Mimikyu_House', 'replay=2026-09-29T05_23_05.zip centerX=-20537.5 centerZ=-25957 baseY=70 width=92 depth=93 height=79 terraformEnd=289400 buildEnd=524760', @('style=orbit music=heat_waves_slowed')),
    @('house_6_80', 'replay=2026-09-26T03_17_41.zip centerX=-17313 centerZ=-20458 baseY=66 width=99 depth=105 height=80 terraformEnd=81200 buildEnd=330055', @('style=orbit music=runaway_slowed'))
)
foreach ($t in $tests) {
    $bak = Join-Path $shorts "_previous-versions\$($t[0])"
    New-Item -ItemType Directory -Force $bak | Out-Null
    Get-ChildItem (Join-Path $shorts $t[0]) -Filter *.mp4 -File | ForEach-Object { Copy-Item $_.FullName $bak -Force }
    Say "$($t[0]): rendering"
    & (Join-Path $here 'render-take.ps1') -Build $t[0] -Common $t[1] -Jobs $t[2] 2>&1 | ForEach-Object { Say "  $_" }
}
Say 'all tests finished'
