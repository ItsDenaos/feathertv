# Installs FeatherTV on a Fire TV and gives it the Home button.
#
#   powershell -ExecutionPolicy Bypass -File tools\install.ps1 -Device 192.168.1.50:5555
#   powershell -ExecutionPolicy Bypass -File tools\install.ps1 -Device ... -Undo      (undo everything)
#   powershell -ExecutionPolicy Bypass -File tools\install.ps1 -Device ... -NoInstall (only re-link)
#
# What it does:
#   1. Installs build\feathertv.apk.
#   2. Exempts it from battery optimization (so the service isn't put to sleep).
#   3. alarm_manager_constants: min_futurity=0 (opens FeatherTV without the 5 s delay after Home).
#   4. Enables the Guard accessibility service and disables the accessibility services of any other
#      launcher on the device so they don't compete. The previous list is saved for -Undo.
param(
    [string]$Device = $env:FTV_DEVICE,
    [switch]$Undo,
    [switch]$NoInstall
)

$ErrorActionPreference = 'Stop'
$adb = $env:ADB
if (-not $adb) { $adb = Join-Path $env:LOCALAPPDATA 'Android\platform-tools\adb.exe' }
if (-not (Test-Path $adb)) { $adb = 'adb' }
$root = Split-Path -Parent $PSScriptRoot
$apk = Join-Path $root 'build\feathertv.apk'
# Outside build/ so that a build doesn't delete it.
$saved = Join-Path $root '.ftv-state\previous-accessibility.txt'
$guard = 'tv.feathertv.home/tv.feathertv.home.Guard'

function Adb {
    if ($Device) { & $adb -s $Device @args } else { & $adb @args }
}
function Sh([string]$cmd) {
    $out = Adb shell $cmd
    if ($out -eq $null) { return '' }
    return (($out | Out-String).Trim())
}
function Get-Services {
    $v = Sh 'settings get secure enabled_accessibility_services'
    if (-not $v -or $v -eq 'null') { return @() }
    return @($v -split ':' | Where-Object { $_ })
}
function Set-Services([string[]]$list) {
    # Clearing the list first forces Android to bind the services again.
    Sh 'settings delete secure enabled_accessibility_services' | Out-Null
    Start-Sleep -Seconds 1
    if ($list.Count -gt 0) {
        Sh ('settings put secure enabled_accessibility_services ' + ($list -join ':')) | Out-Null
        Sh 'settings put secure accessibility_enabled 1' | Out-Null
    } else {
        Sh 'settings put secure accessibility_enabled 0' | Out-Null
    }
}
# Packages that declare a home screen, that is, other launchers.
function Get-Launchers {
    $out = Sh 'cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME'
    return @($out -split "`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ -match '^[\w.]+/' } |
        ForEach-Object { ($_ -split '/')[0] } | Select-Object -Unique)
}

if ($Device) { & $adb connect $Device | Out-Null }
if ((Adb get-state 2>$null) -ne 'device') { throw 'No ADB connection to the device.' }

if ($Undo) {
    if (Test-Path $saved) {
        $prev = @((Get-Content $saved -Raw).Trim() -split ':' | Where-Object { $_ -and $_ -ne 'null' })
    } else {
        $prev = @(Get-Services | Where-Object { $_ -ne $guard })
    }
    Set-Services $prev
    Sh 'dumpsys deviceidle whitelist -tv.feathertv.home' | Out-Null
    Sh 'am start -a android.intent.action.MAIN -c android.intent.category.HOME' | Out-Null
    if (Test-Path $saved) { Remove-Item $saved -Confirm:$false }
    Write-Host "Home button handed back to: $(if ($prev.Count) { $prev -join ', ' } else { "Amazon's home screen" })"
    Write-Host 'FeatherTV is still installed; to remove it: adb uninstall tv.feathertv.home'
    return
}

if (-not $NoInstall) {
    if (-not (Test-Path $apk)) { throw "$apk not found. Build it first with ./build.sh" }
    Adb install -r $apk
}

Sh 'dumpsys deviceidle whitelist +tv.feathertv.home' | Out-Null

$alarm = Sh 'settings get global alarm_manager_constants'
if (-not $alarm -or $alarm -eq 'null') {
    $alarm = 'min_futurity=0'
} elseif ($alarm -notmatch '(^|,)min_futurity=0(,|$)') {
    $alarm = ((@($alarm -split ',' | Where-Object { $_ -and $_ -notmatch '^min_futurity=' }) + 'min_futurity=0') -join ',')
}
Sh "settings put global alarm_manager_constants $alarm" | Out-Null

$current = Get-Services
if (-not (Test-Path $saved)) {
    New-Item -ItemType Directory -Force (Split-Path $saved) | Out-Null
    Set-Content -Path $saved -Value ($current -join ':') -Encoding ascii
}
$launchers = Get-Launchers
$keep = @($current | Where-Object { $_ -ne $guard -and ($launchers -notcontains ($_ -split '/')[0]) })
Set-Services (@($keep) + $guard)

Sh 'am start -n tv.feathertv.home/.Home' | Out-Null
Start-Sleep -Seconds 2
Write-Host "Accessibility: $(Sh 'settings get secure enabled_accessibility_services')"
Write-Host "Alarms:        $(Sh 'settings get global alarm_manager_constants')"
Write-Host 'Done. Press Home on the remote to try it.'
