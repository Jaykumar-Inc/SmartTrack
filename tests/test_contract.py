"""Run with .tools/pio-env/Scripts/python -m unittest discover -s tests -v.
Install jsonschema==4.25.1 into that local venv for schema-level checks.
"""
import copy, json, unittest
from pathlib import Path
from jsonschema import Draft202012Validator, FormatChecker
ROOT=Path(__file__).resolve().parents[1]
class ContractTest(unittest.TestCase):
    def setUp(self):
        self.schema=json.loads((ROOT/'shared-contracts/telemetry-v1.schema.json').read_text(encoding='utf-8'))
        self.example=json.loads((ROOT/'shared-contracts/example-simulator.json').read_text(encoding='utf-8'))
        self.validator=Draft202012Validator(self.schema,format_checker=FormatChecker())
    def test_canonical_fixture(self):
        Draft202012Validator.check_schema(self.schema);self.validator.validate(self.example)
    def test_missing_and_unknown_fields_fail(self):
        for key in self.example:
            value=copy.deepcopy(self.example);del value[key]
            self.assertFalse(self.validator.is_valid(value),key)
        value=copy.deepcopy(self.example);value['heartRate']=72
        self.assertFalse(self.validator.is_valid(value))
    def test_invalid_health_and_stale_gps_cannot_supply_values(self):
        value=copy.deepcopy(self.example);value['health']['quality']='NO_CONTACT'
        self.assertFalse(self.validator.is_valid(value))
        value=copy.deepcopy(self.example);value['gps']['latitude']=0
        self.assertFalse(self.validator.is_valid(value))
    def test_unsynchronized_requires_null(self):
        value=copy.deepcopy(self.example);value['timestampQuality']='UNSYNCHRONIZED'
        self.assertFalse(self.validator.is_valid(value));value['timestamp']=None;self.validator.validate(value)
    def test_firmware_serializes_every_canonical_name(self):
        source=(ROOT/'firmware/esp32/src/Telemetry.cpp').read_text(encoding='utf-8')
        names=set(self.schema['properties'])
        for child in self.schema['properties'].values():
            names.update(child.get('properties',{}));names.update(child.get('items',{}).get('properties',{}))
        for name in names:self.assertIn('"'+name+'"',source)
    def test_openapi_tracks_implemented_endpoints(self):
        api=json.loads((ROOT/'shared-contracts/openapi.yaml').read_text(encoding='utf-8'))
        self.assertEqual('3.1.0',api['openapi']);self.assertEqual(16,len(api['paths']))
        identity=api['paths']['/v1/info']['get']
        self.assertEqual([],identity['security'])
        self.assertEqual('io.smarttrack.backend',identity['responses']['200']['content']['application/json']['schema']['properties']['application']['const'])
        self.assertEqual('./telemetry-v1.schema.json',api['components']['schemas']['Telemetry']['$ref'])
if __name__=='__main__':unittest.main()
