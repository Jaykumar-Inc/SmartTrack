$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$localJdk = Get-ChildItem -LiteralPath (Join-Path $projectRoot '.tools') -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue | Select-Object -First 1
if ($localJdk) { $env:JAVA_HOME = $localJdk.FullName }
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to a Java 21 JDK.' }
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$localMaven = Join-Path $projectRoot '.tools\apache-maven-3.9.11\bin\mvn.cmd'
$script:Maven = if (Test-Path -LiteralPath $localMaven) { $localMaven } else { (Get-Command mvn.cmd -ErrorAction Stop).Source }
