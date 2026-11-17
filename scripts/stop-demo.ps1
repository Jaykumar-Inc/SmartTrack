$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$credentials=Join-Path $projectRoot '.work\mysql-credentials.json'
if(Test-Path -LiteralPath $credentials){
 $saved=Get-Content -LiteralPath $credentials -Raw | ConvertFrom-Json
 $client='C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
 $priorPassword=$env:MYSQL_PWD
 try{
  $env:MYSQL_PWD=$saved.root
  $actualData=& $client --no-defaults --host=127.0.0.1 "--port=$($saved.port)" --user=root --batch --skip-column-names '--execute=SELECT @@datadir'
  $expected=Join-Path $projectRoot '.tools\mysql-data'
  if($LASTEXITCODE -eq 0 -and [IO.Path]::GetFullPath($actualData.Trim()).TrimEnd('\','/') -eq [IO.Path]::GetFullPath($expected).TrimEnd('\','/')){
   & 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqladmin.exe' --no-defaults --host=127.0.0.1 "--port=$($saved.port)" --user=root shutdown
  }else{throw 'Database identity did not match; no process stopped'}
 }finally{$env:MYSQL_PWD=$priorPassword}
}
$pidFile=Join-Path $projectRoot '.work\backend.pid'
if(Test-Path -LiteralPath $pidFile){
 $serverPid=[int](Get-Content -LiteralPath $pidFile)
 $serverProcess=Get-CimInstance Win32_Process -Filter "ProcessId=$serverPid" -ErrorAction SilentlyContinue
 if($serverProcess -and ($serverProcess.CommandLine.Contains((Join-Path $projectRoot 'backend\target\backend-1.0.0-SNAPSHOT.jar')) -or $serverProcess.CommandLine.Contains((Join-Path $projectRoot '.work\runtime\smarttrack-backend.jar')))){Stop-Process -Id $serverPid}
 else{Write-Output 'Recorded backend PID is no longer the workspace backend.'}
}
