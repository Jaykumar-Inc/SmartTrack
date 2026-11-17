param([ValidateRange(1,65535)][int]$Port = 8087,[System.Net.IPAddress]$PhoneAddress)
$ErrorActionPreference = 'Stop'
try {
    $projectRoot = Split-Path -Parent $PSScriptRoot
    $localJdk = Get-ChildItem -LiteralPath (Join-Path $projectRoot '.tools') -Directory -Filter 'jdk-21*' | Select-Object -First 1
    $javaHome = if ($localJdk) { $localJdk.FullName } else { $env:JAVA_HOME }
    if (-not $javaHome) { throw 'Java 21 is required.' }
    $java = Join-Path $javaHome 'bin\java.exe'
    if (-not (Test-Path -LiteralPath $java)) { throw 'Backend Java executable not found.' }
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'Windows needs administrator access for this firewall rule. Run Enable-Phone-Access.cmd as administrator.'
    }
    $name = "SmartTrack-E-Smarttrack-API-$Port"
    $description = "SmartTrack workspace $projectRoot. Java API TCP $Port, local subnet only."
    $existing = Get-NetFirewallRule -Name $name -ErrorAction SilentlyContinue
    if ($existing -and $existing.Description -ne $description) { throw 'An unrelated rule uses this name; no firewall rule was changed.' }
    if ($existing) {
        $existing | Set-NetFirewallRule -Enabled True -Direction Inbound -Action Allow -Profile Private,Public -Protocol TCP -LocalPort $Port -RemoteAddress LocalSubnet -Program $java -EdgeTraversalPolicy Block | Out-Null
    } else {
        New-NetFirewallRule -Name $name -DisplayName "SmartTrack local Wi-Fi API (TCP $Port)" -Description $description -Enabled True -Direction Inbound -Action Allow -Profile Private,Public -Protocol TCP -LocalPort $Port -RemoteAddress LocalSubnet -Program $java -EdgeTraversalPolicy Block | Out-Null
    }
    Write-Output "Enabled SmartTrack TCP $Port for the local subnet and this workspace's Java executable."
    Write-Output 'Existing firewall rules and network profiles were preserved.'
    if ($PhoneAddress) {
        if ($PhoneAddress.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork -or [Net.IPAddress]::IsLoopback($PhoneAddress)) { throw 'Use the physical phone IPv4 address shown in SmartTrack diagnostics.' }
        $phoneRuleName = "SmartTrack-E-Smarttrack-Phone-$Port"
        $phoneDescription = "SmartTrack workspace $projectRoot. Specific Android phone TCP $Port."
        $phoneRule = Get-NetFirewallRule -Name $phoneRuleName -ErrorAction SilentlyContinue
        if ($phoneRule -and $phoneRule.Description -ne $phoneDescription) { throw 'An unrelated phone rule uses this name; no phone rule was changed.' }
        $remotePhone = $PhoneAddress.IPAddressToString
        if ($phoneRule) {
            $phoneRule | Set-NetFirewallRule -Enabled True -Direction Inbound -Action Allow -Profile Private,Public -Protocol TCP -LocalPort $Port -RemoteAddress $remotePhone -Program $java -EdgeTraversalPolicy Block | Out-Null
        } else {
            New-NetFirewallRule -Name $phoneRuleName -DisplayName "SmartTrack specific Android phone (TCP $Port)" -Description $phoneDescription -Enabled True -Direction Inbound -Action Allow -Profile Private,Public -Protocol TCP -LocalPort $Port -RemoteAddress $remotePhone -Program $java -EdgeTraversalPolicy Block | Out-Null
        }
        Write-Output "Allowed this phone only: $remotePhone on TCP $Port."
    }
    @{success=$true;ruleName=$name;port=$Port;remoteAddress='LocalSubnet';phoneAddress=if($PhoneAddress){$PhoneAddress.IPAddressToString}else{$null};program=$java;updatedAt=[DateTime]::UtcNow.ToString('o')} |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $projectRoot '.work\phone-firewall-result.json') -Encoding UTF8
} catch { Write-Host "Phone network setup failed: $($_.Exception.Message)" -ForegroundColor Red; exit 1 }
