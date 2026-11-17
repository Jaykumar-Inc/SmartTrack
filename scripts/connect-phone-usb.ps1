param([string]$Serial, [switch]$Install, [ValidateRange(1,65535)][int]$Port = 8087)
$ErrorActionPreference = 'Stop'
try {
    $projectRoot = Split-Path -Parent $PSScriptRoot
    $sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    $adb = Join-Path $sdk 'platform-tools\adb.exe'
    if (-not (Test-Path -LiteralPath $adb)) { throw 'Install Android SDK Platform Tools or set ANDROID_HOME.' }
    $devices = @(& $adb devices | Where-Object { $_ -match '^([^\s]+)\s+device$' } | ForEach-Object { ($_ -split '\s+')[0] })
    if ($Serial) {
        if ($devices -notcontains $Serial) { throw 'The selected phone is not authorized in ADB. Accept its USB debugging prompt.' }
    } elseif ($devices.Count -eq 1) { $Serial = $devices[0] }
    elseif ($devices.Count -eq 0) { throw 'Connect your phone by USB, enable USB debugging, and accept the laptop authorization prompt. Then run this file again.' }
    else { throw 'Multiple Android devices are connected. Run with -Serial followed by the intended device ID.' }
    $health = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/actuator/health" -TimeoutSec 5
    if ($health.status -ne 'UP') { throw 'Start Start-Backend.cmd first.' }
    & $adb -s $Serial reverse "tcp:$Port" "tcp:$Port"
    if ($LASTEXITCODE -ne 0) { throw 'USB backend forwarding failed.' }
    if ($Install) {
        $apk = Join-Path $projectRoot 'dist\SmartTrack-Android-debug.apk'
        if (-not (Test-Path -LiteralPath $apk)) { throw 'Updated APK missing from dist.' }
        & $adb -s $Serial install -r $apk
        if ($LASTEXITCODE -ne 0) { throw 'APK update failed. Existing app data was not erased.' }
    }
    Write-Output "Phone backend address: http://127.0.0.1:$Port"
    Write-Output 'Enter this address in SmartTrack on the connected phone, then tap Test connection.'
    Write-Output 'Keep USB connected. No LAN exposure or firewall changes are needed.'
} catch { Write-Host "Phone setup failed: $($_.Exception.Message)" -ForegroundColor Red; exit 1 }
