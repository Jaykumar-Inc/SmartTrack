param([ValidateRange(1024,65535)][int]$Port=8089)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$pidFile=Join-Path $projectRoot '.work\integration-backend.pid'
if(-not(Test-Path -LiteralPath $pidFile)){return}
$testServerId=[int](Get-Content -LiteralPath $pidFile)
$testProcess=Get-CimInstance Win32_Process -Filter "ProcessId=$testServerId" -ErrorAction SilentlyContinue
if($testProcess){
 $jar=Join-Path $projectRoot '.work\runtime\smarttrack-integration-backend.jar'
 if(-not $testProcess.CommandLine.Contains($jar) -or -not $testProcess.CommandLine.Contains("--server.port=$Port") -or -not $testProcess.CommandLine.Contains('--spring.datasource.url=jdbc:h2:mem:')){throw 'Process is not the isolated in-memory test backend; nothing stopped'}
 Stop-Process -Id $testServerId
}
Remove-Item -LiteralPath $pidFile
Write-Output 'Stopped the isolated in-memory backend; main MySQL/backend services were preserved.'
