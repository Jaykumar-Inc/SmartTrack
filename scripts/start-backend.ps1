param([switch]$Demo,[switch]$LocalMySql)
. "$PSScriptRoot\toolchain.ps1"
Push-Location $projectRoot
try {
 if ($LocalMySql) { & "$PSScriptRoot\start-local-mysql.ps1" }
 $arguments=@('-jar','backend\target\backend-1.0.0-SNAPSHOT.jar')
 if ($Demo) { $arguments += '--spring.profiles.active=demo' }
 & "$env:JAVA_HOME\bin\java.exe" @arguments
} finally {Pop-Location}
