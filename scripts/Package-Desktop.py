"""Build a Windows x64 app image with Java bundled; no credentials are packaged."""
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
import json, shutil, subprocess, tempfile
import smarttrack_local as local

root = Path(__file__).resolve().parents[1]
tools = root.parent / '.tools'
work = Path(tempfile.mkdtemp(prefix='smarttrack-windows-', dir=tools)).resolve()
assert work.is_relative_to(tools.resolve())
jar, dependencies = local.desktop_files()
jdk = local.java().parent.parent
staging = work / 'input'
staging.mkdir()
shutil.copy2(jar, staging / 'SmartTrack-desktop.jar')
for dependency in dependencies:
    source = Path(dependency)
    target = staging / source.name
    if target.exists():
        assert target.read_bytes() == source.read_bytes(), 'Conflicting dependency names'
    else:
        shutil.copy2(source, target)
runtime = work / 'runtime'
with (root / 'tests/windows-runtime-build.log').open('w') as log:
    result = subprocess.run([
        str(jdk / 'bin/jlink.exe'), '--module-path', str(jdk / 'jmods'),
        '--add-modules', 'java.se,jdk.crypto.ec,jdk.unsupported,jdk.charsets',
        '--strip-debug', '--no-header-files', '--no-man-pages', '--compress=2',
        '--output', str(runtime)], stdout=log, stderr=subprocess.STDOUT)
assert result.returncode == 0, 'jlink failed; see tests/windows-runtime-build.log'
output = work / 'output'
with (root / 'tests/windows-app-image-build.log').open('w') as log:
    result = subprocess.run([
        str(jdk / 'bin/jpackage.exe'), '--type', 'app-image', '--name', 'SmartTrack',
        '--app-version', '3.1.0', '--vendor', 'SmartTrack Project',
        '--description', 'SmartTrack Wi-Fi desktop health, motion and chip temperature monitor',
        '--input', str(staging), '--main-jar', 'SmartTrack-desktop.jar',
        '--main-class', 'org.smarttrack.desktop.DesktopLauncher',
        '--runtime-image', str(runtime), '--java-options', '-Dfile.encoding=UTF-8',
        '--dest', str(output)], stdout=log, stderr=subprocess.STDOUT)
assert result.returncode == 0, 'jpackage failed; see tests/windows-app-image-build.log'
app = output / 'SmartTrack'
(app/'Start SmartTrack.cmd').write_text('@echo off\ncd /d "%~dp0"\nstart "" "%~dp0SmartTrack.exe"\n',encoding='utf-8')
(app/'README.txt').write_text("""SMARTTRACK WI-FI DESKTOP 3.1.0
Extract the full ZIP and run SmartTrack.exe. Java is included.
Start the existing project's START_SMARTTRACK.cmd for its backend/MySQL.
Connect phone and ESP32 to the laptop's JAY hotspot (2.4 GHz).
Server for both apps: http://192.168.137.1:8080 (check SHOW_LOGIN.cmd).
Sign into both apps with the same username/password from SHOW_LOGIN.cmd.
No pairing, serial data port or separate phone database is required.
This ZIP supplies the desktop client/Java; the local server runs separately.
Java licenses are in runtime/legal; dependency metadata is in its JARs.
""",encoding='utf-8')
archive = root / 'deliverables/SmartTrack-Windows.zip'
with ZipFile(archive, 'w', ZIP_DEFLATED, compresslevel=6) as bundle:
    for file in sorted(app.rglob('*')):
        if file.is_file():
            bundle.write(file, Path('SmartTrack') / file.relative_to(app))
metadata = {
    'platform': 'Windows x64', 'type': 'portable app image', 'java_bundled': True,
    'app_version': '3.1.0', 'archive': archive.name,
    'archive_bytes': archive.stat().st_size, 'app_image_path': str(app),
    'requires': 'Existing local SmartTrack backend/MySQL over Wi-Fi'}
(root / 'tests/windows-package-metadata.json').write_text(
    json.dumps(metadata, indent=2), encoding='utf-8')
print('Created', archive.name, archive.stat().st_size, 'bytes')
print('App image:', app)
