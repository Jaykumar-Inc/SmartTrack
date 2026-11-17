param([ValidateRange(1024,65535)][int]$Port=8089)
. "$PSScriptRoot\toolchain.ps1"
$ErrorActionPreference='Stop'
if(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue){throw "Test port $Port is already occupied"}
$runtimeJar=Join-Path $projectRoot '.work\runtime\smarttrack-integration-backend.jar'
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $runtimeJar) | Out-Null
Copy-Item -LiteralPath (Join-Path $projectRoot 'backend\target\backend-1.0.0-SNAPSHOT.jar') -Destination $runtimeJar -Force
$arguments=@('-jar',"`"$runtimeJar`"",'--spring.profiles.active=demo','--spring.datasource.url=jdbc:h2:mem:smarttrack_integration;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1',"--server.port=$Port",'--server.address=127.0.0.1')
$server=Start-Process -FilePath "$env:JAVA_HOME\bin\java.exe" -ArgumentList $arguments -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $projectRoot '.work\integration-backend.log') -RedirectStandardError (Join-Path $projectRoot '.work\integration-backend-error.log')
Set-Content -LiteralPath (Join-Path $projectRoot '.work\integration-backend.pid') -Value $server.Id
$base="http://127.0.0.1:$Port"
$ready=$false
for($attempt=0;$attempt -lt 60;$attempt++){
 $server.Refresh();if($server.HasExited){throw 'Test backend exited; inspect .work/integration-backend logs'}
 try{if((Invoke-RestMethod -Uri "$base/actuator/health" -TimeoutSec 1).status -eq 'UP'){$ready=$true;break}}catch{}
 Start-Sleep -Milliseconds 500
}
if(-not $ready){throw 'Test backend was not ready in time'}
$secretBytes=New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($secretBytes)
$account=@{email="integration-$([Guid]::NewGuid().ToString('N'))@example.test";password=[Convert]::ToBase64String($secretBytes)}
$body=$account|ConvertTo-Json
$null=Invoke-RestMethod -Uri "$base/v1/auth/register" -Method Post -ContentType 'application/json' -Body $body
$login=Invoke-RestMethod -Uri "$base/v1/auth/login" -Method Post -ContentType 'application/json' -Body $body
$account.userToken=$login.token
$account.backend=$base
$account|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $projectRoot '.work\integration-session.json') -Encoding UTF8
Write-Output "In-memory integration backend ready at $base. Main MySQL accounts and device ownership were not changed."
