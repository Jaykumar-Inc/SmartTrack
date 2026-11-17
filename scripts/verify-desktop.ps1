. "$PSScriptRoot\toolchain.ps1"
$session=Get-Content -LiteralPath (Join-Path $projectRoot '.work\demo-session.json') -Raw | ConvertFrom-Json
$env:SMARTTRACK_USER_TOKEN=$session.userToken;$env:SMARTTRACK_BACKEND='http://127.0.0.1:8087'
$env:SMARTTRACK_DEVICE_TOKEN=$session.deviceToken
$simulator=Start-Process -FilePath (Join-Path $projectRoot '.tools\pio-env\Scripts\python.exe') -ArgumentList @('scripts/simulator.py','--base',$env:SMARTTRACK_BACKEND,'--device',$session.deviceId,'--count','32','--interval','0.5') -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $projectRoot '.work\live-simulator.log') -RedirectStandardError (Join-Path $projectRoot '.work\live-simulator-error.log')
$swingCache=Join-Path $env:USERPROFILE '.m2\repository\org\openjfx\javafx-swing\21.0.9'
$classpath="desktop\target\test-classes;desktop\target\classes;desktop\target\lib\*;$swingCache\*"
Push-Location $projectRoot
try{$javaFxModules=((Get-ChildItem -LiteralPath 'desktop\target\lib' -Filter 'javafx-*-win.jar').FullName -join ';');& "$env:JAVA_HOME\bin\java.exe" --module-path $javaFxModules --add-modules javafx.controls --enable-native-access=javafx.graphics -cp $classpath io.smarttrack.desktop.DesktopSmoke;if($LASTEXITCODE -ne 0){throw 'Desktop smoke test failed'};$simulator.WaitForExit(30000)|Out-Null}finally{Pop-Location;Remove-Item Env:SMARTTRACK_USER_TOKEN,Env:SMARTTRACK_DEVICE_TOKEN -ErrorAction SilentlyContinue}
