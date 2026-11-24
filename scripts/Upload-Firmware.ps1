param([Parameter(Mandatory)] [ValidatePattern('^COM\d+$')] [string]$Port)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$firmware=Join-Path $projectRoot 'esp32-firmware'
$environment='integrated-wifi'
if (-not (Test-Path -LiteralPath (Join-Path $firmware 'SmartTrack\smarttrack_config.h'))) { throw 'Run local setup or configure your private Wi-Fi settings before uploading' }
python -m platformio run -d $firmware -e $environment -t upload --upload-port $Port
if($LASTEXITCODE -ne 0){throw 'Upload failed'}
python -m platformio device monitor -p $Port -b 115200
