param([string]$SketchDirectory='E:\COA\PROJECT\firmware\uno_led_display')
$ErrorActionPreference='Stop'
$source=Join-Path (Split-Path $PSScriptRoot -Parent) 'esp32-firmware\SmartTrack'
$target=Join-Path $SketchDirectory 'uno_led_display.ino'
if(-not(Test-Path -LiteralPath $target)){throw 'Original SmartTrack sketch not found'}
$backup=$target+'.before-wifi-only.bak'
if(-not(Test-Path -LiteralPath $backup)){Copy-Item -LiteralPath $target -Destination $backup}
Copy-Item -LiteralPath (Join-Path $source 'SmartTrack.ino') -Destination $target
Get-ChildItem -LiteralPath $source -Filter '*.h' | Where-Object Name -ne 'smarttrack_config.h' | ForEach-Object {Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $SketchDirectory $_.Name)}
Write-Output 'Wi-Fi source synchronized; private configuration preserved. Use UPLOAD_ESP32.cmd to flash.'
