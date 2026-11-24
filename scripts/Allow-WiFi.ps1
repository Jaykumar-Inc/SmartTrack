$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$rows=@(Get-NetAdapter -IncludeHidden | Where-Object { $_.Status -eq 'Up' -and $_.InterfaceDescription -like '*Wi-Fi Direct*' })
$addresses=@($rows | Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -notlike '169.254.*' })
if($addresses.Count -ne 1){throw 'Turn on the JAY laptop Mobile Hotspot (2.4 GHz), then run this again.'}
$address=$addresses[0].IPAddress
$settings=Get-Content -LiteralPath (Join-Path $projectRoot '.local\profile.json') -Raw | ConvertFrom-Json
foreach($rule in @(@{Name='SmartTrack-WiFi-HTTP';Protocol='TCP';Port=$settings.backend_port},@{Name='SmartTrack-WiFi-Discovery';Protocol='UDP';Port=49001})) {
  $existing=Get-NetFirewallRule -Name $rule.Name -ErrorAction SilentlyContinue
  if($existing){$existing | Remove-NetFirewallRule}
  New-NetFirewallRule -Name $rule.Name -DisplayName $rule.Name -Direction Inbound -Action Allow -Protocol $rule.Protocol -LocalPort $rule.Port -LocalAddress $address -RemoteAddress LocalSubnet -Profile Any | Out-Null
}
Write-Output 'SmartTrack is allowed on the laptop hotspot only (API TCP port and UDP 49001).'
