. "$PSScriptRoot\toolchain.ps1"
$env:DEBUG=''
if(-not $env:ANDROID_HOME){$env:ANDROID_HOME=Join-Path $env:LOCALAPPDATA 'Android\Sdk'}
$gradle=Join-Path $projectRoot '.tools\gradle-8.13\bin\gradle.bat'
if(-not(Test-Path -LiteralPath $gradle)){$gradle=(Get-Command gradle.bat -ErrorAction Stop).Source}
Push-Location (Join-Path $projectRoot 'android')
try{& $gradle --no-daemon --console=plain connectedDebugAndroidTest;if($LASTEXITCODE -ne 0){throw 'Android device test failed'}}finally{Pop-Location}
