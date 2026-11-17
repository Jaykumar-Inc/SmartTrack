. "$PSScriptRoot\toolchain.ps1"
Push-Location $projectRoot
try { $javaFxModules=((Get-ChildItem -LiteralPath 'desktop\target\lib' -Filter 'javafx-*-win.jar').FullName -join ';'); & "$env:JAVA_HOME\bin\java.exe" --module-path $javaFxModules --add-modules javafx.controls --enable-native-access=javafx.graphics -cp 'desktop\target\desktop-1.0.0-SNAPSHOT.jar;desktop\target\lib\*' io.smarttrack.desktop.Launcher } finally { Pop-Location }
