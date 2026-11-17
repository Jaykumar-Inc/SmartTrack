import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("usb_monitor", ROOT / "scripts/usb-monitor.py")
usb = importlib.util.module_from_spec(spec)
spec.loader.exec_module(usb)


class UsbMonitorTest(unittest.TestCase):
    def fixture(self):
        # Synthetic protocol fixture for parsing tests; never sent to a device or backend.
        return json.loads((ROOT / "shared-contracts/example-simulator.json").read_text())

    def test_ignores_diagnostics_and_passkeys(self):
        self.assertIsNone(usb.decode_line(b"heap=70000\n"))
        self.assertIsNone(usb.decode_line(b"BLE_PAIR_CODE=000000\n"))

    def test_rejects_simulator_as_hardware_input(self):
        with self.assertRaises(ValueError):
            usb.decode_line(b"TELEMETRY " + json.dumps(self.fixture()).encode())

    def test_decodes_and_validates_complete_hardware_protocol_frame(self):
        fixture = self.fixture()
        fixture["source"] = "HARDWARE"
        self.assertEqual(fixture, usb.decode_line(b"TELEMETRY " + json.dumps(fixture).encode()))

    def test_bounds_input_and_preserves_missing_values(self):
        with self.assertRaises(ValueError):
            usb.decode_line(b"TELEMETRY " + b"x" * 16385)
        fixture = self.fixture()
        fixture["health"]["heartRateBpm"] = None
        fixture["health"]["spo2Percent"] = None
        fixture["health"]["quality"] = "NO_CONTACT"
        self.assertIn("BPM --", usb.display(fixture))
        self.assertIn("NO_CONTACT", usb.display(fixture))


if __name__ == "__main__":
    unittest.main()
