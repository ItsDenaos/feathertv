# Installs FeatherTV and makes it the home screen, on Fire TV or on Android TV / Google TV.
#
#   powershell -ExecutionPolicy Bypass -File tools\install.ps1 -Device 192.168.1.50:5555
#   powershell -ExecutionPolicy Bypass -File tools\install.ps1 -Device ... -Undo          (undo everything)
#   powershell -ExecutionPolicy Bypass -File tools\install.ps1 -Device ... -NoInstall     (only re-link)
#   powershell -ExecutionPolicy Bypass -File tools\install.ps1 -Device ... -ReplaceStock  (Android TV, see below)
#
# Fire OS (detected from the manufacturer):
#   1. Installs build\feathertv.apk and exempts it from battery optimization.
#   2. alarm_manager_constants: min_futurity=0 (opens FeatherTV without the 5 s delay after Home).
#   3. Enables the Guard accessibility service and disables the accessibility services of any other
#      launcher on the device so they don't compete.
#
# Android TV / Google TV:
#   1. Installs build\feathertv.apk.
#   2. Sets FeatherTV as the preferred home screen (cmd package set-home-activity).
#   3. Checks which app Home really opens. Some builds (Google TV in particular) keep their own
#      launcher; with -ReplaceStock the script disables (pm disable-user) only the app that still
#      takes the home role, and repeats until Home opens FeatherTV.
#
# Everything it changes is saved in .ftv-state\<model>-<serial>\ (one folder per device) so -Undo
# can put it back.
param(
    [string]$Device = $env:FTV_DEVICE,
    [switch]$Undo,
    [switch]$NoInstall,
    [switch]$ReplaceStock
)

$ErrorActionPreference = 'Stop'
$adb = $env:ADB
if (-not $adb) { $adb = Join-Path $env:LOCALAPPDATA 'Android\platform-tools\adb.exe' }
if (-not (Test-Path $adb)) { $adb = 'adb' }
$root = Split-Path -Parent $PSScriptRoot
$apk = Join-Path $root 'build\feathertv.apk'
# Outside build/ so that a build doesn't delete it.
$stateRoot = Join-Path $root '.ftv-state'
$pkg = 'tv.feathertv.home'
$guard = 'tv.feathertv.home/tv.feathertv.home.Guard'
$homeActivity = 'tv.feathertv.home/.Home'

function Adb {
    if ($Device) { & $adb -s $Device @args } else { & $adb @args }
}
function Sh([string]$cmd) {
    $out = Adb shell $cmd
    if ($out -eq $null) { return '' }
    return (($out | Out-String).Trim())
}
function Save([string]$path, [string]$value) {
    New-Item -ItemType Directory -Force $state | Out-Null
    Set-Content -Path $path -Value $value -Encoding ascii
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
# Packages that declare a home screen, that is, launchers.
function Get-Launchers {
    $out = Sh 'cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME'
    return @($out -split "`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ -match '^[\w.]+/' } |
        ForEach-Object { ($_ -split '/')[0] } | Select-Object -Unique)
}
# The activity that Home opens right now ("package/.Activity").
function Resolve-Home {
    $out = Sh 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME'
    $line = @($out -split "`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ -match '^[\w.]+/' }) | Select-Object -Last 1
    if ($line) { return $line } else { return '' }
}

if ($Device) { & $adb connect $Device | Out-Null }
if ((Adb get-state 2>$null) -ne 'device') { throw 'No ADB connection to the device.' }
$fireOs = (Sh 'getprop ro.product.manufacturer') -match '^Amazon$'
$model = Sh 'getprop ro.product.model'
Write-Host "Device: $model ($(if ($fireOs) { 'Fire OS' } else { 'Android TV' }), Android $(Sh 'getprop ro.build.version.release'))"

# One state folder per device, named by model and serial number (not by IP, which can change).
$serial = Sh 'getprop ro.serialno'
if (-not $serial) { $serial = (Adb get-serialno | Out-String).Trim() }
$state = Join-Path $stateRoot (("$model-$serial") -replace '[^\w.-]', '_')
$savedServices = Join-Path $state 'previous-accessibility.txt'
$savedHome = Join-Path $state 'previous-home.txt'
$savedDisabled = Join-Path $state 'disabled-launchers.txt'

# ------------------------------------------------------------------------------------- undo
if ($Undo) {
    if ($fireOs) {
        if (Test-Path $savedServices) {
            $prev = @((Get-Content $savedServices -Raw).Trim() -split ':' | Where-Object { $_ -and $_ -ne 'null' })
        } else {
            $prev = @(Get-Services | Where-Object { $_ -ne $guard })
        }
        Set-Services $prev
        Sh "dumpsys deviceidle whitelist -$pkg" | Out-Null
        if (Test-Path $savedServices) { Remove-Item $savedServices -Confirm:$false }
        Write-Host "Home button handed back to: $(if ($prev.Count) { $prev -join ', ' } else { "Amazon's home screen" })"
    } else {
        if (Test-Path $savedDisabled) {
            foreach ($p in @(Get-Content $savedDisabled | Where-Object { $_ })) {
                Sh "pm enable $p" | Out-Null
                Write-Host "Re-enabled: $p"
            }
            Remove-Item $savedDisabled -Confirm:$false
        }
        if (Test-Path $savedHome) {
            $prevHome = (Get-Content $savedHome -Raw).Trim()
            if ($prevHome -and $prevHome -notmatch '^android/') { Sh "cmd package set-home-activity $prevHome" | Out-Null }
            Remove-Item $savedHome -Confirm:$false
            Write-Host "Home screen handed back to: $prevHome"
        }
    }
    if ((Test-Path $state) -and -not (Get-ChildItem $state)) { Remove-Item $state -Confirm:$false }
    Sh 'am start -a android.intent.action.MAIN -c android.intent.category.HOME' | Out-Null
    Write-Host "FeatherTV is still installed; to remove it: adb uninstall $pkg"
    return
}

# ------------------------------------------------------------------------------------- install
if (-not $NoInstall) {
    if (-not (Test-Path $apk)) { throw "$apk not found. Build it first with ./build.sh" }
    Adb install -r $apk
}

if ($fireOs) {
    Sh "dumpsys deviceidle whitelist +$pkg" | Out-Null

    $alarm = Sh 'settings get global alarm_manager_constants'
    if (-not $alarm -or $alarm -eq 'null') {
        $alarm = 'min_futurity=0'
    } elseif ($alarm -notmatch '(^|,)min_futurity=0(,|$)') {
        $alarm = ((@($alarm -split ',' | Where-Object { $_ -and $_ -notmatch '^min_futurity=' }) + 'min_futurity=0') -join ',')
    }
    Sh "settings put global alarm_manager_constants $alarm" | Out-Null

    $current = Get-Services
    if (-not (Test-Path $savedServices)) { Save $savedServices ($current -join ':') }
    $launchers = Get-Launchers
    $keep = @($current | Where-Object { $_ -ne $guard -and ($launchers -notcontains ($_ -split '/')[0]) })
    Set-Services (@($keep) + $guard)

    Sh "am start -n $homeActivity" | Out-Null
    Start-Sleep -Seconds 2
    Write-Host "Accessibility: $(Sh 'settings get secure enabled_accessibility_services')"
    Write-Host "Alarms:        $(Sh 'settings get global alarm_manager_constants')"
} else {
    if (-not (Test-Path $savedHome)) { Save $savedHome (Resolve-Home) }
    Sh "cmd package set-home-activity $homeActivity" | Out-Null

    $disabled = @()
    if (Test-Path $savedDisabled) { $disabled = @(Get-Content $savedDisabled | Where-Object { $_ }) }
    for ($i = 0; $i -lt 4; $i++) {
        $now = Resolve-Home
        if ($now -like "$pkg/*") { break }
        $owner = ($now -split '/')[0]
        # Never touch Android itself (the app chooser) or the settings app's fallback home screen.
        if (-not $ReplaceStock -or -not $owner -or $owner -eq 'android' -or $owner -match 'settings') { break }
        Sh "pm disable-user --user 0 $owner" | Out-Null
        $disabled += $owner
        Save $savedDisabled ($disabled -join "`n")
        Write-Host "Disabled the stock launcher: $owner"
        Sh "cmd package set-home-activity $homeActivity" | Out-Null
    }

    $now = Resolve-Home
    if ($now -like "$pkg/*") {
        Sh 'am start -a android.intent.action.MAIN -c android.intent.category.HOME' | Out-Null
        Write-Host "Home screen: $now"
    } else {
        Write-Host "Home still opens: $(if ($now) { $now } else { 'the app chooser' })"
        if (-not $ReplaceStock) {
            Write-Host 'Run again with -ReplaceStock to disable that launcher (reversible with -Undo).'
        }
        Sh "am start -n $homeActivity" | Out-Null
    }
}
Write-Host 'Done. Press Home on the remote to try it.'
