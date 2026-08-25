import asyncio
import sys
from datetime import datetime

from bleak import BleakClient, BleakScanner

SERVICE_UUID = "8ce5cc01-0a4d-11e9-ab14-d663bd873d93"
CC02_UUID = "8ce5cc02-0a4d-11e9-ab14-d663bd873d93"

PROFILE_READ_CMD = bytes([0x40, 0x40])

SCAN_SECONDS = 10.0
RESPONSE_TIMEOUT = 6.0


def hex_bytes(data: bytes) -> str:
    return " ".join(f"{b:02X}" for b in data)


async def scan_devices():
    found = {}

    def detection_callback(device, advertisement_data):
        key = device.address
        service_uuids = [u.lower() for u in (advertisement_data.service_uuids or [])]
        name = advertisement_data.local_name or device.name or "(unnamed)"
        found[key] = {
            "device": device,
            "name": name,
            "rssi": advertisement_data.rssi,
            "services": service_uuids,
        }

    scanner = BleakScanner(detection_callback=detection_callback)
    print(f"Scanning for BLE devices for {SCAN_SECONDS:.0f} seconds...")
    await scanner.start()
    await asyncio.sleep(SCAN_SECONDS)
    await scanner.stop()

    items = list(found.values())
    items.sort(
        key=lambda x: (
            SERVICE_UUID not in x["services"],
            -(x["rssi"] if x["rssi"] is not None else -999),
            x["name"].lower(),
        )
    )
    return items


async def choose_device():
    devices = await scan_devices()

    if not devices:
        print("No BLE devices found.")
        return None

    print()
    print("Discovered devices:")
    for idx, item in enumerate(devices, start=1):
        marker = "  <== advertises C406 service" if SERVICE_UUID in item["services"] else ""
        rssi = f"{item['rssi']} dBm" if item["rssi"] is not None else "RSSI ?"
        print(f"{idx:2d}. {item['name']}  [{item['device'].address}]  {rssi}{marker}")

    print()
    while True:
        raw = input("Select device number (or q to quit): ").strip()
        if raw.lower() == "q":
            return None
        try:
            index = int(raw)
            if 1 <= index <= len(devices):
                return devices[index - 1]["device"]
        except ValueError:
            pass
        print("Invalid selection.")


async def main():
    device = await choose_device()
    if device is None:
        return 1

    print()
    print(f"Connecting to {device.name or '(unnamed)'} [{device.address}]...")

    response_event = asyncio.Event()
    received = []

    def notification_handler(_sender, data: bytearray):
        payload = bytes(data)
        received.append(payload)
        stamp = datetime.now().strftime("%H:%M:%S.%f")[:-3]
        print(f"RX {stamp}: {hex_bytes(payload)}")

        if len(payload) >= 2 and payload[0] == 0x40 and payload[1] == 0x40:
            response_event.set()

    try:
        async with BleakClient(device) as client:
            if not client.is_connected:
                print("Connection failed.")
                return 2

            print("Connected.")

            services = client.services
            service = services.get_service(SERVICE_UUID)
            if service is None:
                print(f"ERROR: service {SERVICE_UUID} was not found on this device.")
                return 3

            characteristic = services.get_characteristic(CC02_UUID)
            if characteristic is None:
                print(f"ERROR: characteristic {CC02_UUID} was not found.")
                return 4

            print(f"Found CC02: {CC02_UUID}")
            print("Enabling notifications...")
            await client.start_notify(CC02_UUID, notification_handler)
            await asyncio.sleep(0.5)

            print()
            print(f"TX: {hex_bytes(PROFILE_READ_CMD)}")
            print("This script sends ONLY the candidate read command 40 40.")
            await client.write_gatt_char(CC02_UUID, PROFILE_READ_CMD, response=True)

            try:
                await asyncio.wait_for(response_event.wait(), timeout=RESPONSE_TIMEOUT)
            except asyncio.TimeoutError:
                print()
                print(f"No 40 40 response seen within {RESPONSE_TIMEOUT:.0f} seconds.")
                if received:
                    print("Other notifications were received; see RX lines above.")
                else:
                    print("No notifications were received.")

            await asyncio.sleep(0.5)
            await client.stop_notify(CC02_UUID)

    except Exception as exc:
        print()
        print(f"ERROR: {type(exc).__name__}: {exc}")
        return 5

    print()
    matching = [
        p for p in received
        if len(p) >= 2 and p[0] == 0x40 and p[1] == 0x40
    ]

    if matching:
        print("Candidate profile response(s):")
        for p in matching:
            print(hex_bytes(p))
        print()
        print("Copy the full 40 40 response back into ChatGPT for decoding.")
    else:
        print("No candidate 40 40 profile response captured.")

    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(asyncio.run(main()))
    except KeyboardInterrupt:
        print("\nCancelled.")
        raise SystemExit(130)
