"""Explicit SIMULATOR transport; uses the same v1 fixture as the contract tests.
Credentials are read from environment, never printed. Ctrl+C stops streaming.
"""
import argparse, copy, datetime, json, math, os, secrets, time, urllib.error, urllib.request, uuid
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def request(base, path, token=None, data=None):
    headers={"Content-Type":"application/json"}
    if token: headers["Authorization"]="Bearer "+token
    body=None if data is None else json.dumps(data).encode()
    req=urllib.request.Request(base+path,data=body,headers=headers)
    try:
        with urllib.request.urlopen(req,timeout=10) as r: return json.load(r)
    except urllib.error.HTTPError as e:
        raise RuntimeError(f"API request failed ({e.code}): {json.load(e).get('detail','request rejected')}") from None
def run():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base',default='http://127.0.0.1:8080');parser.add_argument('--count',type=int,default=0);parser.add_argument('--interval',type=float,default=1);parser.add_argument('--device',default='sim-esp32');parser.add_argument('--bootstrap',action='store_true');parser.add_argument('--write-session',action='store_true')
    args=parser.parse_args()
    if args.interval<0.1 or args.count<0: parser.error('Use interval >= 0.1 and count >= 0')
    token=os.environ.get('SMARTTRACK_DEVICE_TOKEN');user_token=os.environ.get('SMARTTRACK_USER_TOKEN')
    if args.bootstrap:
        email='sim-'+uuid.uuid4().hex+'@example.test';password=secrets.token_urlsafe(24)
        request(args.base,'/v1/auth/register',data={'email':email,'password':password})
        user_token=request(args.base,'/v1/auth/login',data={'email':email,'password':password})['token']
        token=request(args.base,'/v1/devices',user_token,{'id':args.device,'name':'ESP32 · Simulator'})['deviceToken']
        if args.write_session:
            work=ROOT/'.work';work.mkdir(exist_ok=True)
            (work/'demo-session.json').write_text(json.dumps({'userToken':user_token,'deviceToken':token,'deviceId':args.device,'email':email,'password':password}),encoding='utf-8')
            if os.name=='nt':
                import subprocess
                subprocess.run(['icacls',str(work/'demo-session.json'),'/inheritance:r','/grant:r',os.environ['USERNAME']+':(F)'],check=True,stdout=subprocess.DEVNULL)
    if not token: parser.error('Set SMARTTRACK_DEVICE_TOKEN or use --bootstrap with simulator ingestion enabled')
    fixture=json.loads((ROOT/'shared-contracts/example-simulator.json').read_text(encoding='utf-8'));session=str(uuid.uuid4());started=time.monotonic();seq=0
    print('SIMULATED TELEMETRY · not hardware measurements')
    while args.count==0 or seq<args.count:
        t=copy.deepcopy(fixture);t.update(deviceId=args.device,sessionId=session,sequence=seq,timestamp=datetime.datetime.now(datetime.timezone.utc).isoformat().replace('+00:00','Z'),uptimeMs=int((time.monotonic()-started)*1000))
        t['health']['heartRateBpm']=round(72+5*math.sin(seq/8),1);t['health']['spo2Percent']=round(97.5+0.6*math.sin(seq/19),1)
        t['motion']['axG']=round(0.03*math.sin(seq/4),4);t['motion']['magnitudeG']=round(math.sqrt(1+t['motion']['axG']**2+0.02**2),5)
        if seq%40 in (30,31,32): t['health'].update(heartRateBpm=None,spo2Percent=None,fingerDetected=False,quality='NO_CONTACT')
        if seq==15: t['alerts']=[{'kind':'IMPACT','severity':'WARNING'}]
        # No fabricated GPS fix: a simulator fix is opt-in in future scenarios.
        for attempt in range(4):
            try: request(args.base,'/v1/telemetry',token,t);break
            except (RuntimeError,OSError):
                if attempt==3: raise
                time.sleep(min(8,2**attempt))
        seq+=1
        if seq%10==0 or args.count==1: print(f'Accepted {seq} simulated readings')
        if args.count==0 or seq<args.count: time.sleep(args.interval)
if __name__=='__main__':
    try: run()
    except KeyboardInterrupt: print('\nSimulator stopped')
