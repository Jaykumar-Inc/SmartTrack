"""Local hotspot discovery. Responses prove possession of the watch's device key."""
import hashlib, hmac, ipaddress, json, re, socket, time

PORT=49001

def response_for(payload,device_id,key,server_port):
    try:
        request=json.loads(payload)
        if request.get('type')!='smarttrack-discover' or request.get('deviceId')!=device_id:return None
        nonce=request.get('nonce','')
        if not isinstance(nonce,str) or not re.fullmatch('[0-9a-f]{16}',nonce):return None
        body=f'DISCOVER|{device_id}|{nonce}|{server_port}'.encode()
        signature=hmac.new(key.encode(),body,hashlib.sha256).hexdigest()
        return json.dumps({'nonce':nonce,'port':server_port,'signature':signature}).encode()
    except (ValueError,TypeError,AttributeError):return None

def main():
    import smarttrack_local as local
    profile,credentials,_=local.configuration();connection=local.network()
    if not connection['ready']:raise RuntimeError('Turn on the laptop hotspot first')
    subnet=ipaddress.ip_network(connection['subnet']);recent={}
    with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as server:
        server.bind(('0.0.0.0',PORT))
        print('Wi-Fi discovery listening on UDP 49001',flush=True)
        while True:
            payload,address=server.recvfrom(512);source=ipaddress.ip_address(address[0])
            if not(source.is_loopback or source in subnet):continue
            now=time.monotonic()
            if now-recent.get(address[0],-10)<.25:continue
            if len(recent)>64:recent.clear()
            recent[address[0]]=now
            answer=response_for(payload,profile['device_id'],credentials['device_key'],profile['backend_port'])
            if answer:server.sendto(answer,address)

if __name__=='__main__':main()
