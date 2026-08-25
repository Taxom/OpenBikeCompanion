import asyncio
from datetime import datetime

from bleak import BleakClient, BleakScanner

SERVICE_UUID = "8ce5cc01-0a4d-11e9-ab14-d663bd873d93"
CC02_UUID = "8ce5cc02-0a4d-11e9-ab14-d663bd873d93"

FUNCTION_READ_CMD = bytes([0x40, 0x4C])

SCAN_SECONDS = 8.0
RESPONSE_TIMEOUT = 5.0


def hex_bytes(data: bytes) -> str:
    return " ".join(f"{b:02X}" for b in data)


def u16le(data: bytes, offset: int) -> int:
    return data[offset] | (data[offset + 1] << 8)


def decode_function_response(packet: bytes) -> dict:
    if len(packet) != 14 or packet[:3] != bytes([0x40, 0x4C, 0x00]):
        raise ValueError(
            "Expected 14-byte successful 40 4C response, got: "
            + hex_bytes(packet)
        )

    payload = packet[3:]

    return {
        "timezone_raw": payload[0],
        "auto_backlight_raw": payload[1],
        "auto_shutdown_raw": payload[2],
        "auto_pause_raw": payload[3],
        "function_tone_raw": payload[4],
        "keyboard_clicks_raw": payload[5],
        "estimated_power_raw": payload[6],
        "start_reminding_raw": payload[7],
        "heart_warning_bpm": payload[8],
        "power_warning_w": u16le(payload, 9),
    }


def print_function(data: dict) -> None:
    print(f"  Time zone raw:       0x{data['timezone_raw']:02X}")
    print(f"  Auto backlight raw:  0x{data['auto_backlight_raw']:02X}")
    print(f"  Auto shutdown raw:   0x{data['auto_shutdown_raw']:02X}")
    print(f"  Auto pause raw:      0x{data['auto_pause_raw']:02X}")
    print(f"  Function tone raw:   0x{data['function_tone_raw']:02X}")
    print(f"  Keyboard clicks raw: 0x{data['keyboard_clicks_raw']:02X}")
    print(f"  Estimated power raw: 0x{data['estimated_power_raw']:02X}")
    print(f"  Start reminder raw:  0x{data['start_reminding_raw']:02X}")
    print(f"  HR warning:          {data['heart_warning_bpm']} bpm")
    print(f"  Power warning:       {data['power_warning_w']} W")


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
            idx = int(raw)
            if 1 <= idx <= len(devices):
                return devices[idx - 1]["device"]
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

    rx_packets = []
    function_event = asyncio.Event()
    write_ack_event = asyncio.Event()

    def notification_handler(_sender, data: bytearray):
        packet = bytes(data)
        rx_packets.append(packet)

        stamp = datetime.now().strftime("%H:%M:%S.%f")[:-3]
        print(f"RX {stamp}: {hex_bytes(packet)}")

        if len(packet) >= 2 and packet[:2] == bytes([0x40, 0x4C]):
            function_event.set()

        if len(packet) >= 2 and packet[:2] == bytes([0x40, 0x4D]):
            write_ack_event.set()

    try:
        async with BleakClient(device) as client:
            if not client.is_connected:
                raise RuntimeError("Connection failed")

            print("Connected.")

            services = client.services
            if services.get_service(SERVICE_UUID) is None:
                raise RuntimeError(f"Service not found: {SERVICE_UUID}")

            if services.get_characteristic(CC02_UUID) is None:
                raise RuntimeError(f"CC02 not found: {CC02_UUID}")

            print("Enabling notifications...")
            await client.start_notify(CC02_UUID, notification_handler)
            await asyncio.sleep(0.4)

            # 1) Read current Function settings.
            print(f"\nTX READ: {hex_bytes(FUNCTION_READ_CMD)}")
            rx_packets.clear()
            function_event.clear()

            await client.write_gatt_char(
                CC02_UUID,
                FUNCTION_READ_CMD,
                response=True
            )

            try:
                await asyncio.wait_for(
                    function_event.wait(),
                    timeout=RESPONSE_TIMEOUT
                )
            except asyncio.TimeoutError:
                raise RuntimeError("No 40 4C Function response")

            current_packet = next(
                p for p in reversed(rx_packets)
                if len(p) >= 2 and p[:2] == bytes([0x40, 0x4C])
            )

            current = decode_function_response(current_packet)

            print("\nCurrent Function settings:")
            print_function(current)

            raw = input(
                "\nNew auto-shutdown value in minutes "
                "[Enter = 3]: "
            ).strip()

            if raw:
                minutes = int(raw)
            else:
                minutes = 3

            if not 0 <= minutes <= 255:
                raise ValueError("Auto-shutdown value must fit in one byte")

            # Read response = 40 4C 00 + 11-byte payload.
            # Candidate write = 40 4D + same 11-byte payload.
            payload = bytearray(current_packet[3:])
            old_value = payload[2]
            payload[2] = minutes
            write_packet = bytes([0x40, 0x4D]) + bytes(payload)

            print("\nCandidate WRITE packet:")
            print(f"TX WRITE: {hex_bytes(write_packet)}")
            print(
                f"Only auto-shutdown byte changes: "
                f"0x{old_value:02X} -> 0x{minutes:02X}"
            )

            confirm = input("Send this 40 4D packet? [y/N]: ").strip().lower()
            if confirm != "y":
                print("Cancelled. Nothing written.")
                await client.stop_notify(CC02_UUID)
                return

            rx_packets.clear()
            write_ack_event.clear()

            await client.write_gatt_char(
                CC02_UUID,
                write_packet,
                response=True
            )

            try:
                await asyncio.wait_for(
                    write_ack_event.wait(),
                    timeout=RESPONSE_TIMEOUT
                )
            except asyncio.TimeoutError:
                print(
                    f"No 40 4D ACK within {RESPONSE_TIMEOUT:.0f} seconds."
                )

            # 2) Verify with 40 4C.
            await asyncio.sleep(0.5)

            print(f"\nTX VERIFY READ: {hex_bytes(FUNCTION_READ_CMD)}")
            rx_packets.clear()
            function_event.clear()

            await client.write_gatt_char(
                CC02_UUID,
                FUNCTION_READ_CMD,
                response=True
            )

            try:
                await asyncio.wait_for(
                    function_event.wait(),
                    timeout=RESPONSE_TIMEOUT
                )
            except asyncio.TimeoutError:
                raise RuntimeError("No 40 4C readback response")

            verify_packet = next(
                p for p in reversed(rx_packets)
                if len(p) >= 2 and p[:2] == bytes([0x40, 0x4C])
            )

            verify = decode_function_response(verify_packet)

            print("\nReadback Function settings:")
            print_function(verify)

            if verify["auto_shutdown_raw"] == minutes:
                print(
                    f"\nSUCCESS: 40 4D write confirmed; "
                    f"auto-shutdown byte is now 0x{minutes:02X}."
                )
                print(
                    f"If this byte is minutes, C406 should power off "
                    f"about {minutes} minute(s) after inactivity/disconnect."
                )
            else:
                print(
                    "\nWRITE NOT CONFIRMED: readback auto-shutdown byte is "
                    f"0x{verify['auto_shutdown_raw']:02X}."
                )

            await client.stop_notify(CC02_UUID)

    except Exception as exc:
        print(f"\nERROR: {type(exc).__name__}: {exc}")


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\nCancelled.")
