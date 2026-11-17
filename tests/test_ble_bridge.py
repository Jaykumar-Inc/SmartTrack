import importlib.util
import json
from pathlib import Path
import struct
import unittest

spec = importlib.util.spec_from_file_location("ble_monitor", Path(__file__).resolve().parents[1] / "scripts/ble-monitor.py")
bridge = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bridge)


def packet(frame, part, total, data):
    return b"\x01\x01" + struct.pack("<HHH", frame, part, total) + data


class BleBridgeFramesTest(unittest.TestCase):
    def test_canonical_fixture_at_minimum_and_large_mtu(self):
        payload = (Path(__file__).resolve().parents[1] / "shared-contracts/example-simulator.json").read_bytes()
        for mtu in (23, 185):
            frames = bridge.Frames()
            chunk = mtu - 3 - 8
            parts = [payload[i:i+chunk] for i in range(0, len(payload), chunk)]
            self.assertLessEqual(len(parts), 256)
            decoded = None
            for index in reversed(range(len(parts))):
                decoded = frames.accept(packet(65535, index, len(parts), parts[index]))
                if index:
                    self.assertIsNone(decoded)
            self.assertEqual(json.loads(payload), decoded)

    def test_conflicting_duplicate_cannot_corrupt_record(self):
        frames = bridge.Frames()
        self.assertIsNone(frames.accept(packet(1, 0, 2, b'{"a":')))
        with self.assertRaises(ValueError):
            frames.accept(packet(1, 0, 2, b'{"b":'))
        self.assertNotIn(1, frames.frames)

    def test_rejects_invalid_header_and_unbounded_count(self):
        for data in (b"bad", packet(1, 0, 257, b"x"), packet(1, 2, 2, b"x")):
            with self.assertRaises(ValueError):
                bridge.Frames().accept(data)

    def test_keeps_at_most_two_incomplete_records(self):
        frames = bridge.Frames()
        for frame in range(20):
            frames.accept(packet(frame, 0, 2, b"x"))
        self.assertEqual(2, len(frames.frames))


if __name__ == "__main__":
    unittest.main()
