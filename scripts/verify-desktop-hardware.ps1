param([Parameter(Mandatory=$true)][string]$DeviceId,[string]$SerialPort='COM5')
. "$PSScriptRoot\toolchain.ps1"
$sessionFile = Join-Path $projectRoot '.work\demo-session.json'
$session = Get-Content -LiteralPath $sessionFile -Raw | ConvertFrom-Json
$credentials = @{email=$session.email;password=$session.password} | ConvertTo-Json
$login = Invoke-RestMethod -Uri 'http://127.0.0.1:8087/v1/auth/login' -Method Post -ContentType 'application/json' -Body $credentials
$env:SMARTTRACK_USER_TOKEN = $login.token
$env:SMARTTRACK_BACKEND = 'http://127.0.0.1:8087'
$env:SMARTTRACK_TEST_DEVICE = $DeviceId
$env:SMARTTRACK_TEST_SOURCE = 'HARDWARE'
$monitor = $null
Push-Location $projectRoot
try {
    $monitor = Start-Process -FilePath (Join-Path $projectRoot '.tools\pio-env\Scripts\python.exe') -ArgumentList @('scripts/ble-monitor.py','--seconds','35','--device',$DeviceId,'--usb-pin-port',$SerialPort,'--backend',$env:SMARTTRACK_BACKEND,'--session-file',"`"$sessionFile`"") -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $projectRoot '.work\desktop-hardware-bridge.log') -RedirectStandardError (Join-Path $projectRoot '.work\desktop-hardware-bridge-error.log')
    $null = $monitor.Handle
    $ready = $false
    for ($attempt=0; $attempt -lt 50; $attempt++) {
        $monitor.Refresh()
        if ($monitor.HasExited) { throw 'BLE bridge exited; inspect .work/desktop-hardware-bridge logs' }
        if ((Get-Content -LiteralPath '.work\desktop-hardware-bridge.log' -Raw -ErrorAction SilentlyContinue) -match 'HARDWARE sample') { $ready=$true; break }
        Start-Sleep -Milliseconds 500
    }
    if (-not $ready) { throw 'No hardware reading reached the BLE bridge' }
    $swingCache=Join-Path $env:USERPROFILE '.m2\repository\org\openjfx\javafx-swing\21.0.9'
    $classpath="desktop\target\test-classes;desktop\target\classes;desktop\target\lib\*;$swingCache\*"
    $javaFxModules=((Get-ChildItem -LiteralPath 'desktop\target\lib' -Filter 'javafx-*-win.jar').FullName -join ';')
    & "$env:JAVA_HOME\bin\java.exe" --module-path $javaFxModules --add-modules javafx.controls --enable-native-access=javafx.graphics -cp $classpath io.smarttrack.desktop.DesktopSmoke
    if ($LASTEXITCODE -ne 0) { throw 'Desktop hardware journey failed' }
    if (-not $monitor.WaitForExit(45000)) { throw 'BLE bridge did not finish in time' }
    $monitor.WaitForExit()
    $monitor.Refresh()
    if ($monitor.ExitCode -ne 0) { throw 'BLE bridge reported an error' }
} finally {
    if ($monitor -and -not $monitor.HasExited) {
        # This is the child created above, never a process found by name.
        Stop-Process -Id $monitor.Id -ErrorAction SilentlyContinue
    }
    Pop-Location
    Remove-Item Env:SMARTTRACK_USER_TOKEN,Env:SMARTTRACK_TEST_DEVICE,Env:SMARTTRACK_TEST_SOURCE -ErrorAction SilentlyContinue
}
