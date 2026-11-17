param([string]$MySqlHome = 'C:\Program Files\MySQL\MySQL Server 8.0',[int]$Port=3317,[switch]$ResumeInitialization)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$work=Join-Path $projectRoot '.work'
New-Item -ItemType Directory -Force -Path $work | Out-Null
$data=Join-Path $projectRoot '.tools\mysql-data'
$server=Join-Path $MySqlHome 'bin\mysqld.exe'
$client=Join-Path $MySqlHome 'bin\mysql.exe'
$credentials=Join-Path $work 'mysql-credentials.json'
$listening=Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
if (Test-Path -LiteralPath $credentials) {
 $saved=Get-Content -LiteralPath $credentials -Raw | ConvertFrom-Json
 $Port=[int]$saved.port
 if(-not (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)) {
  $restartArgs=@('--no-defaults',"--basedir=`"$MySqlHome`"","--datadir=`"$data`"",'--bind-address=127.0.0.1',"--port=$Port",'--mysqlx=0','--skip-log-bin',"--log-error=`"$(Join-Path $work 'mysql-error.log')`"")
  $restart=Start-Process -FilePath $server -ArgumentList $restartArgs -WindowStyle Hidden -PassThru
  Set-Content -LiteralPath (Join-Path $work 'mysql.pid') -Value $restart.Id
  Start-Sleep -Seconds 2
 }
 $priorPassword=$env:MYSQL_PWD
 try {
  $env:MYSQL_PWD=$saved.app
  $actualData=& $client --no-defaults --host=127.0.0.1 "--port=$Port" --user=smarttrack --batch --skip-column-names '--execute=SELECT @@datadir'
  if($LASTEXITCODE -ne 0 -or [IO.Path]::GetFullPath($actualData.Trim()).TrimEnd('\','/') -ne [IO.Path]::GetFullPath($data).TrimEnd('\','/')){throw 'Workspace database identity check failed'}
 } finally {$env:MYSQL_PWD=$priorPassword}
 $env:SMARTTRACK_DB_URL="jdbc:mysql://127.0.0.1:$Port/smarttrack?connectionTimeZone=UTC"
 $env:SMARTTRACK_DB_USER='smarttrack'; $env:SMARTTRACK_DB_PASSWORD=$saved.app
 Write-Output "Workspace MySQL credentials loaded (port $Port)."
 return
}
if ($listening) { throw "Port $Port is already occupied. Choose another port; existing services will not be changed." }
if (Test-Path -LiteralPath $data) { if (-not $ResumeInitialization) { throw 'Data directory exists without credentials. Inspect it before using -ResumeInitialization.' } }
else {
 New-Item -ItemType Directory -Path $data | Out-Null
 $initArgs=@('--no-defaults',"--basedir=`"$MySqlHome`"","--datadir=`"$data`"",'--initialize-insecure','--console')
 $init=Start-Process -FilePath $server -ArgumentList $initArgs -WindowStyle Hidden -Wait -PassThru -RedirectStandardError (Join-Path $work 'mysql-init.log')
 if ($init.ExitCode -ne 0) { throw 'MySQL initialization failed; see .work/mysql-init.log' }
}
$serverArgs=@('--no-defaults',"--basedir=`"$MySqlHome`"","--datadir=`"$data`"",'--bind-address=127.0.0.1',"--port=$Port",'--mysqlx=0','--skip-log-bin',"--log-error=`"$(Join-Path $work 'mysql-error.log')`"")
$process=Start-Process -FilePath $server -ArgumentList $serverArgs -WindowStyle Hidden -PassThru
Set-Content -LiteralPath (Join-Path $work 'mysql.pid') -Value $process.Id
$ready=$false
for($attempt=0;$attempt -lt 30;$attempt++) { try { $tcp=New-Object Net.Sockets.TcpClient; $tcp.Connect('127.0.0.1',$Port);$tcp.Dispose();$ready=$true;break } catch { Start-Sleep -Milliseconds 500 } }
if (-not $ready) { throw 'Workspace MySQL did not become ready' }
$actualData = & $client --no-defaults --host=127.0.0.1 "--port=$Port" --user=root --batch --skip-column-names '--execute=SELECT @@datadir'
if ($LASTEXITCODE -ne 0 -or [IO.Path]::GetFullPath($actualData.Trim()).TrimEnd('\','/') -ne [IO.Path]::GetFullPath($data).TrimEnd('\','/')) { throw 'MySQL data directory does not match the workspace; refusing account setup.' }
function New-LocalSecret { $bytes=New-Object byte[] 32;[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes);[Convert]::ToBase64String($bytes) }
$rootSecret=New-LocalSecret; $appSecret=New-LocalSecret
$sql="ALTER USER 'root'@'localhost' IDENTIFIED BY '$rootSecret'; CREATE DATABASE smarttrack CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; CREATE USER 'smarttrack'@'127.0.0.1' IDENTIFIED BY '$appSecret'; GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,REFERENCES,DROP ON smarttrack.* TO 'smarttrack'@'127.0.0.1';"
$sql | & $client --no-defaults --host=127.0.0.1 "--port=$Port" --user=root
if ($LASTEXITCODE -ne 0) { throw 'Workspace MySQL account setup failed' }
@{root=$rootSecret;app=$appSecret;port=$Port} | ConvertTo-Json | Set-Content -LiteralPath $credentials
& icacls $credentials /inheritance:r /grant:r "$($env:USERNAME):(F)" | Out-Null
$env:SMARTTRACK_DB_URL="jdbc:mysql://127.0.0.1:$Port/smarttrack?connectionTimeZone=UTC"
$env:SMARTTRACK_DB_USER='smarttrack';$env:SMARTTRACK_DB_PASSWORD=$appSecret
Write-Output "Started isolated workspace MySQL on 127.0.0.1:$Port. Credentials are excluded from Git."
