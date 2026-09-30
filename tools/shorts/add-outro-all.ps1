# Adds the MineSurvive outro to every Short under Desktop\Shorts that does not have one yet.
# Originals are left alone; each build folder gets a with-outro\ subfolder holding the new versions.
#   add-outro-all.ps1                 # every build
#   add-outro-all.ps1 -Only charmander_80,ninetales
param([string] $Root = 'C:\Users\Graham\Desktop\Shorts', [string[]] $Only = @())
$one = Join-Path $PSScriptRoot 'add-outro.ps1'
$done = 0; $failed = @()
foreach ($d in Get-ChildItem $Root -Directory) {
    if ($Only.Count -and $Only -notcontains $d.Name) { continue }
    $dest = Join-Path $d.FullName 'with-outro'
    foreach ($v in Get-ChildItem $d.FullName -Filter *.mp4 -File) {
        $out = Join-Path $dest $v.Name
        if (Test-Path $out) { continue }
        New-Item -ItemType Directory -Force $dest | Out-Null
        try { & $one -In $v.FullName -Out $out; $done++; "ok   $($d.Name)\$($v.Name)" }
        catch { $failed += $v.FullName; "FAIL $($d.Name)\$($v.Name): $_" }
    }
}
"added the outro to $done video(s); failed: $($failed.Count)"
