"""Read actual ESP32 telemetry over USB while keeping BLE available for the phone."""
import argparse
from collections import deque
import importlib.util
import json
from pathlib import Path
import time

import jsonschema
import serial
from serial.tools import list_ports

ROOT = Path(__file__).resolve().parents[1]
SCHEMA = json.loads((ROOT / "shared-contracts/telemetry-v1.schema.json").read_text(encoding="utf-8"))
VALIDATOR = jsonschema.Draft202012Validator(SCHEMA)


def select_port(requested):
    if requested and requested.lower() != "auto":
        return requested
    candidates = [p.device for p in list_ports.comports()
                  if (p.vid, p.pid) in {(0x10C4, 0xEA60), (0x1A86, 0x7523), (0x0403, 0x6001)}]
    if len(candidates) != 1:
        raise RuntimeError("Connect one ESP32 with a USB data cable, or specify --port COM5. Close other serial monitors.")
    return candidates[0]


def decode_line(line):
    if not line.startswith(b"TELEMETRY "):
        return None
    if len(line) > 16384:
        raise ValueError("Oversized USB reading")
    value = json.loads(line[len(b"TELEMETRY "):])
    VALIDATOR.validate(value)
    if value["source"] != "HARDWARE":
        raise ValueError("USB monitor requires actual hardware telemetry")
    return value


def display(value):
    def number(v, digits=1):
        return "--" if v is None else f"{v:.{digits}f}"
    h, m, g = value["health"], value["motion"], value["gps"]
    gps = "FIX" if g["fix"] else "SEARCHING" if g["communicating"] else "NO RECEIVER DATA"
    return (f"HARDWARE #{value['sequence']} | BPM {number(h['heartRateBpm'])} | "
            f"SpO2 {number(h['spo2Percent'])}% ({h['quality']}) | "
            f"Accel {number(m['magnitudeG'], 2)}g | {m['activity']} | GPS {gps}")


def make_bridge(args):
    if not args.backend:
        return None
    spec = importlib.util.spec_from_file_location("ble_monitor", ROOT / "scripts/ble-monitor.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.Bridge(args.backend, args.session_file)


def monitor(args):
    port = select_port(args.port)
    bridge = make_bridge(args)
    link = serial.Serial()
    link.port, link.baudrate, link.timeout = port, 115200, .3
    link.dtr = link.rts = False
    count = rejected = dropped = forwarded = 0
    backlog = deque()
    registered = set()
    last_attempt = 0
    output = None
    try:
        link.open()
        time.sleep(.5)
        link.reset_input_buffer()
        link.write(f"\nclock {int(time.time())}\nusbstream on\n".encode("ascii"))
        link.flush()
        print(f"USB connected on {port}. Phone Bluetooth remains available. Ctrl+C stops.", flush=True)
        started = renewal = last_reading = time.monotonic()
        if args.output:
            output = open(args.output, "a", encoding="utf-8")
        while time.monotonic() - started < args.seconds and (not args.count or count < args.count):
            now = time.monotonic()
            if now - renewal > 3:
                link.write(b"usbstream on\n")
                renewal = now
            line = link.readline(16385)
            try:
                value = decode_line(line)
            except (ValueError, jsonschema.ValidationError):
                rejected += 1
                continue
            if value is None:
                if now - last_reading > 12:
                    raise RuntimeError("No complete USB reading. Flash firmware 1.1.0 and check the COM port.")
                continue
            last_reading = now
            count += 1
            print(display(value), flush=True)
            if output:
                output.write(json.dumps(value) + "\n")
                output.flush()
            if bridge:
                if len(backlog) >= 100:
                    backlog.popleft()
                    dropped += 1
                backlog.append(value)
                if now - last_attempt >= 1:
                    try:
                        if value["deviceId"] not in registered:
                            bridge.register({"deviceId": value["deviceId"]})
                            registered.add(value["deviceId"])
                        batch = list(backlog)[:20]
                        bridge.call("POST", "/v1/synchronization", batch)
                        forwarded += len(batch)
                        for _ in batch:
                            backlog.popleft()
                        last_attempt = now
                    except Exception as error:
                        print("Backend sharing delayed:", error, "| USB readings continue.", flush=True)
                        last_attempt = now + 9
        print(f"Complete: {count} hardware readings, {forwarded} shared, {len(backlog)} pending, {rejected} rejected, {dropped} dropped.", flush=True)
        if not count:
            raise RuntimeError("No hardware readings received")
    finally:
        if output:
            output.close()
        if link.is_open:
            try:
                link.write(b"usbstream off\n")
                link.flush()
            except serial.SerialException:
                pass
            link.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", default="auto")
    parser.add_argument("--seconds", type=int, default=3600)
    parser.add_argument("--count", type=int, default=0)
    parser.add_argument("--backend", help="Optional backend URL; prompts for your SmartTrack account")
    parser.add_argument("--session-file", help="Optional private test account file")
    parser.add_argument("--output", help="Optional user-selected JSONL recording path")
    args = parser.parse_args()
    try:
        monitor(args)
    except KeyboardInterrupt:
        print("USB monitor stopped.")
    except Exception as error:
        print("USB monitor failed:", error, flush=True)
        raise SystemExit(1) from None


if __name__ == "__main__":
    main()
