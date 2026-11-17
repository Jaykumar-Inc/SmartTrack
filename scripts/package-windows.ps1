param([ValidateSet('app-image','exe','msi')][string]$Type='app-image')
. "$PSScriptRoot\toolchain.ps1"
$dist=Join-Path $projectRoot 'dist'
$inputDirectory=Join-Path $projectRoot '.work\package-input'
New-Item -ItemType Directory -Force -Path $inputDirectory,$dist | Out-Null
$packageTarget=if($Type -eq 'app-image'){Join-Path $dist 'SmartTrack'}else{Join-Path $dist "SmartTrack-1.0.2.$Type"}
if(Test-Path -LiteralPath $packageTarget){
 $resolvedTarget=(Resolve-Path -LiteralPath $packageTarget).Path
 $allowedRoot=[IO.Path]::GetFullPath($dist).TrimEnd('\')+'\'
 if(-not $resolvedTarget.StartsWith($allowedRoot,[StringComparison]::OrdinalIgnoreCase)){throw 'Package target escaped the build output directory'}
 if($Type -eq 'app-image'){Remove-Item -LiteralPath $resolvedTarget -Recurse -Force}else{Remove-Item -LiteralPath $resolvedTarget -Force}
}
if(Test-Path -LiteralPath (Join-Path $projectRoot '.tools\wix')){$env:PATH="$(Join-Path $projectRoot '.tools\wix');$env:PATH"}
Copy-Item -LiteralPath (Join-Path $projectRoot 'desktop\target\desktop-1.0.0-SNAPSHOT.jar') -Destination $inputDirectory
Get-ChildItem -LiteralPath (Join-Path $projectRoot 'desktop\target\lib') -Filter '*.jar' | Copy-Item -Destination $inputDirectory
$javaFxModules=((Get-ChildItem -LiteralPath (Join-Path $projectRoot 'desktop\target\lib') -Filter 'javafx-*-win.jar').FullName -join ';')
$packageArguments=@('--type',$Type,'--name','SmartTrack','--icon',(Join-Path $projectRoot 'design-system\icons\smarttrack.ico'),'--input',$inputDirectory,'--main-jar','desktop-1.0.0-SNAPSHOT.jar','--main-class','io.smarttrack.desktop.Launcher','--app-version','1.0.2','--vendor','SmartTrack','--description','Clarity in every signal.','--dest',$dist,'--module-path',$javaFxModules,'--java-options','--enable-native-access=javafx.graphics','--java-options','--add-modules=javafx.controls','--add-modules','java.base,java.desktop,java.logging,java.net.http,java.prefs,jdk.unsupported,jdk.crypto.ec,javafx.controls')
if($Type -in @('exe','msi')){$packageArguments+=@('--win-dir-chooser','--win-menu','--win-shortcut','--win-per-user-install','--win-upgrade-uuid','7118998f-e94b-4a7a-95b1-711b6eb2f2c2')}
& "$env:JAVA_HOME\bin\jpackage.exe" @packageArguments
if($LASTEXITCODE -ne 0){throw 'jpackage failed. EXE creation requires WiX 3 candle.exe and light.exe on PATH.'}
Write-Output "Created $Type in $dist"
