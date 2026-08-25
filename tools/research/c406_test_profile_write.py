import asyncio
from datetime import datetime

from bleak import BleakClient, BleakScanner

SERVICE_UUID = "8ce5cc01-0a4d-11e9-ab14-d663bd873d93"
CC02_UUID = "8ce5cc02-0a4d-11e9-ab14-d663bd873d93"

PROFILE_READ_CMD = bytes([0x40, 0x40])

SCAN_SECONDS = 8.0
RESPONSE_TIMEOUT = 5.0


def hex_bytes(data: bytes) -> str:
    return " ".join(f"{b:02X}" for b in data)


def u16le(data: bytes, offset: int) -> int:
    return data[offset] | (data[offset + 1] << 8)


def put_u16le(buf: bytearray, offset: int, value: int) -> None:
    buf[offset] = value & 0xFF
    buf[offset + 1] = (value >> 8) & 0xFF


def decode_profile_response(packet: bytes) -> dict:
    if len(packet) != 14 or packet[:3] != bytes([0x40, 0x40, 0x00]):
        raise ValueError(
            "Expected 14-byte successful 40 40 response, got: "
            + hex_bytes(packet)
        )

    return {
        "gender_raw": packet[3],
        "age": packet[4],
        "height_cm": packet[5],
        "max_hr_bpm": packet[6],
        "lthr_bpm": packet[7],
        "ftp_w": u16le(packet, 8),
        "vehicle_weight_kg": u16le(packet, 10) / 100.0,
        "rider_weight_kg": u16le(packet, 12) / 100.0,
    }


def print_profile(profile: dict) -> None:
    print(f"  Gender raw:    0x{profile['gender_raw']:02X}")
    print(f"  Age:           {profile['age']}")
    print(f"  Height:        {profile['height_cm']} cm")
    print(f"  Max HR:        {profile['max_hr_bpm']} bpm")
    print(f"  LTHR:          {profile['lthr_bpm']} bpm")
    print(f"  FTP:           {profile['ftp_w']} W")
    print(f"  Vehicle weight:{profile['vehicle_weight_kg']:.2f} kg")
    print(f"  Rider weight:  {profile['rider_weight_kg']:.2f} kg")


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
    profile_event = asyncio.Event()
    write_ack_event = asyncio.Event()

    def notification_handler(_sender, data: bytearray):
        packet = bytes(data)
        rx_packets.append(packet)
        stamp = datetime.now().strftime("%H:%M:%S.%f")[:-3]
        print(f"RX {stamp}: {hex_bytes(packet)}")

        if len(packet) >= 2 and packet[:2] == bytes([0x40, 0x40]):
            profile_event.set()

        if len(packet) >= 2 and packet[:2] == bytes([0x40, 0x41]):
            write_ack_event.set()

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

        # 1) Read current profile.
        print(f"\nTX READ: {hex_bytes(PROFILE_READ_CMD)}")
        rx_packets.clear()
        profile_event.clear()
        await client.write_gatt_char(
            CC02_UUID, PROFILE_READ_CMD, response=True
        )

        try:
            await asyncio.wait_for(
                profile_event.wait(),
                timeout=RESPONSE_TIMEOUT
            )
        except asyncio.TimeoutError:
            raise RuntimeError("No 40 40 profile response")

        current_packet = next(
            p for p in reversed(rx_packets)
            if len(p) >= 2 and p[:2] == bytes([0x40, 0x40])
        )

        current = decode_profile_response(current_packet)
        print("\nCurrent profile:")
        print_profile(current)

        # Default test: +0.10 kg rider weight.
        default_weight = round(current["rider_weight_kg"] + 0.10, 2)
        raw = input(
            f"\nNew rider weight in kg "
            f"[Enter = {default_weight:.2f}]: "
        ).strip()

        if raw:
            new_weight = float(raw.replace(",", "."))
        else:
            new_weight = default_weight

        weight_raw = round(new_weight * 100)
        if not 0 <= weight_raw <= 0xFFFF:
            raise ValueError("Weight is outside uint16 range")

        # Read response = 40 40 00 + 11-byte payload.
        # Candidate write = 40 41 + same 11-byte payload.
        payload = bytearray(current_packet[3:])
        put_u16le(payload, 9, weight_raw)
        write_packet = bytes([0x40, 0x41]) + bytes(payload)

        print("\nCandidate WRITE packet:")
        print(f"TX WRITE: {hex_bytes(write_packet)}")
        print(
            f"Only rider weight changes: "
            f"{current['rider_weight_kg']:.2f} -> {new_weight:.2f} kg"
        )

        confirm = input("Send this 40 41 packet? [y/N]: ").strip().lower()
        if confirm != "y":
            print("Cancelled. Nothing written.")
            await client.stop_notify(CC02_UUID)
            return

        rx_packets.clear()
        write_ack_event.clear()

        await client.write_gatt_char(
            CC02_UUID, write_packet, response=True
        )

        try:
            await asyncio.wait_for(
                write_ack_event.wait(),
                timeout=RESPONSE_TIMEOUT
            )
        except asyncio.TimeoutError:
            print(
                f"No 40 41 ACK within {RESPONSE_TIMEOUT:.0f} seconds."
            )

        # 2) Read back regardless of ACK presence.
        await asyncio.sleep(0.5)
        print(f"\nTX VERIFY READ: {hex_bytes(PROFILE_READ_CMD)}")
        rx_packets.clear()
        profile_event.clear()

        await client.write_gatt_char(
            CC02_UUID, PROFILE_READ_CMD, response=True
        )

        try:
            await asyncio.wait_for(
                profile_event.wait(),
                timeout=RESPONSE_TIMEOUT
            )
        except asyncio.TimeoutError:
            raise RuntimeError("No 40 40 readback response")

        verify_packet = next(
            p for p in reversed(rx_packets)
            if len(p) >= 2 and p[:2] == bytes([0x40, 0x40])
        )

        verify = decode_profile_response(verify_packet)

        print("\nReadback profile:")
        print_profile(verify)

        if abs(verify["rider_weight_kg"] - new_weight) < 0.005:
            print("\nSUCCESS: 40 41 write confirmed by 40 40 readback.")
        else:
            print(
                "\nWRITE NOT CONFIRMED: readback rider weight is "
                f"{verify['rider_weight_kg']:.2f} kg."
            )

        await client.stop_notify(CC02_UUID)


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\nCancelled.")
    except Exception as exc:
        print(f"\nERROR: {type(exc).__name__}: {exc}")
