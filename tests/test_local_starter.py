import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import urllib.error

spec=importlib.util.spec_from_file_location('smarttrack_local',Path(__file__).resolve().parents[1]/'scripts/smarttrack_local.py')
local=importlib.util.module_from_spec(spec)
spec.loader.exec_module(local)


class LocalStarterTest(unittest.TestCase):
    def test_wifi_configuration_reads_private_credentials(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            (root/'wifi.json').write_text(json.dumps({'ssid':'fixture-hotspot','password':'fixture-password'}))
            with patch.object(local,'LOCAL',root),patch.object(local,'PROFILE',root/'profile.json'),patch.object(local,'SECRETS',root/'credentials.json'):
                profile,credentials,wifi=local.configuration()
                self.assertEqual(wifi['ssid'],'fixture-hotspot')
                self.assertNotIn('bluetooth_code',credentials)

    def test_wifi_header_preserves_watch_preferences(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);sketch=root/'esp32-firmware/SmartTrack';sketch.mkdir(parents=True)
            (sketch/'smarttrack_config.h').write_text('#pragma once\n#define BLUETOOTH_NAME "retired"\n#define WATCH_SCREEN_TIMEOUT_MS 30000UL\n')
            runtime=root/'.local';runtime.mkdir()
            with patch.object(local,'ROOT',root),patch.object(local,'LOCAL',runtime):
                local.configure_firmware({'device_id':'esp32-fixture','original_sketch':str(root/'absent')},{'device_key':'fixture-device-key'},{'ssid':'fixture-net','password':'fixture-password'})
            text=(sketch/'smarttrack_config.h').read_text()
            self.assertNotIn('BLUETOOTH_NAME',text)
            self.assertIn('#define WIFI_SSID "fixture-net"',text)
            self.assertIn('#define WATCH_SCREEN_TIMEOUT_MS 30000UL',text)

    def test_network_headers_participate_in_upload_fingerprint(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);sketch=root/'esp32-firmware/SmartTrack';sketch.mkdir(parents=True)
            names=('SmartTrack.ino','MotionEngine.h','OledFrameTransfer.h','WatchConfig.h','TouchControls.h','TemperatureSample.h','WatchState.h','WatchRenderer.h','WatchRuntime.h','WiFiConfig.h','NetworkRuntime.h','smarttrack_config.h')
            for name in names:(sketch/name).write_text('fixture '+name)
            with patch.object(local,'ROOT',root):
                before=local.firmware_signature();self.assertIsNotNone(before)
                (sketch/'NetworkRuntime.h').write_text('changed network worker')
                self.assertNotEqual(before,local.firmware_signature())
                (sketch/'WatchState.h').unlink();self.assertIsNone(local.firmware_signature())

    def test_missing_firmware_cannot_be_recorded_as_verified_upload(self):
        with patch.object(local,'firmware_signature',return_value=None),patch.object(local,'save') as save:
            with self.assertRaisesRegex(RuntimeError,'firmware source/configuration is missing'):
                local.record_upload('COM5')
            save.assert_not_called()

    def test_missing_signatures_do_not_claim_verified_upload(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            profile={'backend_port':8080,'username':'unit-user','device_id':'unit-device'}
            with patch.object(local,'LOCAL',root),patch.object(local,'firmware_signature',return_value=None):
                local.connection_files(profile,{'app_password':'unit-password'},{'ip':'192.168.1.2','ready':True,'subnet':'192.168.1.0/24'})
            content=(root/'LOGIN.txt').read_text()
            self.assertIn('Run UPLOAD_ESP32.cmd',content)
            self.assertNotIn('matches the last verified',content)

    def test_private_header_preserves_settings_and_escapes_credentials(self):
        text='#pragma once\n#define WIFI_SSID "old"\n#define WIFI_PASSWORD "old"\n#define MOTION_SENSITIVITY 1.2f\n'
        password='test"with\\characters'
        result=local.render_header(text,{'NETWORK_ENABLED':True,'WIFI_SSID':'Test hotspot','WIFI_PASSWORD':password})
        self.assertIn('#define MOTION_SENSITIVITY 1.2f',result)
        self.assertIn('#define NETWORK_ENABLED true',result)
        self.assertIn('#define WIFI_PASSWORD '+json.dumps(password),result)
        self.assertEqual(result.count('#define WIFI_SSID'),1)

    def test_reused_pid_is_not_treated_as_our_process(self):
        with tempfile.TemporaryDirectory() as directory:
            state=Path(directory)/'state.json'
            state.write_text(json.dumps({'backend':{'pid':123,'created':100}}))
            with patch.object(local,'STATE',state),patch.object(local,'fingerprint',return_value=200):
                self.assertFalse(local.owned('backend'))
            with patch.object(local,'STATE',state),patch.object(local,'fingerprint',return_value=100):
                self.assertTrue(local.owned('backend'))

    def test_initial_401_during_account_creation_is_retried(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);(root/'deliverables').mkdir();(root/'deliverables/SmartTrack-backend.jar').touch()
            profile={'backend_port':8080,'mysql_port':3308,'username':'unit-user'}
            credentials={'mysql_password':'unit-database-password','app_password':'unit-application-password'}
            not_ready=urllib.error.HTTPError('http://127.0.0.1',401,'not provisioned yet',{},None)
            with patch.object(local,'ROOT',root),patch.object(local,'LOCAL',root),patch.object(local,'STATE',root/'processes.json'),patch.object(local,'listening',return_value=False),patch.object(local,'java',return_value='java.exe'),patch.object(local,'spawn'),patch.object(local,'owned',return_value=True),patch.object(local.time,'sleep'),patch.object(local,'network',return_value={'ready':False}),patch.object(local,'api',side_effect=[not_ready,{'username':'unit-user'}]) as api:
                local.start_backend(profile,credentials)
                self.assertEqual(api.call_count,2)


if __name__=='__main__':
    unittest.main()
