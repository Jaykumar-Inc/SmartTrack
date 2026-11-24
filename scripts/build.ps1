param([switch]$Android,[switch]$Firmware)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$toolRoot=Join-Path (Split-Path $projectRoot -Parent) '.tools'
if (-not $env:JAVA_HOME) {
 $jdk=Get-ChildItem -LiteralPath $toolRoot -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue | Select-Object -First 1
 if ($jdk) { $env:JAVA_HOME=$jdk.FullName }
}
$maven=Join-Path $toolRoot 'apache-maven-3.9.11\bin\mvn.cmd'
if (-not (Test-Path -LiteralPath $maven)) { $maven='mvn.cmd' }
& $maven -f (Join-Path $projectRoot 'pom.xml') clean package
if ($LASTEXITCODE -ne 0) { throw 'Java build failed' }
if ($Android) {
 $gradle=Join-Path $toolRoot 'gradle-8.13\bin\gradle.bat'
 & $gradle -p (Join-Path $projectRoot 'smarttrack-android') assembleDebug
 if ($LASTEXITCODE -ne 0) { throw 'Android build failed' }
}
if ($Firmware) {
 python -m platformio run -d (Join-Path $projectRoot 'esp32-firmware') -e integrated -e scanner -e max30102 -e lsm6ds3 -e oled
 if ($LASTEXITCODE -ne 0) { throw 'Firmware build failed' }
}
