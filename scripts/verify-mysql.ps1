. "$PSScriptRoot\toolchain.ps1"
& "$PSScriptRoot\start-local-mysql.ps1"
$env:SPRING_DATASOURCE_URL=$env:SMARTTRACK_DB_URL
$env:SPRING_DATASOURCE_USERNAME=$env:SMARTTRACK_DB_USER
$env:SPRING_DATASOURCE_PASSWORD=$env:SMARTTRACK_DB_PASSWORD
Push-Location $projectRoot
try { & $Maven -ntp -pl backend -am test '-Dstyle.color=never'; if ($LASTEXITCODE -ne 0) {throw 'MySQL integration tests failed'} } finally {Pop-Location;Remove-Item Env:SPRING_DATASOURCE_URL,Env:SPRING_DATASOURCE_USERNAME,Env:SPRING_DATASOURCE_PASSWORD -ErrorAction SilentlyContinue}
