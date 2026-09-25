<#
    StartBuild launcher
    ==================
    One double-click: makes sure the build-recording Prism instance exists with the right mod set,
    then launches that instance through the Prism command line.

    Mod set it guarantees (Minecraft 26.2, Fabric Loader 0.19.5):
        Fabric API, Flashback, MaLiLib, Litematica, Sodium, Iris, StartBuild
    Also guarantees:
        - a hash-verified shader pack in the instance's shaderpacks\ folder (Iris does the selecting;
          see the README for when shaders should be on)
    Version policy:
        - Fabric API and Flashback are only installed if the instance has none; an existing
          version is left alone.
        - The others are pinned and replaced if a different version is present, because a
          mismatched pair (e.g. two Litematica jars) silently breaks the build.
        - Baritone is no longer used (2.x places every block itself). An existing Baritone jar is
          left where it is; it is simply not required or installed any more.

    Safety rules:
        - Never deletes a mod. Superseded jars of the mods above go to mods\_superseded-<timestamp>\.
        - Downloads are cached and hash-verified, so re-runs are fast and offline-safe.

    Usage:
        startbuild-launch.ps1                        # default instance, then launch
        startbuild-launch.ps1 -NoLaunch              # set up only, do not launch
        startbuild-launch.ps1 -Instance OtherName
        startbuild-launch.ps1 -Force                 # re-copy everything from cache
        startbuild-launch.ps1 -Shaders On            # also make Iris load the shader pack
        startbuild-launch.ps1 -NoShaderpack          # mods only
        startbuild-launch.ps1 -Build haunted_80 -Wish 'near a lake'
                                                     # hands-free: open -World, find a site, build, save
#>
[CmdletBinding()]
param(
    [string] $Instance = 'BuildRecording',
    [switch] $NoLaunch,
    [switch] $Force,
    [switch] $NoShaderpack,
    [ValidateSet('Keep', 'On', 'Off')] [string] $Shaders = 'Keep',
    # One double-click = open the world and build this schematic by itself, recorded by Flashback.
    [string] $Build = '',   # set (e.g. -Build haunted_80) to open the world and build hands-free
    [string] $Wish = '',    # optional site wish for -Build (e.g. 'near a lake'), written after the name
    [string] $World = 'Video Building',
    [switch] $NoAutoBuild
)

$ErrorActionPreference = 'Stop'

$PrismRoot = Join-Path $env:APPDATA 'PrismLauncher'
$PrismExe  = Join-Path $env:LOCALAPPDATA 'Programs\PrismLauncher\prismlauncher.exe'
$CacheDir  = Join-Path $env:LOCALAPPDATA 'StartBuildCache\mods'
$LogFile   = Join-Path (Split-Path $CacheDir -Parent) 'launcher-log.txt'

$RepoRoot  = Split-Path $PSScriptRoot -Parent
# This script runs both from this repo (Build-Automation-Tool\tools) and from the desktop-icon copy
# (MineSurvive\tools); both repos sit side by side under the same Codex folder.
$CodexRoot    = Split-Path $RepoRoot -Parent
$RepoBuildDir = Join-Path $CodexRoot 'Build-Automation-Tool\build\libs'
$BuildDir     = Join-Path $CodexRoot 'MineSurvive\staging\flashback-startbuild-20260922\build\libs'
# Discover the highest-versioned build output instead of pinning a filename and hash here, so
# rebuilding the mod never requires editing this script. The version comes from the file name and
# deliberately beats LastWriteTime: an older jar touched later (re-downloaded, copied, restored from a
# backup) would otherwise silently count as the "newest" and get installed instead.
# The repo build is the normal source; the old staging copy is still honoured. Highest version wins.
$BuildCandidates = Get-ChildItem @($RepoBuildDir, $BuildDir) -Filter 'startbuild-*.jar' -ErrorAction SilentlyContinue | ForEach-Object {
    $v = [version]'0.0.0'
    if ($_.Name -match '^startbuild-(\d+\.\d+\.\d+)\.jar$') { $v = [version]$Matches[1] }
    [pscustomobject]@{ File = $_; Version = $v }
}
$BuiltPick = $BuildCandidates |
             Sort-Object -Property @{ Expression = 'Version'; Descending = $true },
                                   @{ Expression = { $_.File.LastWriteTime }; Descending = $true } |
             Select-Object -First 1
$BuiltJar  = if ($BuiltPick) { $BuiltPick.File } else { $null }
$BuiltSha  = if ($BuiltJar) { (Get-FileHash $BuiltJar.FullName -Algorithm SHA256).Hash.ToLower() } else { '' }
$BuiltName = if ($BuiltJar) { $BuiltJar.Name } else { 'startbuild.jar' }
$BuiltSize = if ($BuiltJar) { $BuiltJar.Length } else { 0 }

# ---------------------------------------------------------------- pinned mod set
# Policy 'PreferExisting' = keep whatever version is already installed.
# Policy 'Pinned'         = this exact file must be present, or it is installed.
$Mods = @(
    [pscustomobject]@{
        Label   = 'Fabric API'
        Policy  = 'PreferExisting'
        Pattern = 'fabric-api-*.jar'
        File    = 'fabric-api-0.161.0+26.2.jar'
        Url     = 'https://cdn.modrinth.com/data/P7dR8mSH/versions/ewUK83HI/fabric-api-0.161.0%2B26.2.jar'
        Algo    = 'SHA512'
        Hash    = '2502fa5ade78e9a120b3747bc1a9a2167d6671743bea45bf115d3174c94121aeda87c549446d1a604a9c11fb828de17a551e3514ce42f3e93069dd14bdaee55b'
        Bytes   = 2566123
        Why     = 'required by every other mod'
    }
    [pscustomobject]@{
        Label   = 'Flashback'
        Policy  = 'PreferExisting'
        Pattern = 'Flashback-*.jar'
        File    = 'Flashback-0.43.4-for-MC26.2.jar'
        Url     = 'https://cdn.modrinth.com/data/4das1Fjq/versions/h6FH7iAC/Flashback-0.43.4-for-MC26.2.jar'
        Algo    = 'SHA512'
        Hash    = 'b650eac3500ae27f523e614eff8caf7b74e97f231462734c65240da5f943ace1e3297ee7818013a1ba283c7b1e69aceccbe6fa0029b60bee84b47c877432d419'
        Bytes   = 202237995
        Why     = 'records the build and edits the camera'
    }
    [pscustomobject]@{
        Label   = 'Sodium'
        Policy  = 'Pinned'
        Pattern = 'sodium-*.jar'
        File    = 'sodium-fabric-0.9.2+mc26.2.jar'
        Url     = 'https://cdn.modrinth.com/data/AANobbMI/versions/xJZxADzI/sodium-fabric-0.9.2%2Bmc26.2.jar'
        Algo    = 'SHA512'
        Hash    = '9b7a7aade8824543ce2a804fddfd04e37f57d1f91c00574e7dc87e554eb1835d888313ee6f715a7599bd729840cf01fd74e02aeae87b4e5a3c10c34a016e407c'
        Bytes   = 1885572
        Why     = 'renderer - required by Iris, and helps recording performance'
    }
    [pscustomobject]@{
        Label   = 'Iris'
        Policy  = 'Pinned'
        Pattern = 'iris-*.jar'
        File    = 'iris-fabric-1.11.4+mc26.2.jar'
        Url     = 'https://cdn.modrinth.com/data/YL57xq9U/versions/gxZWWnKH/iris-fabric-1.11.4%2Bmc26.2.jar'
        Algo    = 'SHA512'
        Hash    = 'dee955580976aebc92373f5c2d88398ad1019b043c6254a41fe7ab8a6ca3a5ff29d58df2b22ec43a31198beb769e814658eef06fbf980066a2e2c216eeafd32a'
        Bytes   = 2821616
        Why     = 'shaders - for the replay and export, not while building (see README)'
    }
    [pscustomobject]@{
        Label   = 'MaLiLib'
        Policy  = 'Pinned'
        Pattern = 'malilib-*.jar'
        File    = 'malilib-fabric-26.2-0.29.6.jar'
        Url     = 'https://cdn.modrinth.com/data/GcWjdA9I/versions/KvjmGjAV/malilib-fabric-26.2-0.29.6.jar'
        Algo    = 'SHA512'
        Hash    = '0fab398f835d9c4736dac65510f707c77a60be6e6051bfd008225c20a5ff13e146296da258bee551d39fa28102b8854d77fa25b2ecb6b0f89fb3c874bac563db'
        Bytes   = 2037195
        Why     = 'required by Litematica'
    }
    [pscustomobject]@{
        Label   = 'Litematica'
        Policy  = 'Pinned'
        Pattern = 'litematica-*.jar'
        File    = 'litematica-fabric-26.2-0.28.8.jar'
        Url     = 'https://cdn.modrinth.com/data/bEpr0Arc/versions/CuniXtbo/litematica-fabric-26.2-0.28.8.jar'
        Algo    = 'SHA512'
        Hash    = 'd1c80538f3311b585c9311e59f14fd7fd3eb82288fe5afb399b2139cb35b42d24db7203379f2f61b50a610736335a86dbd395ca1795b001622e6b24dc6fe2a67'
        Bytes   = 1929569
        Why     = 'holds the schematic the build follows'
    }
    [pscustomobject]@{
        Label   = 'StartBuild'
        Policy  = 'Pinned'
        Pattern = 'startbuild-*.jar'
        File    = $BuiltName
        Url     = ''
        Algo    = 'SHA256'
        Hash    = $BuiltSha
        Bytes   = $BuiltSize
        Why     = 'the builder itself (/findsite, /startbuild)'
    }
)

# ---------------------------------------------------------------- shader pack
# Not a mod, so it is kept separately: it lives in the instance's shaderpacks\ folder and Iris owns
# the selection in config\iris.properties. Same cache-and-verify treatment as the mods, so the pack
# can always be restored. Complementary Reimagined is the default because it is free, widely used
# for showcase footage, and publishes explicit support for this Minecraft version.
$ShaderCache = Join-Path (Split-Path $CacheDir -Parent) 'shaderpacks'
$ShaderPack  = [pscustomobject]@{
    Label = 'Complementary Reimagined'
    File  = 'ComplementaryReimagined_r5.9.3.zip'
    Url   = 'https://cdn.modrinth.com/data/HVnmMxH1/versions/Bqen1mJX/ComplementaryReimagined_r5.9.3.zip'
    Algo  = 'SHA1'
    Hash  = '838139b54cddb56b2e83cd260d8efd960ac536d6'
    Bytes = 553397
}

# ---------------------------------------------------------------- helpers
function Write-Log {
    param([string] $Message)
    $line = '{0}  {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Message
    try {
        $dir = Split-Path $LogFile -Parent
        if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
        Add-Content -Path $LogFile -Value $line
    } catch { }
}

function Write-Step {
    param([string] $Message)
    Write-Host ''
    Write-Host "== $Message" -ForegroundColor Cyan
    Write-Log "STEP $Message"
}

function Write-Ok   { param([string] $m) Write-Host "   [ok]   $m" -ForegroundColor Green;  Write-Log "OK   $m" }
function Write-Note { param([string] $m) Write-Host "   [note] $m" -ForegroundColor Yellow; Write-Log "NOTE $m" }
function Write-Bad  { param([string] $m) Write-Host "   [FAIL] $m" -ForegroundColor Red;    Write-Log "FAIL $m" }

function Test-Hash {
    param([string] $Path, [string] $Algorithm, [string] $Expected)
    if (-not (Test-Path $Path)) { return $false }
    try { return ((Get-FileHash -Path $Path -Algorithm $Algorithm).Hash -ieq $Expected) } catch { return $false }
}

function Move-Aside {
    param([string] $Path, [string] $IntoDir, [System.Collections.Generic.List[string]] $Collector)
    if (-not (Test-Path $IntoDir)) { New-Item -ItemType Directory -Force -Path $IntoDir | Out-Null }
    $leaf = Split-Path $Path -Leaf
    try {
        Move-Item -Path $Path -Destination (Join-Path $IntoDir $leaf) -Force -ErrorAction Stop
    } catch {
        # Almost always: Minecraft is running and has the jar open.
        Write-Bad "cannot replace $leaf - something has it open, which normally means the game is running."
        Write-Host '   Close Minecraft completely, then run this again.' -ForegroundColor Yellow
        exit 1
    }
    $Collector.Add($leaf)
    Write-Note "moved aside $leaf (kept, not deleted)"
}

function Get-RemoteFile {
    param([string] $Url, [string] $Destination, [string] $Algo, [string] $Hash, [long] $Bytes)

    if (Test-Hash -Path $Destination -Algorithm $Algo -Expected $Hash) { return $true }

    $dir = Split-Path $Destination -Parent
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    if (Test-Path $Destination) { Remove-Item $Destination -Force -ErrorAction SilentlyContinue }

    Write-Host ("   downloading {0} ({1} MB) ..." -f (Split-Path $Destination -Leaf), [math]::Round($Bytes / 1MB, 1))
    $curl = Get-Command 'curl.exe' -ErrorAction SilentlyContinue
    if ($curl) {
        & $curl.Source '-L' '--fail' '--silent' '--show-error' '-o' $Destination $Url
    } else {
        try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 } catch { }
        Invoke-WebRequest -Uri $Url -OutFile $Destination -UseBasicParsing
    }

    if (-not (Test-Path $Destination)) { return $false }
    return (Test-Hash -Path $Destination -Algorithm $Algo -Expected $Hash)
}

function New-PrismInstance {
    param([string] $InstanceDirPath, [string] $DisplayName)

    New-Item -ItemType Directory -Force -Path (Join-Path $InstanceDirPath 'minecraft\mods') | Out-Null

    $mmcPack = @'
{
    "components": [
        {
            "cachedName": "LWJGL 3",
            "cachedVersion": "3.4.1",
            "dependencyOnly": true,
            "uid": "org.lwjgl3",
            "version": "3.4.1"
        },
        {
            "cachedName": "Minecraft",
            "cachedRequires": [
                {
                    "suggests": "3.4.1",
                    "uid": "org.lwjgl3"
                }
            ],
            "cachedVersion": "26.2",
            "important": true,
            "uid": "net.minecraft",
            "version": "26.2"
        },
        {
            "cachedName": "Intermediary Mappings",
            "cachedRequires": [
                {
                    "equals": "26.2",
                    "uid": "net.minecraft"
                }
            ],
            "cachedVersion": "26.2",
            "dependencyOnly": true,
            "uid": "net.fabricmc.intermediary",
            "version": "26.2"
        },
        {
            "cachedName": "Fabric Loader",
            "cachedRequires": [
                {
                    "uid": "net.fabricmc.intermediary"
                }
            ],
            "cachedVersion": "0.19.5",
            "uid": "net.fabricmc.fabric-loader",
            "version": "0.19.5"
        }
    ],
    "formatVersion": 1
}
'@
    Set-Content -Path (Join-Path $InstanceDirPath 'mmc-pack.json') -Value $mmcPack -Encoding ASCII

    $cfg = New-Object System.Collections.Generic.List[string]
    $cfg.Add('[General]')
    $cfg.Add('ConfigVersion=1.3')
    $cfg.Add('InstanceType=OneSix')
    $cfg.Add("name=$DisplayName")
    $cfg.Add('iconKey=default')
    $cfg.Add('OverrideMemory=true')
    $cfg.Add('MinMemAlloc=2048')
    $cfg.Add('MaxMemAlloc=4096')
    $cfg.Add('JoinServerOnLaunch=false')
    $cfg.Add('LogPrePostOutput=true')
    $cfg.Add('LowMemWarning=false')
    $cfg.Add('OverrideCommands=false')
    $cfg.Add('OverrideConsole=false')
    $cfg.Add('OverrideJavaArgs=false')
    $cfg.Add('OverrideWindow=false')

    # Reuse Graham's known-good bundled Java runtime when it is present, else let Prism choose.
    $java = Join-Path $PrismRoot 'java\java-runtime-epsilon\bin\javaw.exe'
    if (Test-Path $java) {
        $cfg.Add('OverrideJavaLocation=true')
        $cfg.Add('JavaPath=' + ($java -replace '\\', '/'))
        $cfg.Add('AutomaticJava=false')
        $cfg.Add('JavaArchitecture=64')
        $cfg.Add('JavaRealArchitecture=amd64')
        $cfg.Add('JavaVendor=Microsoft')
        $cfg.Add('JavaVersion=25.0.1')
    } else {
        $cfg.Add('AutomaticJava=true')
        $cfg.Add('OverrideJavaLocation=false')
    }

    $cfg.Add('uuid=' + ([guid]::NewGuid().ToString('N')))
    Set-Content -Path (Join-Path $InstanceDirPath 'instance.cfg') -Value $cfg -Encoding ASCII
}

# ---------------------------------------------------------------- 1. preflight
Write-Host ''
Write-Host 'StartBuild launcher' -ForegroundColor White
Write-Host '------------------' -ForegroundColor White
Write-Log '=== launcher run ==='

Write-Step 'Checking Prism Launcher'

if (-not (Test-Path $PrismExe)) {
    Write-Bad "Prism Launcher not found at $PrismExe"
    Write-Host '   Install Prism Launcher, or edit $PrismExe at the top of this script.'
    exit 1
}
Write-Ok 'Prism Launcher found'

$running = Get-Process javaw -ErrorAction SilentlyContinue
if ($running) {
    Write-Note "Minecraft appears to be running (javaw pid $($running.Id -join ', '))."
    Write-Host '   Mods cannot be replaced while the game holds them open. If this run needs to update' -ForegroundColor Yellow
    Write-Host '   anything, close the game first - otherwise it will stop with a clear message.' -ForegroundColor Yellow
}

Write-Step "Checking instance '$Instance'"

$InstanceDir = Join-Path (Join-Path $PrismRoot 'instances') $Instance
if (-not (Test-Path $InstanceDir)) {
    Write-Note "instance '$Instance' does not exist yet - creating it (Minecraft 26.2 + Fabric Loader 0.19.5)"
    New-PrismInstance -InstanceDirPath $InstanceDir -DisplayName $Instance
    if (-not (Test-Path (Join-Path $InstanceDir 'instance.cfg'))) {
        Write-Bad 'could not create the instance'
        exit 1
    }
    Write-Ok "created $InstanceDir"
} else {
    Write-Ok "instance found: $InstanceDir"
}

# Prism instance game folders have been seen as both 'minecraft' and '.minecraft'.
$GameDir = $null
foreach ($candidate in @('minecraft', '.minecraft')) {
    $probe = Join-Path $InstanceDir $candidate
    if (Test-Path $probe) { $GameDir = $probe; break }
}
if (-not $GameDir) {
    $GameDir = Join-Path $InstanceDir 'minecraft'
    New-Item -ItemType Directory -Force -Path $GameDir | Out-Null
    Write-Note "created game folder $GameDir"
}

$ModsDir = Join-Path $GameDir 'mods'
if (-not (Test-Path $ModsDir)) {
    New-Item -ItemType Directory -Force -Path $ModsDir | Out-Null
    Write-Note "created $ModsDir"
}
Write-Ok "mods folder: $ModsDir"

# ---------------------------------------------------------------- 2. mods
Write-Step 'Installing / verifying the mod set'

$existing = @(Get-ChildItem $ModsDir -File -Filter '*.jar' -ErrorAction SilentlyContinue)
Write-Host "   $($existing.Count) jar(s) already in the mods folder"

$supersededDir = Join-Path $ModsDir ('_superseded-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
$superseded = New-Object System.Collections.Generic.List[string]

foreach ($mod in $Mods) {
    $target = Join-Path $ModsDir $mod.File
    $matching = @($existing | Where-Object { $_.Name -like $mod.Pattern })

    if ($mod.Policy -eq 'PreferExisting' -and $matching.Count -gt 0 -and -not $Force) {
        Write-Ok "$($mod.Label) already present: $(($matching | ForEach-Object { $_.Name }) -join ', ') (left as-is)"
        continue
    }

    if ((-not $Force) -and (Test-Hash -Path $target -Algorithm $mod.Algo -Expected $mod.Hash)) {
        Write-Ok "$($mod.Label) installed and hash-verified"
        continue
    }

    # Any other jar for this mod would clash with the one we are about to install.
    foreach ($other in ($matching | Where-Object { $_.Name -ne $mod.File })) {
        Move-Aside -Path $other.FullName -IntoDir $supersededDir -Collector $superseded
    }
    if ((-not $Force) -and (Test-Path $target) -and -not (Test-Hash -Path $target -Algorithm $mod.Algo -Expected $mod.Hash)) {
        Move-Aside -Path $target -IntoDir $supersededDir -Collector $superseded
    }

    if ($mod.Label -eq 'StartBuild') {
        if (-not $BuiltJar) {
            Write-Bad 'no startbuild-*.jar build output found in either build folder:'
            Write-Host "   $RepoBuildDir"
            Write-Host "   $BuildDir"
            Write-Host '   Build it from the Build-Automation-Tool repo:  .\gradlew.bat build'
            exit 1
        }
        Copy-Item -Path $BuiltJar.FullName -Destination $target -Force
        Write-Ok "$($mod.Label) installed from the repo build ($($BuiltJar.Name))"
    } else {
        $cached = Join-Path $CacheDir $mod.File
        if (-not (Get-RemoteFile -Url $mod.Url -Destination $cached -Algo $mod.Algo -Hash $mod.Hash -Bytes $mod.Bytes)) {
            Write-Bad "$($mod.Label): download failed or hash mismatch"
            Write-Host "   $($mod.Url)"
            exit 1
        }
        Copy-Item -Path $cached -Destination $target -Force
        Write-Ok "$($mod.Label) installed - $($mod.Why)"
    }
}

if ($superseded.Count -gt 0) {
    Write-Host ''
    Write-Host "   Moved aside (kept): $($superseded -join ', ')" -ForegroundColor Yellow
    Write-Host "   Folder: $supersededDir" -ForegroundColor Yellow
}

# Tidy up: remove superseded folders that ended up empty (e.g. from a failed replacement).
$emptied = 0
Get-ChildItem $ModsDir -Directory -Filter '_superseded-*' -ErrorAction SilentlyContinue | ForEach-Object {
    if (-not (Get-ChildItem $_.FullName -Recurse -File -ErrorAction SilentlyContinue)) {
        Remove-Item $_.FullName -Recurse -Force -ErrorAction SilentlyContinue
        $emptied++
    }
}
if ($emptied -gt 0) { Write-Ok "removed $emptied empty _superseded folder(s)" }

# ---------------------------------------------------------------- 3. summary
Write-Step 'Final mod set'
Get-ChildItem $ModsDir -File -Filter '*.jar' | Sort-Object Name | ForEach-Object {
    Write-Host ("   {0,12:N0}  {1}" -f $_.Length, $_.Name)
}
if ($BuildCandidates.Count -gt 1) {
    $skipped = $BuildCandidates | Where-Object { $_.File.Name -ne $BuiltJar.Name } | ForEach-Object { $_.File.Name }
    Write-Note ("other build outputs ignored: {0}" -f ($skipped -join ', '))
}

# ---------------------------------------------------------------- 3b. shader pack
# The shader pack is what makes the exported video look like a real build. Iris reads shaderpacks\
# from the instance and owns config\iris.properties, so this step only guarantees the zip is present
# and hash-correct, then reports (or optionally sets) which pack Iris loads at startup. Skip with
# -NoShaderpack.
if (-not $NoShaderpack) {
    Write-Step 'Ensuring the shader pack'

    $ShaderPackDir = Join-Path $GameDir 'shaderpacks'
    $cacheFile     = Join-Path $ShaderCache $ShaderPack.File
    $instFile      = Join-Path $ShaderPackDir $ShaderPack.File

    if (Get-RemoteFile -Url $ShaderPack.Url -Destination $cacheFile -Algo $ShaderPack.Algo -Hash $ShaderPack.Hash -Bytes $ShaderPack.Bytes) {
        if (-not (Test-Hash -Path $instFile -Algorithm $ShaderPack.Algo -Expected $ShaderPack.Hash)) {
            New-Item -ItemType Directory -Force -Path $ShaderPackDir | Out-Null
            Copy-Item $cacheFile -Destination $instFile -Force
        }
        Write-Ok ("{0} ready: {1} (hash-verified)" -f $ShaderPack.Label, $ShaderPack.File)
    } else {
        Write-Bad "could not obtain $($ShaderPack.File) - shaders will be unavailable"
    }

    # Iris rewrites this file every time it shuts down, so only edit it while the game is closed.
    $irisProps = Join-Path $GameDir 'config\iris.properties'
    $selected  = ''
    if (Test-Path $irisProps) {
        $hit = Select-String -Path $irisProps -Pattern '^shaderPack=' | Select-Object -First 1
        if ($hit) { $selected = $hit.Line.Substring('shaderPack='.Length).Trim() }
    }

    if ($Shaders -eq 'Keep') {
        if ($selected) { Write-Ok "Iris is set to load: $selected" }
        else { Write-Note 'no pack selected yet - Video Settings > Shader Packs selects one in game' }
    } elseif (Get-Process javaw -ErrorAction SilentlyContinue) {
        Write-Note "game is running and Iris rewrites iris.properties on exit, so -Shaders $Shaders was skipped"
    } elseif (Test-Path $irisProps) {
        $want = if ($Shaders -eq 'On') { $ShaderPack.File } else { '' }
        $text = Get-Content $irisProps -Raw
        if ($text -match '(?m)^shaderPack=.*$') {
            $text = [regex]::Replace($text, '(?m)^shaderPack=.*$', "shaderPack=$want")
        } else {
            $text = $text.TrimEnd() + "`r`nshaderPack=$want`r`n"
        }
        Set-Content -Path $irisProps -Value $text -NoNewline
        Write-Ok "Iris set to load: $(if ($want) { $want } else { '(none)' })"
    } else {
        Write-Note 'iris.properties does not exist yet - launch once, then set the pack in game'
    }
}

# ---------------------------------------------------------------- 3c. hands-free build
# The mod watches for config\startbuild-autorun. Once a world has loaded it deletes the file, finds a
# site (matching the optional wish words after the name), terraforms it by hand, starts Flashback and
# builds the schematic - no typing at all. File content: "<name>" or "<name> <wish words>".
# Only THIS instance counts as running: another Minecraft (a different instance or version) must not stop
# the launch - it used to, whenever the owner had a second game open.
$gameRunning = [bool](Get-CimInstance Win32_Process -Filter "Name='javaw.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -and $_.CommandLine -like "*instances*$Instance*" })
$autoBuild = (-not $NoAutoBuild) -and [bool]$Build
if ($autoBuild) {
    $flag = Join-Path $GameDir 'config\startbuild-autorun'
    $autorunText = $Build.Trim()
    if ($Wish.Trim()) { $autorunText = $autorunText + ' ' + $Wish.Trim() }
    New-Item -ItemType Directory -Force -Path (Split-Path $flag -Parent) | Out-Null
    Set-Content -Path $flag -Value $autorunText -NoNewline -Encoding ASCII
    Write-Ok "auto-build armed: $autorunText (starts by itself once the world is loaded)"
}

# ---------------------------------------------------------------- 4. launch
if ($gameRunning -and -not $NoLaunch) {
    Write-Step 'Minecraft is already running'
    if ($autoBuild) {
        Write-Note 'the build starts in the open world within a few seconds (close the game first if you want the mod updated)'
    } else {
        Write-Note 'nothing launched; close the game first if you want the mod updated'
    }
    Start-Sleep -Seconds 4
    exit 0
}
if ($NoLaunch) {
    Write-Step 'Setup only (-NoLaunch) - not starting Minecraft'
    exit 0
}

# Prism's "Low free memory - launch anyway?" dialog waits for a click, which stalls an unattended relaunch.
# Prism counts only truly free RAM (it said 70 MB while Windows had 6 GB available), so it is switched off.
$instanceCfg = Join-Path $InstanceDir 'instance.cfg'
if ((Test-Path $instanceCfg) -and (Select-String -Path $instanceCfg -Pattern '^LowMemWarning=true' -Quiet)) {
    (Get-Content $instanceCfg) -replace '^LowMemWarning=true', 'LowMemWarning=false' | Set-Content $instanceCfg -Encoding ASCII
    Write-Ok 'Prism low-memory prompt switched off for this instance (it blocks unattended launches)'
}

Write-Step "Launching '$Instance'"
Write-Host '   In game: /findsite <name> [wish]  then  /startbuild confirm   (finds a site, terraforms, builds)' -ForegroundColor White
Write-Host '   Or: /startbuild place <name>  (build where you stand)    /stopbuild  (stop and save the take)'
Write-Log "launch instance=$Instance"
$launchArgs = @('-l', $Instance)
if ($autoBuild -and $World) {
    # Prism quick-play: straight into the world, no menus to click.
    $launchArgs += @('-w', ('"' + $World + '"'))
}
Start-Process -FilePath $PrismExe -ArgumentList $launchArgs | Out-Null
Write-Ok 'Prism Launcher told to start - the game window should appear shortly'
exit 0
