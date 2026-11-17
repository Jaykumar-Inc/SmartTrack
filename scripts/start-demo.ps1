param([int]$Port=8087)
. "$PSScriptRoot\toolchain.ps1"
& "$PSScriptRoot\start-local-mysql.ps1"
if(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue){throw "Port $Port is occupied"}
$env:SMARTTRACK_PORT="$Port";$env:SMARTTRACK_ALLOW_SIMULATOR='true';$env:DEBUG='false'
$runtimeDirectory=Join-Path $projectRoot '.work\runtime'
New-Item -ItemType Directory -Force -Path $runtimeDirectory | Out-Null
$runtimeJar=Join-Path $runtimeDirectory 'smarttrack-backend.jar'
Copy-Item -LiteralPath (Join-Path $projectRoot 'backend\target\backend-1.0.0-SNAPSHOT.jar') -Destination $runtimeJar -Force
$server=Start-Process -FilePath "$env:JAVA_HOME\bin\java.exe" -ArgumentList @('-jar',"`"$runtimeJar`"") -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $projectRoot '.work\backend.log') -RedirectStandardError (Join-Path $projectRoot '.work\backend-error.log')
Set-Content -LiteralPath (Join-Path $projectRoot '.work\backend.pid') -Value $server.Id
$ready=$false
for($attempt=0;$attempt -lt 60;$attempt++){try{$response=Invoke-RestMethod -Uri "http://127.0.0.1:$Port/actuator/health" -TimeoutSec 1;if($response.status -eq 'UP'){$ready=$true;break}}catch{Start-Sleep -Milliseconds 500}}
if(-not $ready){throw 'Backend startup failed; inspect .work/backend.log'}
Push-Location $projectRoot
try{
 $sessionFile=Join-Path $projectRoot '.work\demo-session.json'
 if(Test-Path -LiteralPath $sessionFile){
  $session=Get-Content -LiteralPath $sessionFile -Raw | ConvertFrom-Json
  $loginBody=@{email=$session.email;password=$session.password} | ConvertTo-Json
  $login=Invoke-RestMethod -Uri "http://127.0.0.1:$Port/v1/auth/login" -Method Post -ContentType 'application/json' -Body $loginBody
  $session.userToken=$login.token;$session | ConvertTo-Json | Set-Content -LiteralPath $sessionFile
  $env:SMARTTRACK_DEVICE_TOKEN=$session.deviceToken
  python scripts/simulator.py --base "http://127.0.0.1:$Port" --device $session.deviceId --count 20 --interval 0.1
 }else{python scripts/simulator.py --base "http://127.0.0.1:$Port" --bootstrap --write-session --device sim-esp32 --count 20 --interval 0.1}
 if($LASTEXITCODE -ne 0){throw 'Simulator bootstrap failed'}
}finally{Pop-Location;Remove-Item Env:SMARTTRACK_DEVICE_TOKEN -ErrorAction SilentlyContinue}
Write-Output "Simulator development backend ready at http://127.0.0.1:$Port with MySQL on port 3317. Session credentials: .work/demo-session.json (private)."
