param([switch]$Release)
$ErrorActionPreference='Stop'
$env:DEBUG=''
. "$PSScriptRoot\toolchain.ps1"
$gradle = Join-Path $projectRoot '.tools\gradle-8.13\bin\gradle.bat'
if (-not (Test-Path -LiteralPath $gradle)) { $gradle = (Get-Command gradle.bat -ErrorAction Stop).Source }
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if ($Release -and -not $env:SMARTTRACK_KEYSTORE) { throw 'Set SMARTTRACK_KEYSTORE and signing environment variables for a signed release.' }
Push-Location (Join-Path $projectRoot 'android')
try { $task = if ($Release) {'assembleRelease'} else {'assembleDebug'}; & $gradle --no-daemon --console=plain $task testDebugUnitTest lintDebug; if ($LASTEXITCODE -ne 0) { throw 'Android verification failed' } } finally { Pop-Location }
