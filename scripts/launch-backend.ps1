param(
    [ValidateRange(1, 65535)][int]$Port = 8087,
    [string]$BindAddress = $(if ($Port -eq 8087) { '0.0.0.0' } else { '127.0.0.1' }),
    [switch]$Restart
)

$ErrorActionPreference = 'Stop'
function Show-BackendAddresses([int]$ApiPort, [string[]]$ListeningAddresses) {
    $phoneUrls = @()
    if ($ListeningAddresses -contains '0.0.0.0' -or $ListeningAddresses -contains '::') {
        $phoneUrls = @(Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
            Where-Object { $_.AddressState -eq 'Preferred' -and $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' } |
            Select-Object -ExpandProperty IPAddress -Unique |
            ForEach-Object { "http://${_}:$ApiPort" })
    } else {
        $phoneUrls = @($ListeningAddresses | Where-Object { $_ -ne '127.0.0.1' -and $_ -ne '::1' } |
            ForEach-Object { if ($_.Contains(':')) { "http://[$_]:$ApiPort" } else { "http://${_}:$ApiPort" } })
    }
    if ($phoneUrls.Count) {
        Write-Output 'Phone backend address (use the same Wi-Fi):'
        $phoneUrls | ForEach-Object { Write-Output "  $_" }
        $addressFile = Join-Path $projectRoot 'Phone-Backend-Address.txt'
        @('SmartTrack phone backend address', '') + $phoneUrls + @('', 'Keep the phone and laptop on the same Wi-Fi. Enter a URL above in SmartTrack, then tap Test connection.') |
            Set-Content -LiteralPath $addressFile -Encoding ASCII
        Write-Output "Saved phone address: $addressFile"
    } else {
        Write-Output 'Phone Wi-Fi access is not active on this listener. USB forwarding remains available.'
    }
}
try {
    . "$PSScriptRoot\toolchain.ps1"
    $workDirectory = Join-Path $projectRoot '.work'
    $runtimeDirectory = Join-Path $workDirectory 'runtime'
    $runtimeJar = Join-Path $runtimeDirectory 'smarttrack-backend.jar'
    $sourceJar = Join-Path $projectRoot 'backend\target\backend-1.0.0-SNAPSHOT.jar'
    $packagedJar = Join-Path $projectRoot 'dist\smarttrack-backend-1.0.0-preview.jar'
    $healthUrl = "http://127.0.0.1:$Port/actuator/health"
    $listener = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue

    if ($listener) {
        $owners = @($listener | Select-Object -ExpandProperty OwningProcess -Unique)
        if ($owners.Count -ne 1) { throw "Port $Port has multiple owners. Choose another port with -Port." }
        $running = Get-CimInstance Win32_Process -Filter "ProcessId=$($owners[0])"
        $isWorkspaceBackend = $false
        foreach ($jar in @($runtimeJar, $sourceJar, $packagedJar)) {
            if ($running.CommandLine -and $running.CommandLine.Contains($jar)) {
                $isWorkspaceBackend = $true
            }
        }
        if (-not $isWorkspaceBackend) { throw "Port $Port belongs to another application. Choose another port with -Port." }
        if ($Restart) {
            Write-Output 'Restarting the identified workspace backend...'
            Stop-Process -Id $owners[0]
            for ($attempt = 0; $attempt -lt 40; $attempt++) {
                if (-not (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)) { break }
                Start-Sleep -Milliseconds 250
            }
            if (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue) {
                throw "Port $Port is still occupied after stopping the workspace backend"
            }
        } else {
        $health = Invoke-RestMethod -Uri $healthUrl -TimeoutSec 5
        if ($health.status -ne 'UP') { throw 'The workspace backend is running but is not healthy. Inspect .work backend logs.' }
        Write-Output "SmartTrack backend is already running at http://127.0.0.1:$Port"
        Write-Output "Health: $healthUrl"
        Write-Output "Listening on: $(($listener.LocalAddress | Select-Object -Unique) -join ', ')"
        Show-BackendAddresses $Port @($listener.LocalAddress | Select-Object -Unique)
        return
        }
    }

    if (-not (Test-Path -LiteralPath $sourceJar)) { $sourceJar = $packagedJar }
    if (-not (Test-Path -LiteralPath $sourceJar)) {
        throw 'Backend JAR missing. Run scripts/build.ps1 first, then start the backend again.'
    }
    & "$PSScriptRoot\start-local-mysql.ps1"
    New-Item -ItemType Directory -Force -Path $runtimeDirectory | Out-Null
    $copyNeeded = -not (Test-Path -LiteralPath $runtimeJar)
    if (-not $copyNeeded) {
        $copyNeeded = (Get-FileHash -LiteralPath $sourceJar).Hash -ne (Get-FileHash -LiteralPath $runtimeJar).Hash
    }
    if ($copyNeeded) { Copy-Item -LiteralPath $sourceJar -Destination $runtimeJar -Force }
    $env:SMARTTRACK_PORT = "$Port"
    $env:SMARTTRACK_BIND = $BindAddress
    $stdoutLog = Join-Path $workDirectory "backend-$Port.log"
    $stderrLog = Join-Path $workDirectory "backend-$Port-error.log"
    Write-Output "Starting SmartTrack backend on port $Port..."
    $server = Start-Process -FilePath "$env:JAVA_HOME\bin\java.exe" -ArgumentList @('-jar', "`"$runtimeJar`"") -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdoutLog -RedirectStandardError $stderrLog
    Set-Content -LiteralPath (Join-Path $workDirectory "backend-$Port.pid") -Value $server.Id
    if ($Port -eq 8087) { Set-Content -LiteralPath (Join-Path $workDirectory 'backend.pid') -Value $server.Id }
    $ready = $false
    # A local readiness probe also works when listening on all IPv4 interfaces.
    $probeHost = if ($BindAddress -eq '0.0.0.0') { '127.0.0.1' } else { $BindAddress }
    if ($probeHost.Contains(':')) { $probeHost = "[$probeHost]" }
    $healthUrl = "http://${probeHost}:$Port/actuator/health"
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        $server.Refresh()
        if ($server.HasExited) { throw "Backend exited. Inspect $stdoutLog and $stderrLog" }
        try {
            $health = Invoke-RestMethod -Uri $healthUrl -TimeoutSec 1
            if ($health.status -eq 'UP') { $ready = $true; break }
        } catch { }
        Start-Sleep -Milliseconds 500
    }
    if (-not $ready) { throw "Backend is not ready yet. Inspect $stdoutLog and $stderrLog" }
    Write-Output "SmartTrack backend is ready at http://${probeHost}:$Port"
    Write-Output "Health: $healthUrl"
    Write-Output "Logs: $stdoutLog"
    Show-BackendAddresses $Port @($BindAddress)
    Write-Output 'You can close this window; the backend keeps running.'
} catch {
    Write-Host "SmartTrack startup failed: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
