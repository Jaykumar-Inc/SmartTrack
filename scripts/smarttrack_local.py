"""Windows local deployment: real MySQL + backend + JavaFX + private wearable config.

No credentials are embedded in this source. Runtime files stay under ignored .local/.
Existing MySQL installations and databases are never modified.
"""
from __future__ import annotations

import argparse
import base64
import contextlib
import ctypes
from ctypes import wintypes
import csv
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
LOCAL = ROOT / '.local'
TOOLS = ROOT.parent / '.tools'
PROFILE = LOCAL / 'profile.json'
SECRETS = LOCAL / 'credentials.json'
STATE = LOCAL / 'processes.json'
FLAGS = (subprocess.CREATE_NO_WINDOW | subprocess.CREATE_NEW_PROCESS_GROUP) if os.name == 'nt' else 0


def save(path: Path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + '.tmp')
    temporary.write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8')
    temporary.replace(path)


def load(path: Path, default=None):
    return json.loads(path.read_text(encoding='utf-8-sig')) if path.exists() else default


def powershell(code: str):
    result = subprocess.run(['powershell.exe', '-NoProfile', '-NonInteractive', '-Command', code],
                            capture_output=True, text=True, creationflags=FLAGS)
    if result.returncode:
        raise RuntimeError(result.stderr.strip() or 'Windows command failed')
    return result.stdout.strip()


def private_directory():
    LOCAL.mkdir(exist_ok=True)
    (LOCAL / 'logs').mkdir(exist_ok=True)
    if not (LOCAL / 'permissions-set').exists():
        identity = subprocess.run(['whoami', '/user', '/fo', 'csv', '/nh'], capture_output=True,
                                  text=True, creationflags=FLAGS, check=True)
        sid = next(csv.reader([identity.stdout.strip()]))[1]
        if not re.fullmatch(r'S-1-[\d-]+', sid):
            raise RuntimeError('Cannot identify the Windows account for private runtime files')
        result = subprocess.run(['icacls', str(LOCAL), '/inheritance:r', '/grant:r',
                                 f'*{sid}:(OI)(CI)F', '*S-1-5-18:(OI)(CI)F', '*S-1-5-32-544:(OI)(CI)F'],
                                capture_output=True, text=True, creationflags=FLAGS)
        if result.returncode:
            raise RuntimeError('Cannot protect .local credentials with Windows file permissions')
        (LOCAL / 'permissions-set').write_text('Current Windows user, SYSTEM and Administrators only.\n')


@contextlib.contextmanager
def deployment_lock():
    import msvcrt
    with (LOCAL / 'deployment.lock').open('a+b') as handle:
        if handle.tell() == 0:
            handle.write(b'0'); handle.flush()
        handle.seek(0)
        try:
            msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
        except OSError as error:
            raise RuntimeError('Another SmartTrack setup/start/stop is running. Wait for it to finish.') from error
        try:
            yield
        finally:
            handle.seek(0); msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)


def network():
    # Detect the laptop Mobile Hotspot interface, excluding its university uplink.
    code=r"""$rows=@();foreach($n in [Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces()) {
if($n.OperationalStatus -ne 'Up' -or $n.Description -notlike '*Wi-Fi Direct*'){continue}
foreach($a in $n.GetIPProperties().UnicastAddresses) {
if($a.Address.AddressFamily -eq 'InterNetwork' -and $a.Address.ToString() -notlike '169.254.*') {
$rows+=@{ip=$a.Address.ToString();mask=$a.IPv4Mask.ToString();adapter=$n.Name}}}}
ConvertTo-Json -InputObject @($rows) -Compress"""
    rows=json.loads(powershell(code) or '[]')
    if len(rows)!=1:return {'ip':'127.0.0.1','ready':False,'subnet':'127.0.0.0/8','adapter':''}
    result=rows[0];address=ipaddress.ip_address(result['ip'])
    if not address.is_private or address.is_loopback:raise RuntimeError('The hotspot must use a private local address.')
    result['ready']=True;result['subnet']=str(ipaddress.IPv4Network(f"{result['ip']}/{result['mask']}",strict=False))
    return result


def configuration():
    profile, credentials = load(PROFILE), load(SECRETS)
    if not profile or not credentials:
        if (LOCAL / 'mysql' / 'auto.cnf').exists():
            raise RuntimeError('Existing local database found but its profile/credentials are missing. Restore those files; no data or password will be reset.')
        profile = {'version': 1, 'backend_port': 8080, 'mysql_port': 3308,
                   'username': 'smarttrack', 'device_id': 'esp32-' + secrets.token_hex(6),
                   'original_sketch': r'E:\COA\PROJECT\firmware\uno_led_display'}
        credentials = {'app_password': secrets.token_urlsafe(18),
                       'mysql_root_password': secrets.token_hex(20),
                       'mysql_password': secrets.token_hex(20)}
        save(PROFILE, profile); save(SECRETS, credentials)
    wifi=load(LOCAL/'wifi.json',{})
    if not wifi.get('ssid') or not wifi.get('password'):
        raise RuntimeError('Run CONFIGURE_WIFI.cmd to save the laptop hotspot settings privately.')
    return profile,credentials,wifi



def fingerprint(pid):
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    kernel.OpenProcess.restype = wintypes.HANDLE
    kernel.GetProcessTimes.argtypes = [wintypes.HANDLE] + [ctypes.POINTER(wintypes.FILETIME)] * 4
    kernel.GetExitCodeProcess.argtypes = [wintypes.HANDLE, ctypes.POINTER(wintypes.DWORD)]
    kernel.CloseHandle.argtypes = [wintypes.HANDLE]
    handle = kernel.OpenProcess(0x1000, False, int(pid))
    if not handle:
        return None
    try:
        status = wintypes.DWORD()
        if not kernel.GetExitCodeProcess(handle, ctypes.byref(status)) or status.value != 259:
            return None
        times = [wintypes.FILETIME() for _ in range(4)]
        if not kernel.GetProcessTimes(handle, *(ctypes.byref(value) for value in times)):
            return None
        return (times[0].dwHighDateTime << 32) | times[0].dwLowDateTime
    finally:
        kernel.CloseHandle(handle)


def owned(name):
    record = load(STATE, {}).get(name)
    return bool(record and fingerprint(record['pid']) == record['created'])


def spawn(name, arguments, cwd=ROOT, env=None):
    log = LOCAL / 'logs' / f'{name}.log'
    with log.open('ab') as output:
        child = subprocess.Popen([str(value) for value in arguments], cwd=cwd,
                                 env=env, stdout=output, stderr=subprocess.STDOUT, creationflags=FLAGS)
    stamp = fingerprint(child.pid)
    if stamp is None:
        raise RuntimeError(f'{name} exited immediately. See .local/logs/{name}.log')
    state = load(STATE, {})
    state[name] = {'pid': child.pid, 'created': stamp}
    save(STATE, state)
    return child


def listening(port):
    try:
        with socket.create_connection(('127.0.0.1', port), timeout=0.3):
            return True
    except OSError:
        return False


def java():
    candidate = TOOLS / 'jdk-21.0.13+9' / 'bin' / 'java.exe'
    if candidate.exists():
        return candidate
    candidate = Path(os.environ.get('JAVA_HOME', '')) / 'bin' / 'java.exe'
    if candidate.exists():
        return candidate
    raise RuntimeError('Install/configure JDK 21 or restore the workspace .tools JDK.')


def mysql_tools():
    directory = Path(os.environ.get('SMARTTRACK_MYSQL_BIN', r'C:\Program Files\MySQL\MySQL Server 8.0\bin'))
    for executable in ('mysqld.exe', 'mysql.exe', 'mysqladmin.exe'):
        if not (directory / executable).exists():
            raise RuntimeError(f'Missing {executable}; set SMARTTRACK_MYSQL_BIN to your MySQL bin directory.')
    return directory


def mysql_client(profile, credentials, root=False):
    path = LOCAL / ('mysql-root.cnf' if root else 'mysql-client.cnf')
    user = 'root' if root else 'smarttrack'
    password = credentials['mysql_root_password' if root else 'mysql_password']
    path.write_text(f"[client]\nhost=127.0.0.1\nport={profile['mysql_port']}\nprotocol=TCP\nuser={user}\npassword={password}\n", encoding='utf-8')
    return [mysql_tools() / 'mysql.exe', f'--defaults-extra-file={path}']


def database_query(profile, credentials, sql, root=False):
    return subprocess.run([str(x) for x in mysql_client(profile, credentials, root)] + ['-N', '-e', sql],
                          capture_output=True, text=True, creationflags=FLAGS, timeout=8)


def start_database(profile, credentials):
    if owned('mysql'):
        if database_query(profile, credentials, 'SELECT 1').returncode:
            raise RuntimeError('The managed MySQL instance is running but authentication failed. Restore its private credentials.')
        print('MySQL already running on loopback port', profile['mysql_port']); return
    if listening(profile['mysql_port']):
        raise RuntimeError(f"Port {profile['mysql_port']} belongs to another process. Existing servers will not be stopped.")
    tools = mysql_tools(); data = LOCAL / 'mysql'; data.mkdir(exist_ok=True)
    if not (data / 'auto.cnf').exists():
        print('Initializing the separate project MySQL database... ', flush=True)
        with (LOCAL / 'logs' / 'mysql-initialize.log').open('ab') as output:
            result = subprocess.run([str(tools/'mysqld.exe'), '--no-defaults', '--initialize-insecure',
                                     f'--basedir={tools.parent}', f'--datadir={data}', '--console'],
                                    stdout=output, stderr=subprocess.STDOUT, creationflags=FLAGS)
        if result.returncode:
            raise RuntimeError('MySQL initialization failed; see .local/logs/mysql-initialize.log')
    # The initialize phase never listens. init-file sets passwords before accepting clients.
    init = LOCAL / 'mysql-bootstrap.sql'
    init.write_text(
        f"ALTER USER 'root'@'localhost' IDENTIFIED BY '{credentials['mysql_root_password']}';\n"
        "CREATE DATABASE IF NOT EXISTS smarttrack CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;\n"
        f"CREATE USER IF NOT EXISTS 'smarttrack'@'localhost' IDENTIFIED BY '{credentials['mysql_password']}';\n"
        "GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,DROP,INDEX,REFERENCES ON smarttrack.* TO 'smarttrack'@'localhost';\n", encoding='utf-8')
    mysql_client(profile, credentials, True)
    spawn('mysql', [tools/'mysqld.exe', '--no-defaults', f'--basedir={tools.parent}', f'--datadir={data}',
                    f"--port={profile['mysql_port']}", '--bind-address=127.0.0.1', '--mysqlx=OFF', f'--init-file={init}', '--console'])
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        if not owned('mysql'):
            raise RuntimeError('Project MySQL exited; see .local/logs/mysql.log')
        if listening(profile['mysql_port']) and database_query(profile, credentials, 'SELECT 1', True).returncode == 0:
            init.unlink(missing_ok=True)
            if database_query(profile, credentials, 'SELECT 1').returncode:
                raise RuntimeError('MySQL started but the application database account is unavailable.')
            print('Real MySQL ready; existing port 3306 installation unchanged.'); return
        time.sleep(0.5)
    raise RuntimeError('Timed out starting project MySQL; see .local/logs/mysql.log')


def api(profile, credentials, path, method='GET', data=None, device=False, ip=None):
    ip=ip or profile.get('server_ip','127.0.0.1')
    headers = {'Content-Type': 'application/json'}
    if device:
        headers['X-Device-Key'] = credentials['device_key']
    else:
        headers['Authorization'] = 'Basic ' + base64.b64encode(
            (profile['username'] + ':' + credentials['app_password']).encode()).decode()
    body = None if data is None else json.dumps(data).encode()
    request = urllib.request.Request(f"http://{ip}:{profile['backend_port']}/api{path}",
                                     data=body, headers=headers, method=method)
    with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(request, timeout=8) as response:
        return json.load(response)


def start_backend(profile, credentials):
    jar = ROOT / 'deliverables' / 'SmartTrack-backend.jar'
    if not jar.exists():
        raise RuntimeError('Missing deliverables/SmartTrack-backend.jar; build the backend first.')
    connection=network()
    bind=connection['ip'] if connection['ready'] else '127.0.0.1'
    profile['server_ip']=bind
    artifact_hash = hashlib.sha256(jar.read_bytes()+bind.encode()).hexdigest()
    if listening(profile['backend_port']):
        if not owned('backend'):
            raise RuntimeError(f"Port {profile['backend_port']} is already used by another server; it was left untouched.")
        if load(STATE,{}).get('backend',{}).get('artifact_sha256') == artifact_hash:
            api(profile, credentials, '/auth/me'); print('Backend already running.'); return
        record=load(STATE)['backend']
        subprocess.run(['taskkill','/PID',str(record['pid']),'/T','/F'],capture_output=True,creationflags=FLAGS,check=True)
        deadline=time.monotonic()+10
        while listening(profile['backend_port']) and time.monotonic()<deadline:time.sleep(.2)
        if listening(profile['backend_port']):raise RuntimeError('Previous managed backend did not release its port.')
        print('Restarting the updated managed backend; MySQL history retained.')
    env = os.environ.copy()
    env.update(DB_URL=f"jdbc:mysql://127.0.0.1:{profile['mysql_port']}/smarttrack?serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false",
               DB_USER='smarttrack', DB_PASSWORD=credentials['mysql_password'],
               SMARTTRACK_USER=profile['username'], SMARTTRACK_PASSWORD=credentials['app_password'],
               MOCK_ENABLED='false', PORT=str(profile['backend_port']), SERVER_ADDRESS=bind)
    env.pop('SPRING_PROFILES_ACTIVE', None)
    work = LOCAL / 'backend'; work.mkdir(exist_ok=True)
    spawn('backend', [java(), '-jar', jar, '--spring.profiles.active=default', '--smarttrack.mock-enabled=false',
                       '--debug=false', f'--server.address={bind}', '--logging.level.root=INFO'], cwd=work, env=env)
    state=load(STATE,{})
    state.setdefault('backend',{})['artifact_sha256']=artifact_hash;save(STATE,state)
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        if not owned('backend'):
            raise RuntimeError('Backend exited; see .local/logs/backend.log')
        try:
            api(profile, credentials, '/auth/me')
            print('Backend ready: authenticated REST + WebSocket; simulation OFF.'); return
        except urllib.error.HTTPError as error:
            # Tomcat accepts requests before ApplicationRunner finishes first-user provisioning.
            # An initial 401 is not evidence that the newly generated password is wrong.
            if error.code not in (401, 503):
                raise
        except (OSError, ValueError):
            pass
        time.sleep(0.5)
    raise RuntimeError('Timed out starting backend; see .local/logs/backend.log')


def register_wearable(profile, credentials):
    devices = api(profile, credentials, '/devices')
    matches = [d for d in devices if d['deviceId'] == profile['device_id']]
    if not matches:
        result = api(profile, credentials, '/devices', 'POST',
                     {'deviceId': profile['device_id'], 'name': 'SmartTrack Wi-Fi wearable', 'simulated': False})
        credentials['device_key'] = result['apiKey']; save(SECRETS, credentials)
    if matches and matches[0]['simulated']:
        raise RuntimeError('This device is registered as simulated; a real wearable registration is required.')
    print('Real Wi-Fi wearable registered:', profile['device_id'])


def render_header(text, settings):
    for name, value in settings.items():
        literal = json.dumps(value) if isinstance(value, str) else 'true' if value is True else 'false'
        line = f'#define {name} {literal}'
        pattern = rf'^\s*#define\s+{re.escape(name)}\b[^\r\n]*'
        if re.search(pattern, text, re.MULTILINE):
            text = re.sub(pattern, lambda match: line, text, flags=re.MULTILINE)
        else:
            text += '\n' + line + '\n'
    return text


def configure_firmware(profile,credentials,wifi=None,connection=None):
    wifi=wifi or load(LOCAL/'wifi.json',{})
    if not wifi.get('ssid') or not wifi.get('password') or not credentials.get('device_key'):
        raise RuntimeError('Wi-Fi/device provisioning is incomplete. Run START_SMARTTRACK.cmd.')
    canonical=ROOT/'esp32-firmware/SmartTrack'
    directories=[canonical];original=Path(profile['original_sketch'])
    if original.exists():directories.append(original)
    values={'DEVICE_ID':profile['device_id'],'WIFI_SSID':wifi['ssid'],'WIFI_PASSWORD':wifi['password'],'DEVICE_KEY':credentials['device_key']}
    for index,directory in enumerate(directories):
        header=directory/'smarttrack_config.h'
        text=header.read_text(encoding='utf-8-sig') if header.exists() else (canonical/'config.example.h').read_text()
        backup=LOCAL/'firmware-backups'/f'{index}-before-wifi-only.h.bak'
        if header.exists() and not backup.exists():backup.parent.mkdir(exist_ok=True);backup.write_text(text,encoding='utf-8')
        obsolete=('GPS_RX_PIN','GPS_TX_PIN','BLUETOOTH_NAME','BLUETOOTH_ACCESS_CODE','NETWORK_ENABLED','BACKEND_URL','BACKEND_CA_PEM','LAN_NTP_HOST','ALLOW_LAN_HTTP')
        for name in obsolete:text=re.sub(r'^\s*#define\s+'+name+r'\b[^\r\n]*','',text,flags=re.MULTILINE)
        header.write_text(render_header(text,values),encoding='utf-8')
    print('Private Wi-Fi provisioning saved; server address is discovered automatically.')


def connection_files(profile,credentials,connection=None):
    connection=connection or network();wifi=load(LOCAL/'wifi.json',{})
    phone=f"http://{connection['ip']}:{profile['backend_port']}" if connection['ready'] else 'Turn on the laptop hotspot and run START_SMARTTRACK.cmd'
    save(LOCAL/'connection.json',{'transport':'WIFI','ip':connection['ip'],'subnet':connection['subnet'],'phone_url':phone,'hotspot_ready':connection['ready'],'desktop_url':f"http://{profile.get('server_ip','127.0.0.1')}:{profile['backend_port']}"})
    signature=firmware_signature();uploaded=load(LOCAL/'firmware-upload.json',{})
    matches=bool(signature and uploaded.get('signature')==signature and uploaded.get('verified'))
    (LOCAL/'LOGIN.txt').write_text(
        'SMARTTRACK PRIVATE WI-FI SETUP - DO NOT SHARE\n\n'
        f"Laptop hotspot: {wifi.get('ssid','Not configured')} (2.4 GHz)\nHotspot password: {wifi.get('password','Not configured')}\n\n"
        f"Phone server URL: {phone}\nDesktop server URL: http://{profile.get('server_ip','127.0.0.1')}:{profile['backend_port']}\n"
        f"Username: {profile['username']}\nPassword: {credentials['app_password']}\n\n"
        'Phone and ESP32 join the laptop hotspot. Both apps share the same database.\n'
        'The watch discovers the laptop automatically; keep START_SMARTTRACK.cmd running.\n'
        +('Firmware matches the verified USB upload.\n' if matches else 'Run UPLOAD_ESP32.cmd to install the new Wi-Fi firmware.\n'),encoding='utf-8')
    print('Wi-Fi connection details and app login: SHOW_LOGIN.cmd')


def firmware_signature():
    directory=ROOT/'esp32-firmware/SmartTrack'
    digest=hashlib.sha256()
    for name in ('SmartTrack.ino','MotionEngine.h','OledFrameTransfer.h','WatchConfig.h','TouchControls.h',
                 'TemperatureSample.h','WatchState.h','WatchRenderer.h','WatchRuntime.h','WiFiConfig.h','NetworkRuntime.h','smarttrack_config.h'):
        path=directory/name
        if not path.exists():
            return None
        digest.update(name.encode());digest.update(path.read_bytes())
    return digest.hexdigest()


def record_upload(port):
    signature=firmware_signature()
    if not signature:
        raise RuntimeError('Cannot record upload verification: firmware source/configuration is missing.')
    save(LOCAL/'firmware-upload.json',{'signature':signature,'port':port,'verified':True})


def upload_firmware(port):
    if not re.fullmatch(r'COM\d+',port):
        raise RuntimeError('Expected an explicit COM port such as COM5.')
    env=os.environ.copy()
    if (TOOLS/'pio').exists():
        env['PYTHONPATH']=str(TOOLS/'pio')+os.pathsep+env.get('PYTHONPATH','')
        env['PLATFORMIO_CORE_DIR']=str(TOOLS/'platformio')
    print('Uploading configured firmware to',port,'- close other Serial monitors first.',flush=True)
    with (LOCAL/'logs/firmware-upload.log').open('ab') as log:
        result=subprocess.run([sys.executable,'-m','platformio','run','-d',str(ROOT/'esp32-firmware'),'-e','integrated-wifi','-t','upload','--upload-port',port],env=env,stdout=log,stderr=subprocess.STDOUT,creationflags=FLAGS)
    if result.returncode:
        raise RuntimeError('Upload failed. See .local/logs/firmware-upload.log; no successful upload was recorded.')
    record_upload(port)
    print('Upload verified. Keep the IMU still, flat and +Z up for calibration.')


def maven(*arguments):
    directory = TOOLS / 'apache-maven-3.9.11'
    boot = next((directory / 'boot').glob('plexus-classworlds-*.jar'), None)
    if not boot:
        raise RuntimeError('Workspace Maven is missing. Restore .tools/apache-maven-3.9.11 or build the desktop manually.')
    return [java(), f'-Dmaven.home={directory}', f'-Dmaven.multiModuleProjectDirectory={ROOT}',
            f'-Dclassworlds.conf={directory / "bin" / "m2.conf"}', '-classpath', boot,
            'org.codehaus.plexus.classworlds.launcher.Launcher', *arguments]


def desktop_files():
    module = ROOT / 'smarttrack-desktop'
    jar = module / 'target' / 'smarttrack-desktop-1.0.0.jar'
    path = module / 'target' / 'classpath.txt'
    rebuild = not jar.exists() or not path.exists()
    if path.exists() and (module/'pom.xml').stat().st_mtime > path.stat().st_mtime:
        rebuild = True
    if not rebuild:
        with zipfile.ZipFile(jar) as archive:
            rebuild = 'org/smarttrack/desktop/DesktopLauncher.class' not in archive.namelist()
        rebuild |= any(p.stat().st_mtime > jar.stat().st_mtime for p in (module/'src/main').rglob('*') if p.is_file())
    if rebuild:
        print('Preparing the JavaFX desktop (dependencies may download on first build)...', flush=True)
        with (LOCAL / 'logs' / 'desktop-build.log').open('ab') as output:
            result = subprocess.run([str(x) for x in maven('-f', module/'pom.xml', 'package', 'dependency:build-classpath',
                                     '-DskipTests', '-Dmdep.outputFile=target/classpath.txt', '-q')],
                                    cwd=ROOT, stdout=output, stderr=subprocess.STDOUT, creationflags=FLAGS)
        if result.returncode:
            raise RuntimeError('Desktop build failed; see .local/logs/desktop-build.log')
    dependencies = path.read_text(encoding='utf-8-sig').strip().split(os.pathsep)
    if not all(Path(p).exists() for p in dependencies):
        raise RuntimeError('A cached JavaFX dependency is missing. Rebuild the desktop with Maven.')
    return jar, dependencies


def start_desktop(profile):
    jar, dependencies = desktop_files()
    artifact_hash=hashlib.sha256(jar.read_bytes()+profile.get('server_ip','127.0.0.1').encode()).hexdigest()
    if owned('desktop'):
        if load(STATE,{}).get('desktop',{}).get('artifact_sha256') == artifact_hash:
            print('Desktop already running.'); return
        record=load(STATE)['desktop']
        subprocess.run(['taskkill','/PID',str(record['pid']),'/T','/F'],capture_output=True,creationflags=FLAGS,check=True)
    env = os.environ.copy(); env['SMARTTRACK_BACKEND_URL'] = f"http://{profile.get('server_ip','127.0.0.1')}:{profile['backend_port']}"
    env['SMARTTRACK_LOGIN_USER'] = profile['username']
    connection=network()
    if connection['ready']:env['SMARTTRACK_PHONE_URL']=f"http://{connection['ip']}:{profile['backend_port']}"
    spawn('desktop', [java(), '-cp', os.pathsep.join([str(jar)] + dependencies), 'org.smarttrack.desktop.DesktopLauncher'], env=env)
    state=load(STATE,{})
    state['desktop']['artifact_sha256']=artifact_hash;save(STATE,state)
    print('Desktop launched. Its login URL/username are prefilled; use the private app password.')


def start_hotspot():
    result=subprocess.run(['powershell.exe','-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',str(ROOT/'scripts/Start-Hotspot.ps1')],capture_output=True,text=True,creationflags=FLAGS)
    if result.returncode:raise RuntimeError('Could not start the saved laptop hotspot. Turn it on in Windows Settings, using the saved name/password and 2.4 GHz.')
    print(result.stdout.strip())
    for attempt in range(10):
        if network()['ready']:return
        time.sleep(.3)
    raise RuntimeError('Hotspot is starting; retry START_SMARTTRACK.cmd in a few seconds.')


def start_discovery(profile,connection):
    if not connection['ready']:
        print('Laptop hotspot is off; desktop history remains available locally.');return
    if owned('discovery'):return
    spawn('discovery',[sys.executable,ROOT/'scripts/wifi_discovery.py'])
    print('Wi-Fi server discovery ready on UDP 49001.')


def firewall(profile=None,connection=None):
    result=subprocess.run(['powershell.exe','-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',str(ROOT/'scripts/Allow-WiFi.ps1')],capture_output=True,text=True,creationflags=FLAGS)
    if result.returncode:raise RuntimeError('Windows administrator permission is needed for the hotspot firewall rules. Run ALLOW_WIFI.cmd as administrator.')
    print('Hotspot-local Wi-Fi firewall rules ready.')


def stop():
    profile, credentials = load(PROFILE, {}), load(SECRETS, {})
    for name in ('desktop', 'discovery', 'backend'):
        if owned(name):
            record = load(STATE)[name]
            result=subprocess.run(['taskkill', '/PID', str(record['pid']), '/T', '/F'], capture_output=True, creationflags=FLAGS)
            if result.returncode and owned(name):
                raise RuntimeError(f'Could not stop the managed {name} process.')
            print('Stopped managed', name)
    if owned('mysql'):
        mysql_client(profile, credentials, True)
        result = subprocess.run([str(mysql_tools()/'mysqladmin.exe'), f'--defaults-extra-file={LOCAL / "mysql-root.cnf"}', 'shutdown'],
                                capture_output=True, creationflags=FLAGS, timeout=15)
        if result.returncode:
            raise RuntimeError('Could not gracefully stop project MySQL; it was not force-killed.')
        print('Stopped project MySQL. All databases/files were retained.')


def status():
    profile, credentials = load(PROFILE), load(SECRETS)
    if not profile:
        print('Not configured. Run START_SMARTTRACK.cmd first.'); return
    for name in ('mysql', 'backend', 'discovery', 'desktop'):
        print(name + ':', 'RUNNING' if owned(name) else 'STOPPED')
    if owned('backend'):
        api(profile, credentials, '/auth/me')
        devices = api(profile, credentials, '/devices')
        print('REST login verified; registered wearables:', len(devices))
        for device in devices:
            print(device['deviceId'], 'ONLINE' if device['online'] else 'OFFLINE / connect ESP32 to the laptop hotspot',
                  'SIMULATED' if device['simulated'] else 'REAL HARDWARE')
    connection = load(LOCAL / 'connection.json', {})
    print('Transport: Wi-Fi only. Phone and desktop share the backend/database.')
    print('Username:', profile['username'], '| Password is in private .local/LOGIN.txt')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['start', 'stop', 'status', 'firewall', 'upload', 'configure'])
    parser.add_argument('--no-desktop', action='store_true')
    parser.add_argument('--port',default='COM5')
    args = parser.parse_args()
    if os.name != 'nt':
        raise RuntimeError('This local startup workflow targets Windows 11.')
    private_directory()
    with deployment_lock():
        if args.action == 'stop':
            stop(); return
        if args.action == 'status':
            status(); return
        if args.action=='configure':
            import getpass
            previous=load(LOCAL/'wifi.json',{})
            ssid=input('Laptop hotspot name ['+previous.get('ssid','JAY')+']: ').strip() or previous.get('ssid','JAY')
            password=getpass.getpass('Hotspot password (blank keeps saved value): ') or previous.get('password','')
            if not 8<=len(password)<=63:raise RuntimeError('A hotspot password must have 8 to 63 characters.')
            save(LOCAL/'wifi.json',{'ssid':ssid,'password':password,'mode':'laptop-hotspot'})
            print('Wi-Fi settings saved privately. Run START_SMARTTRACK.cmd then UPLOAD_ESP32.cmd.');return
        profile, credentials, wifi = configuration()
        if args.action in ('start','firewall'):start_hotspot()
        connection=network()
        if args.action == 'firewall':
            firewall(profile, connection); return
        if args.action == 'upload':
            configure_firmware(profile,credentials,wifi,connection)
            upload_firmware(args.port)
            connection_files(profile,credentials,connection)
            return
        print('Starting Wi-Fi SmartTrack with one shared backend and database.', flush=True)
        start_database(profile, credentials)
        start_backend(profile, credentials)
        save(PROFILE,profile)
        register_wearable(profile, credentials)
        configure_firmware(profile, credentials, wifi, connection)
        connection_files(profile, credentials, connection)
        start_discovery(profile,connection)
        api(profile, credentials, '/auth/me')
        if not args.no_desktop:
            start_desktop(profile)
        print('Ready. Install deliverables/SmartTrack-debug.apk and open SHOW_LOGIN.cmd for app credentials and firmware status.')
        print('Join the laptop hotspot on phone and ESP32. Use SHOW_LOGIN.cmd for the shared server/login.')


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        # Error text must never accidentally disclose a private password or device key.
        message = str(error)
        for path in (SECRETS, LOCAL/'wifi.json'):
            for key, value in (load(path, {}) or {}).items():
                if isinstance(value, str) and ('password' in key or 'key' in key or 'code' in key):
                    message = message.replace(value, '[private]')
        print('SmartTrack:', message, file=sys.stderr)
        sys.exit(1)
