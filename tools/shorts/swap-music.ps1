# Replaces the song in finished takes without re-rendering: the render's music track is swapped for another song
# (started where it gets going, levelled, faded out at the end), files / thumbnail / title row renamed to the new
# song, then the Short is finished again (hook, banner, sounds) with a phone copy. Ends with package-all.
#   swap-music.ps1 -From runaway_slowed [-Builds a,b,...]     (no -Builds: every build whose render uses -From)
# The new songs rotate through shorts-meta's $HouseSongs.
param([Parameter(Mandatory)][string] $From, [string[]] $Builds = @())
$ErrorActionPreference = 'Continue'
. (Join-Path $PSScriptRoot 'shorts-meta.ps1')
$Builds = @($Builds -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ })
$shorts = 'C:\Users\Graham\Desktop\Shorts'
$kit = 'C:\Users\Graham\Desktop\Shorts Thumbnails and Titles'
$csvPath = Join-Path $kit 'titles.csv'
$musicDir = "$env:APPDATA\PrismLauncher\instances\BuildRecording\minecraft\config\startbuild-music"
$bin = Split-Path (Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages" -Recurse -Filter ffmpeg.exe | Select-Object -First 1).FullName
$ff = Join-Path $bin 'ffmpeg.exe'; $fp = Join-Path $bin 'ffprobe.exe'
$inv = [Globalization.CultureInfo]::InvariantCulture
Add-Type -AssemblyName Microsoft.VisualBasic
function Recycle($p) { [Microsoft.VisualBasic.FileIO.FileSystem]::DeleteFile($p, 'OnlyErrorDialogs', 'SendToRecycleBin') }

$raws = Get-ChildItem $shorts -Directory | Where-Object { $_.Name -notlike '_*' -and (-not $Builds.Count -or $Builds -contains $_.Name) } | ForEach-Object {
    Get-ChildItem -LiteralPath $_.FullName -Filter "*-$From.mp4" | Where-Object { $_.Name -match '-(C-orbit|D-cuts|E-flyby)-' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1 }
foreach ($raw in $raws) {
    $b = $raw.Directory.Name
    $song = Get-NextHouseSong
    $songFile = Get-ChildItem $musicDir | Where-Object { $_.BaseName -eq $song } | Select-Object -First 1
    $newName = $raw.Name -replace ([regex]::Escape("-$From.mp4") + '$'), "-$song.mp4"
    $newRaw = Join-Path $raw.DirectoryName $newName
    $dur = [double]::Parse((& $fp -v error -show_entries format=duration -of csv=p=0 $raw.FullName).Trim(), $inv)
    $fadeAt = ($dur - 2).ToString('0.###', $inv)
    # skip a quiet intro, cut to length, even loudness, fade out over the last 2 s (as the renderer's mixer does)
    & $ff -v error -y -i $raw.FullName -i $songFile.FullName -filter_complex "[1:a]silenceremove=start_periods=1:start_threshold=-32dB,atrim=duration=$($dur.ToString('0.###', $inv)),loudnorm=I=-14:TP=-1.5:LRA=11,aresample=48000,afade=t=in:d=0.25,afade=t=out:st=$($fadeAt):d=2[a]" `
        -map 0:v -map '[a]' -c:v copy -c:a aac -b:a 192k -movflags +faststart $newRaw
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $newRaw)) { "${b}: music swap FAILED"; continue }
    $tm = [IO.Path]::ChangeExtension($raw.FullName, '.timing.txt')
    if (Test-Path -LiteralPath $tm) { Move-Item -LiteralPath $tm ([IO.Path]::ChangeExtension($newRaw, '.timing.txt')) -Force }
    $oldJpg = Join-Path $kit ($raw.BaseName + '.jpeg')
    if (Test-Path -LiteralPath $oldJpg) { Move-Item -LiteralPath $oldJpg (Join-Path $kit ([IO.Path]::GetFileNameWithoutExtension($newName) + '.jpeg')) -Force }
    $rows = Import-Csv $csvPath; foreach ($r in $rows) { if ($r.Video -eq $raw.Name) { $r.Video = $newName } }; $rows | Export-Csv -NoTypeInformation -Encoding UTF8 $csvPath
    Recycle $raw.FullName
    $row = $rows | Where-Object { $_.Video -eq $newName } | Select-Object -First 1
    $final = Join-Path $raw.DirectoryName "$b - final.mp4"
    try { & (Join-Path $PSScriptRoot 'finish-short.ps1') -In $newRaw -Out $final -Build $b -Display "$($row.Build)" -Title "$($row.Title)" | Out-Null } catch { "${b}: finish FAILED - $_"; continue }
    & $ff -v error -y -i $final -c:v libx264 -preset slow -crf 21 -maxrate 5500k -bufsize 11000k -c:a aac -b:a 160k -movflags +faststart (Join-Path $raw.DirectoryName "$b - final (phone size).mp4")
    "$b -> $song"
}
& (Join-Path $PSScriptRoot 'package-all.ps1') | Select-Object -Last 1
