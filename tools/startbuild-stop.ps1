<#
    Ask a running StartBuild session to stop and SAVE.
    =================================================
    Drops the stop-flag file that StartBuild watches for (startbuild-stop in the instance's config
    folder). The mod then does exactly what /stopbuild does: cancel the build, finish the Flashback
    recording, save the replay, and notify.

    Use this instead of killing Minecraft. Terminating the process discards the in-flight recording,
    because Flashback only turns it into a replay when the recording is finished properly.

    Usage:
        powershell -ExecutionPolicy Bypass -File tools\startbuild-stop.ps1
        powershell -ExecutionPolicy Bypass -File tools\startbuild-stop.ps1 -Wait
#>
[CmdletBinding()]
param(
    [string] $Instance = 'BuildRecording',
    [switch] $Wait
)

$ErrorActionPreference = 'Stop'
$configDir = Join-Path $env:APPDATA "PrismLauncher\instances\$Instance\minecraft\config"
$flag = Join-Path $configDir 'startbuild-stop'
$log = Join-Path $env:APPDATA "PrismLauncher\instances\$Instance\minecraft\logs\latest.log"

if (-not (Test-Path $configDir)) {
    Write-Output "No config folder at $configDir - is the instance name right?"
    exit 1
}

Set-Content -Path $flag -Value "stop requested $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" -NoNewline
Write-Output "Stop flag written: $flag"

if (-not $Wait) {
    Write-Output 'StartBuild checks for it every second. The replay is saved when it is picked up.'
    exit 0
}

Write-Output 'Waiting for StartBuild to pick it up...'
$deadline = (Get-Date).AddSeconds(60)
while ((Get-Date) -lt $deadline) {
    if (-not (Test-Path $flag)) {
        Write-Output 'Flag consumed.'
        if (Test-Path $log) {
            Get-Content $log -Tail 40 | Select-String -Pattern 'Stop requested|Recording stopped and saved|Aborted|stopping' |
                Select-Object -Last 5 | ForEach-Object { "  $($_.Line.Trim())" }
        }
        exit 0
    }
    Start-Sleep -Seconds 1
}

Write-Output "The flag is still there after 60s. Is StartBuild 1.12.0+ installed and a session running?"
exit 1
