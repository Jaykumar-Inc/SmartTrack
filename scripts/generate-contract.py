"""Generate the published JSON Schema and canonical simulator fixture. Standard library only."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
def nullable(kind, **kw): return {"type": [kind, "null"], **kw}
def obj(properties): return {"type": "object", "additionalProperties": False, "required": list(properties), "properties": properties}
def enum(*items): return {"type": "string", "enum": list(items)}
def number(lo, hi): return nullable("number", minimum=lo, maximum=hi)
schema = obj({
 "protocolVersion": {"const": 1}, "deviceId": {"type": "string", "pattern": "^[A-Za-z0-9_-]{3,64}$"},
 "sessionId": {"type": "string", "format": "uuid"}, "sequence": {"type": "integer", "minimum": 0},
 "timestamp": nullable("string", format="date-time"), "timestampQuality": enum("NTP", "GPS", "SYSTEM", "PHONE", "UNSYNCHRONIZED"),
 "uptimeMs": {"type": "integer", "minimum": 0}, "source": enum("HARDWARE", "SIMULATOR"),
 "health": obj({"heartRateBpm": number(25,240), "spo2Percent": number(50,100), "fingerDetected": {"type":"boolean"}, "quality":enum("VALID","LOW_SIGNAL","NO_CONTACT","UNAVAILABLE")}),
 "motion": obj({**{k:number(-16,16) for k in ["axG","ayG","azG"]}, **{k:number(-2000,2000) for k in ["gxDps","gyDps","gzDps"]}, "temperatureC":number(-40,100),"magnitudeG":number(0,28),"pitchDeg":number(-180,180),"rollDeg":number(-180,180),"activity":enum("STILL","MOVING","VIGOROUS","UNKNOWN"),"fallState":enum("IDLE","FREEFALL","IMPACT","MONITORING","FALL","COOLDOWN")}),
 "gps": obj({"communicating":{"type":"boolean"},"fix":{"type":"boolean"},"satellites":nullable("integer",minimum=0,maximum=99),"latitude":number(-90,90),"longitude":number(-180,180),"altitudeM":number(-1000,20000),"speedKmh":number(0,1500),"lastFixAt":nullable("string",format="date-time"),"characters":{"type":"integer","minimum":0},"checksumFailures":{"type":"integer","minimum":0}}),
 "status":obj({"firmwareVersion":{"type":"string","maxLength":40},"rssiDbm":nullable("integer",minimum=-127,maximum=0),"freeHeapBytes":nullable("integer",minimum=0),"wifiConnected":{"type":"boolean"},"bleConnected":{"type":"boolean"},"clockSource":{"type":"string","maxLength":24}}),
 "alerts":{"type":"array","maxItems":8,"items":obj({"kind":enum("IMPACT","EXPERIMENTAL_FALL","SENSOR_UNAVAILABLE"),"severity":enum("INFO","WARNING","CRITICAL")})}
})
schema.update({"$schema":"https://json-schema.org/draft/2020-12/schema","$id":"https://smarttrack.local/contracts/telemetry-v1.schema.json","title":"SmartTrack telemetry v1"})
schema["allOf"]=[
 {"if":{"properties":{"timestampQuality":{"const":"UNSYNCHRONIZED"}}},"then":{"properties":{"timestamp":{"type":"null"}}},"else":{"properties":{"timestamp":{"type":"string","format":"date-time"}}}},
 {"if":{"properties":{"health":{"properties":{"quality":{"const":"VALID"}}}}},"then":{"properties":{"health":{"properties":{"fingerDetected":{"const":True},"heartRateBpm":{"type":"number"},"spo2Percent":{"type":"number"}}}}},"else":{"properties":{"health":{"properties":{"heartRateBpm":{"type":"null"},"spo2Percent":{"type":"null"}}}}}},
 {"if":{"properties":{"gps":{"properties":{"fix":{"const":True}}}}},"then":{"properties":{"gps":{"properties":{"communicating":{"const":True},"latitude":{"type":"number"},"longitude":{"type":"number"}}}}},"else":{"properties":{"gps":{"properties":{k:{"type":"null"} for k in ["latitude","longitude","altitudeM","speedKmh"]}}}}}
]
fixture={"protocolVersion":1,"deviceId":"sim-esp32","sessionId":"f74c781e-6ed8-4d3c-b637-e3d2e734f42a","sequence":0,"timestamp":"2026-11-16T00:00:00Z","timestampQuality":"SYSTEM","uptimeMs":1000,"source":"SIMULATOR",
 "health":{"heartRateBpm":72.0,"spo2Percent":98.0,"fingerDetected":True,"quality":"VALID"},
 "motion":{"axG":0.01,"ayG":0.02,"azG":1.0,"gxDps":0.0,"gyDps":0.0,"gzDps":0.0,"temperatureC":28.0,"magnitudeG":1.00025,"pitchDeg":0.57,"rollDeg":1.15,"activity":"STILL","fallState":"IDLE"},
 "gps":{"communicating":True,"fix":False,"satellites":3,"latitude":None,"longitude":None,"altitudeM":None,"speedKmh":None,"lastFixAt":None,"characters":1200,"checksumFailures":0},
 "status":{"firmwareVersion":"simulator-1.0.0","rssiDbm":-52,"freeHeapBytes":180000,"wifiConnected":True,"bleConnected":False,"clockSource":"SYSTEM"},"alerts":[]}
for name, value in [("telemetry-v1.schema.json",schema),("example-simulator.json",fixture)]:
 (ROOT/"shared-contracts"/name).write_text(json.dumps(value,indent=2)+"\n",encoding="utf-8")
print("Generated v1 schema and simulator fixture")
