# Lists the take lifecycle lines from every game log (gz + latest), oldest first.
$lg = "$env:APPDATA\PrismLauncher\instances\BuildRecording\minecraft\logs"
$pat = 'recording=true|natural build of|building \(\d of \d\)|terraforming: \w+ \(1 of|\] Done:|Stopped|render: (finished|music|exporting|failed)'
$files = Get-ChildItem $lg -Filter '*.log.gz' | Where-Object { $_.LastWriteTime -gt (Get-Date '2026-09-24') } | Sort-Object LastWriteTime
foreach ($f in $files) {
    $fs = [IO.File]::OpenRead($f.FullName); $gz = New-Object IO.Compression.GZipStream($fs, [IO.Compression.CompressionMode]::Decompress)
    $sr = New-Object IO.StreamReader($gz)
    "=== $($f.Name) (ends $($f.LastWriteTime))"
    while ($null -ne ($l = $sr.ReadLine())) { if ($l -match '\[StartBuild\]' -and $l -match $pat -and $l -notmatch 'CHAT') { $l.Substring(0, [Math]::Min(330, $l.Length)) } }
    $sr.Close()
}
"=== latest.log"
Get-Content "$lg\latest.log" | Where-Object { $_ -match '\[StartBuild\]' -and $_ -match $pat -and $_ -notmatch 'CHAT' } | ForEach-Object { $_.Substring(0, [Math]::Min(330, $_.Length)) }
