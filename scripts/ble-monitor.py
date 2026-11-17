"""Windows BLE monitor/optional backend bridge. Pairing requires the actual OLED passkey.

Credentials and passkeys are never logged. Raw readings are written only with --output.
"""
import argparse
import asyncio
import getpass
import json
import struct
import time
import urllib.error
import urllib.request
from pathlib import Path

from bleak import BleakClient, BleakScanner

SERVICE = "7fd90000-9c24-4f64-a65e-8a08cc91f001"
IDENTITY = "7fd90001-9c24-4f64-a65e-8a08cc91f001"
SNAPSHOT = "7fd90002-9c24-4f64-a65e-8a08cc91f001"
CONFIG = "7fd90006-9c24-4f64-a65e-8a08cc91f001"
TIME = "7fd90007-9c24-4f64-a65e-8a08cc91f001"


class Frames:
    def __init__(self):
        self.frames = {}

    def accept(self, packet):
        now = time.monotonic()
        self.frames = {k: v for k, v in self.frames.items() if now-v[0] < 10}
        if len(packet) < 9 or packet[:2] != b"\x01\x01":
            raise ValueError("Invalid BLE frame")
        frame, part, total = struct.unpack_from("<HHH", packet, 2)
        if not 1 <= total <= 256 or part >= total:
            raise ValueError("Invalid fragment count")
        if frame not in self.frames:
            if len(self.frames) >= 2:
                del self.frames[next(iter(self.frames))]
            self.frames[frame] = (now, [None] * total)
        parts = self.frames[frame][1]
        data = bytes(packet[8:])
        if len(parts) != total or (parts[part] is not None and parts[part] != data):
            del self.frames[frame]
            raise ValueError("Conflicting BLE fragments")
        parts[part] = data
        if sum(len(p) for p in parts if p is not None) > 16384:
            del self.frames[frame]
            raise ValueError("BLE frame too large")
        if any(p is None for p in parts):
            return None
        del self.frames[frame]
        return json.loads(b"".join(parts).decode("utf-8"))


def usb_pin(port):
    import serial
    link = serial.Serial()
    link.port, link.baudrate, link.timeout = port, 115200, 0.2
    link.dtr = link.rts = False
    try:
        link.open()
        link.reset_input_buffer()
        link.write(b"paircode\n")
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            line = link.readline().decode("ascii", "ignore").strip()
            if line.startswith("BLE_PAIR_CODE="):
                pin = line.split("=", 1)[1]
                if len(pin) == 6 and pin.isdigit():
                    return pin
        raise RuntimeError("No USB pairing code returned. Flash the updated firmware first.")
    finally:
        if link.is_open:
            link.close()


async def pair_windows(address, port, repair=False):
    from winrt.windows.devices.bluetooth import BluetoothLEDevice
    from winrt.windows.devices.enumeration import (
        DevicePairingKinds, DevicePairingProtectionLevel, DevicePairingResultStatus,
    )
    device = await BluetoothLEDevice.from_bluetooth_address_async(int(address.replace(":", ""), 16))
    if device is None:
        raise RuntimeError("Windows could not open the BLE device")
    try:
        pairing = device.device_information.pairing
        if pairing.is_paired and repair:
            result = await pairing.unpair_async()
            print("Removed this ESP32's Windows bond for a fresh pairing test:", result.status.name, flush=True)
            device.close()
            device = await BluetoothLEDevice.from_bluetooth_address_async(int(address.replace(":", ""), 16))
            pairing = device.device_information.pairing
        if pairing.is_paired:
            print("Windows already has a bond for this ESP32.", flush=True)
            return
        known_pin = await asyncio.to_thread(usb_pin, port) if port else None
        custom = pairing.custom
        loop = asyncio.get_running_loop()
        tasks = set()

        async def provide(args, deferral):
            try:
                pin = known_pin if known_pin else await asyncio.to_thread(
                    getpass.getpass, "Enter the six-digit ESP32 OLED code: "
                )
                if len(pin) != 6 or not pin.isdigit():
                    raise ValueError("A six-digit OLED code is required")
                args.accept(pin)
            finally:
                deferral.complete()

        def requested(sender, args):
            deferral = args.get_deferral()
            def schedule():
                task = asyncio.create_task(provide(args, deferral))
                tasks.add(task)
            loop.call_soon_threadsafe(schedule)

        registration = custom.add_pairing_requested(requested)
        try:
            print("Pairing with authenticated encryption. Watch the OLED for the code.", flush=True)
            result = await custom.pair_async(
                DevicePairingKinds.PROVIDE_PIN, DevicePairingProtectionLevel.ENCRYPTION_AND_AUTHENTICATION
            )
            if result.status not in (DevicePairingResultStatus.PAIRED, DevicePairingResultStatus.ALREADY_PAIRED):
                raise RuntimeError("Pairing failed: " + result.status.name + ". Forget this ESP32 in Windows Bluetooth settings and retry.")
            print("Authenticated Windows pairing succeeded.", flush=True)
        finally:
            custom.remove_pairing_requested(registration)
            if tasks:
                await asyncio.gather(*tasks)
    finally:
        device.close()


class Bridge:
    def __init__(self, base, session_file):
        self.base = base.rstrip("/")
        if not self.base.startswith(("http://", "https://")):
            raise ValueError("Use an HTTP(S) backend URL")
        self.token = None
        try:
            info = self.call("GET", "/v1/info")
            if info.get("application") != "io.smarttrack.backend":
                raise RuntimeError("Incorrect server identity")
        except RuntimeError:
            raise RuntimeError("This is not the current SmartTrack backend. Run Start-Backend.cmd -Restart and use port 8087.") from None
        saved = json.loads(Path(session_file).read_text(encoding="utf-8-sig")) if session_file else {}
        email = saved.get("email") or input("Backend email: ").strip()
        password = saved.get("password") or getpass.getpass("Backend password: ")
        self.token = None
        self.token = self.call("POST", "/v1/auth/login", {"email": email, "password": password})["token"]

    def call(self, method, path, payload=None):
        headers = {"Content-Type": "application/json"}
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        data = json.dumps(payload).encode() if payload is not None else None
        request = urllib.request.Request(self.base + path, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=10) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            try:
                detail = json.load(error).get("detail", "HTTP " + str(error.code))
            except (ValueError, AttributeError):
                detail = "HTTP " + str(error.code) + ". Check the backend address and port 8087."
            raise RuntimeError(detail) from None

    def register(self, identity):
        devices = self.call("GET", "/v1/devices")
        if not any(d["id"] == identity["deviceId"] for d in devices):
            # A new device secret is returned once by the API but is not needed by user-token sync.
            self.call("POST", "/v1/devices", {"id": identity["deviceId"], "name": "ESP32 via laptop BLE"})


async def monitor(args):
    print("Scanning for SmartTrack BLE devices...", flush=True)
    advertisements = await BleakScanner.discover(timeout=10, return_adv=True)
    matches = [d for d, a in advertisements.values() if SERVICE in a.service_uuids]
    if args.address:
        matches = [d for d in matches if d.address.lower() == args.address.lower()]
    if args.device:
        matches = [d for d in matches if d.name == "SmartTrack " + args.device]
    for device in matches:
        print(device.address, device.name or "SmartTrack", flush=True)
    if args.scan:
        return
    if len(matches) != 1:
        raise RuntimeError("Choose one device with --address ADDRESS" if matches else "No SmartTrack advertisement. Check ESP32 power, laptop Bluetooth, and disconnect other BLE clients.")
    target = matches[0]
    bridge = await asyncio.to_thread(Bridge, args.backend, args.session_file) if args.backend else None
    await pair_windows(target.address, args.usb_pin_port, args.repair)
    messages = asyncio.Queue(maxsize=32)
    frames = Frames()
    rejected = 0
    def notification(sender, data):
        nonlocal rejected
        try:
            value = frames.accept(bytes(data))
            if value is not None:
                messages.put_nowait(value)
        except (ValueError, asyncio.QueueFull):
            rejected += 1
    count = forwarded = 0
    async with BleakClient(target, timeout=30, winrt={"use_cached_services": False}) as client:
        identity = json.loads(bytes(await client.read_gatt_char(IDENTITY)))
        print("Connected to", identity["deviceId"], "firmware", identity["firmwareVersion"], flush=True)
        if bridge:
            await asyncio.to_thread(bridge.register, identity)
        await client.start_notify(SNAPSHOT, notification)
        if args.verify_controls:
            original = json.loads(bytes(await client.read_gatt_char(CONFIG)))["telemetryIntervalMs"]
            try:
                await client.write_gatt_char(CONFIG, json.dumps({"telemetryIntervalMs": 1500}).encode(), response=True)
                actual = json.loads(bytes(await client.read_gatt_char(CONFIG)))["telemetryIntervalMs"]
                if actual != 1500:
                    raise RuntimeError("Delivery interval write/read verification failed")
                await client.write_gatt_char(TIME, json.dumps({"epochSeconds": int(time.time())}).encode(), response=True)
                print("Verified encrypted interval read/write and clock write; restoring original interval.", flush=True)
            finally:
                await client.write_gatt_char(CONFIG, json.dumps({"telemetryIntervalMs": original}).encode(), response=True)
        print("Subscribed. Receiving actual hardware readings; Ctrl+C stops this monitor.", flush=True)
        deadline = time.monotonic() + args.seconds
        while time.monotonic() < deadline:
            if not client.is_connected:
                raise RuntimeError("BLE link disconnected. Run this monitor again to reconnect.")
            try:
                value = await asyncio.wait_for(messages.get(), timeout=min(5, max(.1, deadline-time.monotonic())))
            except asyncio.TimeoutError:
                continue
            if value.get("source") != "HARDWARE" or value.get("deviceId") != identity["deviceId"]:
                raise RuntimeError("Unexpected telemetry source or identity")
            count += 1
            if args.output:
                with open(args.output, "a", encoding="utf-8") as recording:
                    recording.write(json.dumps(value) + "\n")
            if bridge:
                await asyncio.to_thread(bridge.call, "POST", "/v1/synchronization", [value])
                forwarded += 1
            health, motion, gps = value["health"], value["motion"], value["gps"]
            print("HARDWARE sample", count, "sequence", value["sequence"], "health", health.get("quality"),
                  "activity", motion.get("activity"), "GPS fix", gps.get("fix"), flush=True)
        await client.stop_notify(SNAPSHOT)
    print(f"Complete: {count} hardware snapshots, {forwarded} forwarded, {rejected} rejected fragments.", flush=True)
    if not count:
        raise RuntimeError("Connected but received no complete readings. Inspect ESP32 diagnostics.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scan", action="store_true")
    parser.add_argument("--address")
    parser.add_argument("--device", help="Select the advertised SmartTrack device ID")
    parser.add_argument("--seconds", type=int, default=3600)
    parser.add_argument("--backend", help="Optional API URL; asks for your SmartTrack email/password")
    parser.add_argument("--session-file", help="Optional private local test account file; never printed")
    parser.add_argument("--usb-pin-port", help="Read the OLED passkey over a physically connected USB port, e.g. COM5")
    parser.add_argument("--repair", action="store_true", help="Forget only this ESP32's Windows bond and pair it again")
    parser.add_argument("--verify-controls", action="store_true", help="Test encrypted interval configuration and synchronize the device clock")
    parser.add_argument("--output", help="Optional user-selected JSONL recording path")
    args = parser.parse_args()
    try:
        asyncio.run(monitor(args))
    except KeyboardInterrupt:
        print("BLE monitor stopped.")
    except Exception as error:
        print("BLE monitor failed:", error, flush=True)
        raise SystemExit(1) from None


if __name__ == "__main__":
    main()
