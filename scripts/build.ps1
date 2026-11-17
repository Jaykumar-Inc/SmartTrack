param([switch]$Offline)
. "$PSScriptRoot\toolchain.ps1"
Push-Location $projectRoot
try { $buildArguments = @('-ntp','verify','-Dstyle.color=never'); if ($Offline) { $buildArguments += '-o' }; & $Maven @buildArguments; if ($LASTEXITCODE -ne 0) { throw 'Maven build failed' } } finally { Pop-Location }
