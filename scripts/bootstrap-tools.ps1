# Workspace-local tool bootstrap. Does not change PATH persistently or install services.
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$toolsDirectory=Join-Path $projectRoot '.tools'
New-Item -ItemType Directory -Force -Path $toolsDirectory | Out-Null
function Install-VerifiedArchive([string]$Url,[string]$ChecksumUrl,[string]$Name,[string]$Algorithm,[string]$ExpectedDirectory){
 if(Test-Path -LiteralPath (Join-Path $toolsDirectory $ExpectedDirectory)){return}
 $archive=Join-Path $toolsDirectory $Name
 Invoke-WebRequest -Uri $Url -OutFile $archive
 $response=Invoke-WebRequest -Uri $ChecksumUrl -UseBasicParsing
 $checksumText=if($response.Content -is [byte[]]){[Text.Encoding]::UTF8.GetString($response.Content)}else{[string]$response.Content}
 $checksum=($checksumText.Trim() -split '\s+')[0]
 $actual=(Get-FileHash -LiteralPath $archive -Algorithm $Algorithm).Hash
 if(-not $actual.Equals($checksum,[StringComparison]::OrdinalIgnoreCase)){throw "Checksum mismatch for $Name"}
 Expand-Archive -LiteralPath $archive -DestinationPath $toolsDirectory -Force
}
Install-VerifiedArchive 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.zip' 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.zip.sha512' 'maven.zip' 'SHA512' 'apache-maven-3.9.11'
Install-VerifiedArchive 'https://services.gradle.org/distributions/gradle-8.13-bin.zip' 'https://services.gradle.org/distributions/gradle-8.13-bin.zip.sha256' 'gradle.zip' 'SHA256' 'gradle-8.13'
$jdkUrl='https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/OpenJDK21U-jdk_x64_windows_hotspot_21.0.13_9.zip'
Install-VerifiedArchive $jdkUrl "$jdkUrl.sha256.txt" 'jdk21.zip' 'SHA256' 'jdk-21.0.13+9'
$venv=Join-Path $toolsDirectory 'pio-env'
if(-not(Test-Path -LiteralPath (Join-Path $venv 'Scripts\python.exe'))){python -m venv $venv;if($LASTEXITCODE -ne 0){throw 'Python venv creation failed'}}
& (Join-Path $venv 'Scripts\python.exe') -m pip install -r (Join-Path $projectRoot 'requirements-dev.txt')
if($LASTEXITCODE -ne 0){throw 'PlatformIO/test dependency installation failed'}
Write-Output 'Workspace Java 21, Maven, Gradle, PlatformIO and schema-test tools are ready. Android SDK and MySQL must be installed separately.'
