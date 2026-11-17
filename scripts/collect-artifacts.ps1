$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$dist=Join-Path $projectRoot 'dist'
$firmware=Join-Path $dist 'firmware'
New-Item -ItemType Directory -Force -Path $dist,$firmware | Out-Null
Copy-Item -LiteralPath (Join-Path $projectRoot 'backend\target\backend-1.0.0-SNAPSHOT.jar') -Destination (Join-Path $dist 'smarttrack-backend-1.0.0-preview.jar') -Force
Copy-Item -LiteralPath (Join-Path $projectRoot 'android\app\build\outputs\apk\debug\app-debug.apk') -Destination (Join-Path $dist 'SmartTrack-Android-debug.apk') -Force
$apkMetadata=Get-Content -LiteralPath (Join-Path $projectRoot 'android\app\build\outputs\apk\debug\output-metadata.json') -Raw | ConvertFrom-Json
$apkVersion=[string]$apkMetadata.elements[0].versionName
if($apkVersion -notmatch '^\d+\.\d+\.\d+$'){throw 'Unexpected APK version; refusing to construct artifact filename'}
Copy-Item -LiteralPath (Join-Path $dist 'SmartTrack-Android-debug.apk') -Destination (Join-Path $dist "SmartTrack-Android-$apkVersion-debug.apk") -Force
foreach($name in @('firmware.bin','bootloader.bin','partitions.bin','firmware.elf')){Copy-Item -LiteralPath (Join-Path $projectRoot "firmware\esp32\.pio\build\esp32dev\$name") -Destination $firmware -Force}
$firmwareRoot=Join-Path $projectRoot 'firmware\esp32'
$sourcePaths=@('platformio.ini','README.md','src','include','test') | ForEach-Object {Join-Path $firmwareRoot $_}
Compress-Archive -LiteralPath $sourcePaths -DestinationPath (Join-Path $dist 'SmartTrack-ESP32-source.zip') -Force
$manifest=Get-ChildItem -LiteralPath $dist -File | Where-Object Name -NE 'SHA256SUMS.txt' | Sort-Object Name | ForEach-Object { $hash=Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256;"$($hash.Hash.ToLowerInvariant())  $($_.Name)" }
$manifest+=Get-ChildItem -LiteralPath $firmware -File | Sort-Object Name | ForEach-Object { $hash=Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256;"$($hash.Hash.ToLowerInvariant())  firmware/$($_.Name)" }
$manifest | Set-Content -LiteralPath (Join-Path $dist 'SHA256SUMS.txt') -Encoding ASCII
Write-Output 'Collected backend, debug APK and ESP32 artifacts; wrote dist/SHA256SUMS.txt.'
