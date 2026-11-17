"""Publish the implemented REST/WS surface as OpenAPI 3.1 (JSON is valid YAML)."""
import json
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def schema_ref(name): return {'$ref':'#/components/schemas/'+name}
def object_schema(properties,required=None): return {'type':'object','properties':properties,'required':required or list(properties),'additionalProperties':False}
def array(items):return {'type':'array','items':items}
string={'type':'string'}
schemas={
 'Credentials':object_schema({'email':{'type':'string','format':'email','maxLength':254},'password':{'type':'string','minLength':12,'maxLength':72,'writeOnly':True}}),
 'Registration':object_schema({'id':{'type':'string','pattern':'^[A-Za-z0-9_-]{3,64}$'},'name':{'type':'string','minLength':1,'maxLength':80}}),
 'Configuration':object_schema({'revision':{'type':'integer','minimum':0},'telemetryIntervalMs':{'type':'integer','minimum':1000,'maximum':60000},'timezone':string}),
 'StoredConfiguration':object_schema({'revision':{'type':'integer'},'telemetry_interval_ms':{'type':'integer'},'timezone':string}),
 'Telemetry':{'$ref':'./telemetry-v1.schema.json'},
 'Accepted':object_schema({'id':string,'duplicate':{'type':'boolean'}}),
 'Reading':object_schema({'id':string,'receivedAt':{'type':'string','format':'date-time'},'freshness':{'type':'string','enum':['SIMULATED','OFFLINE','TIME_UNVERIFIED','DELAYED','LIVE']},'telemetry':schema_ref('Telemetry')}),
 'Device':object_schema({'id':string,'name':string,'created_at':{'type':'string','format':'date-time'},'last_received_at':{'type':['string','null'],'format':'date-time'}}),
 'Alert':object_schema({'id':string,'kind':string,'severity':string,'source':string,'created_at':{'type':'string','format':'date-time'},'acknowledged_at':{'type':['string','null'],'format':'date-time'}}),
 'Problem':{'type':'object','properties':{'title':string,'status':{'type':'integer'},'detail':string,'instance':string}},
 'Login':object_schema({'token':string,'expiresAt':{'type':'string','format':'date-time'}}),
 'RegisteredDevice':object_schema({'id':string,'name':string,'deviceToken':string}),
}
paths={}
def operation(method,path,summary,response=None,body=None,public=False,created=False,parameters=None,description=''):
 status='201' if created else '200';op={'summary':summary,'description':description,'responses':{status:{'description':'Success'},'400':{'description':'Invalid v1 contract'},'401':{'description':'Missing or expired credentials'},'403':{'description':'Credential scope does not permit this action'},'404':{'description':'Resource unavailable to this owner'},'409':{'description':'Conflicting idempotency key or revision'}}}
 if response:op['responses'][status]['content']={'application/json':{'schema':response}}
 if body:op['requestBody']={'required':True,'content':{'application/json':{'schema':body}}}
 if public:op['security']=[]
 params=[]
 for name in ['device','alert']:
  if '{'+name+'}' in path:params.append({'name':name,'in':'path','required':True,'schema':string})
 if parameters:params+=parameters
 if params:op['parameters']=params
 paths.setdefault(path,{})[method.lower()]=op
operation('POST','/v1/auth/register','Create a user',body=schema_ref('Credentials'),public=True,created=True)
operation('POST','/v1/auth/login','Obtain a 12-hour opaque user token',schema_ref('Login'),schema_ref('Credentials'),public=True)
operation('POST','/v1/auth/logout','Revoke the current user token')
operation('GET','/v1/devices','List owned devices (user only)',array(schema_ref('Device')))
operation('POST','/v1/devices','Register a device and show its secret once',schema_ref('RegisteredDevice'),schema_ref('Registration'),created=True)
operation('GET','/v1/devices/{device}/configuration','Read owned device configuration',schema_ref('StoredConfiguration'))
operation('PUT','/v1/devices/{device}/configuration','Save configuration using optimistic revision',schema_ref('StoredConfiguration'),schema_ref('Configuration'))
operation('POST','/v1/devices/{device}/rotate-token','Revoke and replace a device token',object_schema({'deviceToken':string}))
operation('POST','/v1/telemetry','Ingest a canonical v1 snapshot',schema_ref('Accepted'),schema_ref('Telemetry'),description='Device tokens are scoped to one device. User tokens may sync owned devices. Tuple (deviceId, sessionId, sequence) is idempotent. Different content at the same tuple returns 409. Simulator sources require explicit backend opt-in.')
operation('POST','/v1/synchronization','Replay up to 100 cached snapshots',array(schema_ref('Accepted')),{'type':'array','minItems':1,'maxItems':100,'items':schema_ref('Telemetry')},description='Each snapshot commits independently. If a later snapshot fails, replay the batch; earlier accepted tuples deduplicate safely. A highest-sequence watermark is diagnostic, not a contiguous acknowledgement of missing sequences.')
operation('GET','/v1/devices/{device}/latest','Read newest measurement by effective measurement time',schema_ref('Reading'))
operation('GET','/v1/devices/{device}/history','Query received-time history, newest first',array(schema_ref('Reading')),parameters=[{'name':n,'in':'query','required':True,'schema':{'type':'string','format':'date-time'}} for n in ['from','to']]+[{'name':'limit','in':'query','schema':{'type':'integer','minimum':1,'maximum':2000,'default':500}}],description='Half-open UTC range [from,to), maximum 31 days. Clients must disclose truncation at the requested limit.')
operation('GET','/v1/devices/{device}/synchronization','Inspect session high-water marks',array({'type':'object'}))
operation('GET','/v1/devices/{device}/alerts','List up to 200 recent alerts (user only)',array(schema_ref('Alert')))
operation('POST','/v1/alerts/{alert}/acknowledgement','Acknowledge an owned alert idempotently')
operation('GET','/v1/stream','Upgrade to authenticated WebSocket',parameters=[{'name':'deviceId','in':'query','required':True,'schema':string}],description='HTTP Authorization: Bearer usr_… on upgrade; never put credentials in the URL. User only. Sends Reading JSON after database commit. REST latest/history recover missed frames. Server does not promise exactly-once delivery.')
operation('GET','/actuator/health','Check process/database health',public=True)
operation('GET','/v1/info','Identify the SmartTrack backend before sign-in',object_schema({'application':{'type':'string','const':'io.smarttrack.backend'},'version':string,'telemetryProtocolVersion':{'type':'integer','const':1}}),public=True)
document={'openapi':'3.1.0','info':{'title':'SmartTrack API','version':'1.1.0','description':'Experimental connected-device monitoring. UTC instants, nullable measurements, explicit provenance.'},'servers':[{'url':'http://127.0.0.1:8087','description':'Local simulator development; production requires HTTPS'}],'security':[{'bearerAuth':[]}],'paths':paths,'components':{'securitySchemes':{'bearerAuth':{'type':'http','scheme':'bearer','description':'Opaque usr_ user token or dev_ device token. Secrets are never database passwords.'}},'schemas':schemas}}
(ROOT/'shared-contracts/openapi.yaml').write_text(json.dumps(document,indent=2)+'\n',encoding='utf-8')
print('Generated implemented OpenAPI surface')
