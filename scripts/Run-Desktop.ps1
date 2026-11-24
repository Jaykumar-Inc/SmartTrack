$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$toolRoot=Join-Path (Split-Path $projectRoot -Parent) '.tools'
if (-not $env:JAVA_HOME) { $jdk=Get-ChildItem -LiteralPath $toolRoot -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue | Select-Object -First 1; if ($jdk) { $env:JAVA_HOME=$jdk.FullName } }
$maven=Join-Path $toolRoot 'apache-maven-3.9.11\bin\mvn.cmd'
if (-not (Test-Path -LiteralPath $maven)) { $maven='mvn.cmd' }
& $maven -f (Join-Path $projectRoot 'smarttrack-desktop\pom.xml') javafx:run
