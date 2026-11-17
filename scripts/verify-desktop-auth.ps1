. "$PSScriptRoot\toolchain.ps1"
Push-Location $projectRoot
try {
    & $Maven -ntp -pl desktop -am test-compile -DskipTests '-Dstyle.color=never'
    if ($LASTEXITCODE -ne 0) { throw 'Desktop auth harness compilation failed' }
    Remove-Item Env:SMARTTRACK_USER_TOKEN -ErrorAction SilentlyContinue
    $env:SMARTTRACK_BACKEND = 'http://127.0.0.1:8087'
    $javaFxModules = ((Get-ChildItem -LiteralPath 'desktop\target\lib' -Filter 'javafx-*-win.jar').FullName -join ';')
    & "$env:JAVA_HOME\bin\java.exe" --module-path $javaFxModules --add-modules javafx.controls --enable-native-access=javafx.graphics -cp 'desktop\target\test-classes;desktop\target\classes;desktop\target\lib\*' io.smarttrack.desktop.DesktopAuthSmoke
    if ($LASTEXITCODE -ne 0) { throw 'Desktop auth UI verification failed' }
} finally { Pop-Location }
