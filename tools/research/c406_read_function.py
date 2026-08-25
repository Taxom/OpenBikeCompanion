import asyncio
from datetime import datetime

from bleak import BleakClient, BleakScanner

SERVICE_UUID = "8ce5cc01-0a4d-11e9-ab14-d663bd873d93"
CC02_UUID = "8ce5cc02-0a4d-11e9-ab14-d663bd873d93"

FUNCTION_READ_CMD = bytes([0x40, 0x4C])

SCAN_SECONDS = 8.0
RESPONSE_TIMEOUT = 6.0


def hex_bytes(data: bytes) -> str:
    return " ".join(f"{b:02X}" for b in data)


async def scan_devices():
    found = {}

    def detection_callback(device, advertisement_data):
        name = advertisement_data.local_name or device.name or "(unnamed)"
        found[device.address] = {
            "device": device,
            "name": name,
            "rssi": advertisement_data.rssi,
            "services": [
                u.lower()
                for u in (advertisement_data.service_uuids or [])
            ],
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
        )
    )
    return items


async def choose_device():
    devices = await scan_devices()

    if not devices:
        print("No BLE devices found.")
        return None

    print("\nDiscovered devices:")
    for idx, item in enumerate(devices, start=1):
        marker = (
            "  <== advertises C406 service"
            if SERVICE_UUID in item["services"]
            else ""
        )
        rssi = (
            f"{item['rssi']} dBm"
            if item["rssi"] is not None
            else "RSSI ?"
        )
        print(
            f"{idx:2d}. {item['name']}  "
            f"[{item['device'].address}]  {rssi}{marker}"
        )

    while True:
        raw = input("\nSelect device number (or q to quit): ").strip()

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
        return

    print(
        f"\nConnecting to {device.name or '(unnamed)'} "
        f"[{device.address}]..."
    )

    response_event = asyncio.Event()
    received = []

    def notification_handler(_sender, data: bytearray):
        packet = bytes(data)
        received.append(packet)

        stamp = datetime.now().strftime("%H:%M:%S.%f")[:-3]
        print(f"RX {stamp}: {hex_bytes(packet)}")

        if (
            len(packet) >= 2
            and packet[0] == 0x40
            and packet[1] == 0x4C
        ):
            response_event.set()

    try:
        async with BleakClient(device) as client:
            if not client.is_connected:
                raise RuntimeError("Connection failed")

            print("Connected.")

            services = client.services

            if services.get_service(SERVICE_UUID) is None:
                raise RuntimeError(
                    f"Service not found: {SERVICE_UUID}"
                )

            if services.get_characteristic(CC02_UUID) is None:
                raise RuntimeError(
                    f"Characteristic not found: {CC02_UUID}"
                )

            print(f"Found CC02: {CC02_UUID}")
            print("Enabling notifications...")

            await client.start_notify(
                CC02_UUID,
                notification_handler
            )

            await asyncio.sleep(0.5)

            print()
            print(f"TX: {hex_bytes(FUNCTION_READ_CMD)}")
            print(
                "This script sends ONLY the candidate "
                "Function read command 40 4C."
            )

            await client.write_gatt_char(
                CC02_UUID,
                FUNCTION_READ_CMD,
                response=True
            )

            try:
                await asyncio.wait_for(
                    response_event.wait(),
                    timeout=RESPONSE_TIMEOUT
                )
            except asyncio.TimeoutError:
                print()
                print(
                    f"No 40 4C response seen within "
                    f"{RESPONSE_TIMEOUT:.0f} seconds."
                )

                if received:
                    print(
                        "Other notifications were received; "
                        "see RX lines above."
                    )
                else:
                    print("No notifications were received.")

            await asyncio.sleep(0.5)
            await client.stop_notify(CC02_UUID)

    except Exception as exc:
        print(
            f"\nERROR: {type(exc).__name__}: {exc}"
        )
        return

    matching = [
        packet
        for packet in received
        if (
            len(packet) >= 2
            and packet[0] == 0x40
            and packet[1] == 0x4C
        )
    ]

    print()

    if matching:
        print("Candidate Function response(s):")
        for packet in matching:
            print(hex_bytes(packet))

        print()
        print(
            "Copy the full 40 4C response back into ChatGPT "
            "for decoding."
        )
    else:
        print("No candidate 40 4C Function response captured.")


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\nCancelled.")
