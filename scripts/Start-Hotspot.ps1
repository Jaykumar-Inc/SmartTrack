$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.Runtime.WindowsRuntime
$root=Split-Path $PSScriptRoot -Parent
$saved=Get-Content -LiteralPath (Join-Path $root '.local\wifi.json') -Raw | ConvertFrom-Json
$profile=[Windows.Networking.Connectivity.NetworkInformation,Windows.Networking.Connectivity,ContentType=WindowsRuntime]::GetInternetConnectionProfile()
if(-not $profile){throw 'Windows needs an available connection profile to start Mobile Hotspot.'}
$manager=[Windows.Networking.NetworkOperators.NetworkOperatorTetheringManager,Windows.Networking.NetworkOperators,ContentType=WindowsRuntime]::CreateFromConnectionProfile($profile)
$config=$manager.GetCurrentAccessPointConfiguration()
if($config.Ssid -cne $saved.ssid -or $config.Passphrase -cne $saved.password -or $config.Band.ToString() -ne 'TwoPointFourGigahertz') {
  $config.Ssid=$saved.ssid
  $config.Passphrase=$saved.password
  $config.Band=[Windows.Networking.NetworkOperators.TetheringWiFiBand,Windows.Networking.NetworkOperators,ContentType=WindowsRuntime]::TwoPointFourGigahertz
  $method=[System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and -not $_.IsGenericMethod -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncAction' } | Select-Object -First 1
  $task=$method.Invoke($null,@($manager.ConfigureAccessPointAsync($config)))
  if(-not $task.Wait(20000)){throw 'Hotspot configuration timed out'}
}
[Windows.Networking.NetworkOperators.NetworkOperatorTetheringManager,Windows.Networking.NetworkOperators,ContentType=WindowsRuntime]::DisableNoConnectionsTimeout()
if($manager.TetheringOperationalState.ToString() -ne 'On') {
  $method=[System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.IsGenericMethod -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' } | Select-Object -First 1
  $task=$method.MakeGenericMethod([Windows.Networking.NetworkOperators.NetworkOperatorTetheringOperationResult,Windows.Networking.NetworkOperators,ContentType=WindowsRuntime]).Invoke($null,@($manager.StartTetheringAsync()))
  if(-not $task.Wait(20000) -or $task.Result.Status.ToString() -ne 'Success'){throw 'Windows could not start Mobile Hotspot; turn it on manually in Settings.'}
}
Write-Output ('Laptop hotspot ready: '+$saved.ssid+' (2.4 GHz); automatic idle shutdown disabled.')
