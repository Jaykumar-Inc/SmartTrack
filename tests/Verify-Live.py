"""Optional real-server REST/SSE smoke check; credentials come only from environment."""
import base64
import json
import os
import urllib.request

base=os.environ.get('SMOKE_BACKEND','http://localhost:8080').rstrip('/')+'/api'
token=base64.b64encode((os.environ['SMARTTRACK_USER']+':'+os.environ['SMARTTRACK_PASSWORD']).encode()).decode()
def request(path):
 return urllib.request.urlopen(urllib.request.Request(base+path,headers={'Authorization':'Basic '+token}),timeout=8)
with request('/devices') as response:
 devices=json.load(response)
assert devices,'Register a device or enable demo mode first'
device=next(d for d in devices if d['simulated'])
with request('/devices/'+device['deviceId']+'/latest') as response:
 latest=json.load(response)
assert latest['simulated'] and not latest['stale']
assert latest['health']['bpm'] is not None
print('REST: persisted simulated reading is fresh')
found=False
with request('/events') as stream:
 for raw in stream:
  line=raw.decode().strip()
  if line in ('event:update','event: update'):
   print('SSE: authenticated post-commit update received')
   found=True
   break
assert found,'SSE stream ended before an update'
