"""Read actual ESP32 diagnostics without printing private optical values or secrets."""
import argparse
import json
from pathlib import Path
import sys
import time
import urllib.error
from smarttrack_local import ROOT, TOOLS, LOCAL, PROFILE, SECRETS, load, save, api

if (TOOLS/'pio').exists():
    sys.path.insert(0,str(TOOLS/'pio'))
import serial

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--port',default='COM5')
parser.add_argument('--seconds',type=int,default=25)
args=parser.parse_args()
profile,credentials=load(PROFILE),load(SECRETS)
if not profile or not credentials:
    raise SystemExit('Start SmartTrack before checking the board.')
summary={'port':args.port,'jsonPackets':0,'wifiConnected':None,'serverAccepted':None}
connection=serial.Serial(port=None,baudrate=115200,timeout=0.25)
connection.dtr=False;connection.rts=False;connection.port=args.port
connection.open()
buffer=b''
deadline=time.monotonic()+max(1,min(args.seconds,60))
with connection,(LOCAL/'logs/hardware-serial.log').open('w',encoding='utf-8') as log:
    while time.monotonic()<deadline:
        buffer+=connection.read(connection.in_waiting or 1)
        while b'\n' in buffer:
            raw,buffer=buffer.split(b'\n',1)
            text=raw.decode('utf-8',errors='replace').strip()
            log.write(text+'\n')
            if ' | WiFi ' in text:
                summary['wifiConnected']='| WiFi OK ' in text
                summary['serverAccepted']='server OK' in text
            if not text.startswith('JSON: '):
                continue
            try:
                reading=json.loads(text[6:])
            except json.JSONDecodeError:
                continue
            summary['jsonPackets']+=1
            summary['deviceIdMatches']=reading.get('deviceId')==profile['device_id']
            summary['sensors']=reading.get('sensorStatus',{})
            summary['healthQuality']=reading.get('health',{}).get('quality')
            summary['motionQuality']=reading.get('motion',{}).get('quality')
            summary['calibration']=reading.get('motion',{}).get('calibration')
            summary['temperatureQuality']=reading.get('temperature',{}).get('quality')
            summary['chipTemperatureC']=reading.get('temperature',{}).get('celsius')
try:
    reading=api(profile,credentials,'/devices/'+profile['device_id']+'/latest')
    summary['desktopArchiveLatestAvailable']=True
    summary['desktopArchiveReadingIsReal']=not reading.get('simulated',True)
    summary['desktopArchiveReadingStale']=reading.get('stale',True)
except (OSError,urllib.error.HTTPError):
    summary['desktopArchiveLatestAvailable']=False
save(LOCAL/'hardware-summary.json',summary)
print(json.dumps(summary,indent=2))
print('Raw Serial output is private in .local/logs/hardware-serial.log')
