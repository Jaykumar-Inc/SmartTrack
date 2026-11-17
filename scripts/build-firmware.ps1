param([switch]$Upload,[string]$Port)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$env:PLATFORMIO_CORE_DIR=Join-Path $projectRoot '.tools\platformio'
$python=Join-Path $projectRoot '.tools\pio-env\Scripts\python.exe'
if (-not (Test-Path -LiteralPath $python)) { $python=(Get-Command python -ErrorAction Stop).Source }
$buildArguments=@('-m','platformio','run','-d',(Join-Path $projectRoot 'firmware\esp32'),'-e','esp32dev')
if($Upload){if(-not $Port){throw 'Specify the ESP32 COM port with -Port'};$buildArguments+=@('-t','upload','--upload-port',$Port)}
& $python @buildArguments
if($LASTEXITCODE -ne 0){throw 'Firmware build failed'}
