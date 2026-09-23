<#
    StartBuild stock datapack installer
    ===================================
    Datapacks live INSIDE each world, so a new world needs them copied in again (and a world that was
    deleted takes them with it). This lists the worlds that can actually accept a datapack - a folder
    without level.dat is not a loadable world - and copies the generated stock packs in.

    Usage:
        install-stock-datapack.ps1                              # just list worlds
        install-stock-datapack.ps1 -World "New World"           # install all stock packs
        install-stock-datapack.ps1 -World latest                # newest world
        install-stock-datapack.ps1 -World "New World" -Name haunted_80
#>
param(
    [string] $Instance = 'BuildRecording',
    [string] $World,
    [string] $Name,
    [string] $Source,
    [switch] $List
)

$ErrorActionPreference = 'Stop'

$gameDir = Join-Path $env:APPDATA "PrismLauncher\instances\$Instance\minecraft"
$sourceRoot = if ($Source) { $Source } else { Join-Path ([Environment]::GetFolderPath('Desktop')) 'litematic\_stock' }

Write-Host ''
Write-Host 'StartBuild stock datapack installer' -ForegroundColor White
Write-Host '----------------------------------' -ForegroundColor White

if (-not (Test-Path $gameDir)) {
    Write-Host "  instance not found: $gameDir" -ForegroundColor Red
    exit 1
}
if (-not (Test-Path $sourceRoot)) {
    Write-Host "  no stock packs at: $sourceRoot" -ForegroundColor Yellow
    Write-Host '  generate one first:  node tools\schematic-catalog.mjs --stock <schematic.litematic>'
    exit 1
}

$packs = @(Get-ChildItem $sourceRoot -Directory -ErrorAction SilentlyContinue |
           Where-Object { Test-Path (Join-Path $_.FullName 'pack.mcmeta') })
if ($Name) { $packs = @($packs | Where-Object { $_.Name -eq $Name }) }
if (-not $packs) {
    Write-Host "  no matching stock pack(s) in $sourceRoot" -ForegroundColor Yellow
    exit 1
}
Write-Host "  packs available: $($packs.Name -join ', ')"

$saves = Join-Path $gameDir 'saves'
$worlds = @(Get-ChildItem $saves -Directory -ErrorAction SilentlyContinue |
            Where-Object { Test-Path (Join-Path $_.FullName 'level.dat') })
$broken = @(Get-ChildItem $saves -Directory -ErrorAction SilentlyContinue |
            Where-Object { -not (Test-Path (Join-Path $_.FullName 'level.dat')) })

Write-Host ''
Write-Host 'Worlds that can accept datapacks:'
if ($worlds.Count -eq 0) {
    Write-Host '  (none)' -ForegroundColor Yellow
    Write-Host '  Create a world in game first (creative, cheats ON), then run this again.' -ForegroundColor Yellow
} else {
    foreach ($w in $worlds) { Write-Host ("  {0}   (modified {1:yyyy-MM-dd HH:mm})" -f $w.Name, $w.LastWriteTime) }
}
if ($broken.Count) {
    Write-Host ''
    Write-Host 'Folders that are NOT usable worlds (no level.dat - a deleted or half-created world):' -ForegroundColor Yellow
    foreach ($b in $broken) { Write-Host ("  {0}" -f $b.Name) }
}

if ($List -or -not $World) { exit 0 }
if ($worlds.Count -eq 0) { exit 1 }

$target = $null
if ($World -eq 'latest') {
    $target = $worlds | Sort-Object LastWriteTime -Descending | Select-Object -First 1
} else {
    $target = $worlds | Where-Object { $_.Name -eq $World } | Select-Object -First 1
}
if (-not $target) {
    Write-Host ''
    Write-Host "  world '$World' not found" -ForegroundColor Red
    exit 1
}

$datapacks = Join-Path $target.FullName 'datapacks'
New-Item -ItemType Directory -Force -Path $datapacks | Out-Null

Write-Host ''
foreach ($pack in $packs) {
    Copy-Item $pack.FullName -Destination $datapacks -Recurse -Force
    $functions = @(Get-ChildItem (Join-Path $datapacks "$($pack.Name)\data") -Recurse -File -Filter '*.mcfunction' -ErrorAction SilentlyContinue)
    Write-Host ("  installed {0}  ({1} function file(s))" -f $pack.Name, $functions.Count) -ForegroundColor Green
}

Write-Host ''
Write-Host "Copied $($packs.Count) pack(s) into world '$($target.Name)'."
Write-Host 'In game:   /reload'
Write-Host '           /function sb:<build>_1        (see materials.txt for the batch list)'
if (Get-Process javaw -ErrorAction SilentlyContinue) {
    Write-Host 'Minecraft is running, so /reload is required for it to notice.' -ForegroundColor Yellow
}
