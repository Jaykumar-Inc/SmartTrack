param([switch]$Demo)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$jdk=Get-ChildItem -LiteralPath (Join-Path (Split-Path $projectRoot -Parent) '.tools') -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue | Select-Object -First 1
$java=if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } elseif ($jdk) { Join-Path $jdk.FullName 'bin\java.exe' } else { 'java.exe' }
if (-not $env:SMARTTRACK_USER) { $env:SMARTTRACK_USER=Read-Host 'Bootstrap username' }
if (-not $env:SMARTTRACK_PASSWORD) {
 $secure=Read-Host 'Bootstrap password (12+ characters on first run)' -AsSecureString
 $env:SMARTTRACK_PASSWORD=[System.Net.NetworkCredential]::new('', $secure).Password
}
Push-Location (Join-Path $projectRoot 'smarttrack-backend')
try {
 if ($Demo) { & $java -jar target\smarttrack-backend-1.0.0.jar --spring.profiles.active=demo }
 else { & $java -jar target\smarttrack-backend-1.0.0.jar }
} finally { Pop-Location; Remove-Item Env:SMARTTRACK_PASSWORD -ErrorAction SilentlyContinue }
