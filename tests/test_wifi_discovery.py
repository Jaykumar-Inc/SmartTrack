import importlib.util,json,hmac,hashlib,unittest
from pathlib import Path
spec=importlib.util.spec_from_file_location('wifi_discovery',Path(__file__).resolve().parents[1]/'scripts/wifi_discovery.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
class DiscoveryTest(unittest.TestCase):
 def request(self,**extra):
  body={'type':'smarttrack-discover','deviceId':'wifi-fixture','nonce':'aabbccddeeff0011'};body.update(extra);return json.dumps(body).encode()
 def test_authenticated_nonce_and_port(self):
  result=json.loads(module.response_for(self.request(),'wifi-fixture','fixture-key',8080))
  expected=hmac.new(b'fixture-key',b'DISCOVER|wifi-fixture|aabbccddeeff0011|8080',hashlib.sha256).hexdigest()
  self.assertEqual(result['signature'],expected);self.assertEqual(result['port'],8080)
  self.assertNotEqual(result['signature'],json.loads(module.response_for(self.request(nonce='aabbccddeeff0022'),'wifi-fixture','fixture-key',8080))['signature'])
 def test_wrong_identity_and_malformed_packets_get_no_reply(self):
  for payload in [self.request(deviceId='other-watch'),self.request(nonce='bad'),b'garbage',b'[]',b'null']:
   self.assertIsNone(module.response_for(payload,'wifi-fixture','fixture-key',8080))
