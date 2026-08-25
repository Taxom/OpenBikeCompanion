# Reverse-Engineered BLE Protocol Notes

This document describes the bicycle-computer BLE protocol currently understood by OpenBike Companion.

It is an independent reverse-engineering effort based on observed Bluetooth traffic and testing on real hardware. It is not an official Magene protocol specification.

## Status terminology

- **Confirmed** — observed and successfully reproduced on real hardware.
- **Inferred** — strongly suggested by the protocol structure or LCD layout, but not individually verified.
- **Unknown** — observed, but not yet understood.

All byte sequences below are hexadecimal unless stated otherwise.

## BLE transport

### Command service

Service UUID:

```text
8ce5cc01-0a4d-11e9-ab14-d663bd873d93
```

### CC02 — command channel

Characteristic UUID:

```text
8ce5cc02-0a4d-11e9-ab14-d663bd873d93
```

Observed use:

- commands are written by the client;
- responses and acknowledgements are returned as notifications;
- configuration read/write commands use this channel.

### CC03 — bulk notification channel

Characteristic UUID:

```text
8ce5cc03-0a4d-11e9-ab14-d663bd873d93
```

Observed use:

- high-volume notifications;
- ride/FIT download data is streamed on this characteristic.

## Byte order

Observed 32-bit timestamps and ride identifiers are little-endian.

Example:

```text
C4 1C 86 6A
```

represents the same 32-bit value as:

```text
0x6A861CC4
```

## Data pages

Data-page configuration is fully confirmed.

Each page contains exactly seven metric IDs in this order:

```text
0  Big
1  Row 1 Left
2  Row 1 Right
3  Row 2 Left
4  Row 2 Right
5  Row 3 Left
6  Row 3 Right
```

The physical layout is:

```text
+-----------------------+
|          Big          |
+-----------+-----------+
| Row 1 L   | Row 1 R   |
+-----------+-----------+
| Row 2 L   | Row 2 R   |
+-----------+-----------+
| Row 3 L   | Row 3 R   |
+-----------+-----------+
```

### Read pages — confirmed

Request:

```text
40 42
```

Response:

```text
40 42 00 <page_count> <page data...>
```

Each page contributes exactly seven bytes.

Example with three pages:

```text
40 42 00 03
94 31 71 A4 93 64 11
64 44 63 83 92 51 23
94 44 71 A4 93 70 31
```

### Write pages — confirmed

Request:

```text
40 43 <page_count> <page data...>
```

Successful acknowledgement:

```text
40 43 00
```

The write command has been verified end-to-end on real hardware, including subsequent readback.

## Metric IDs

The upper nibble identifies the metric family in the observed scheme.

For several families, the lower nibble follows this pattern:

```text
x0  Lap Maximum
x1  Lap Average
x2  Maximum
x3  Average
x4  Current
```

This modifier pattern is fully confirmed for Cadence and is consistent with other observed metric families.

### Special

| ID | Metric | Status |
|---:|---|---|
| 11 | Clock | Confirmed |

### Grade

| ID | Metric | Status |
|---:|---|---|
| 20 | Lap Max Grade | Inferred |
| 21 | Lap Avg Grade | Inferred |
| 22 | Max Grade | Inferred |
| 23 | Avg Grade | Confirmed |
| 24 | Grade | Confirmed |

### Distance

| ID | Metric | Status |
|---:|---|---|
| 30 | Lap Distance | Confirmed |
| 31 | Distance | Confirmed |

### Cadence

| ID | Metric | Status |
|---:|---|---|
| 40 | Lap Max Cadence | Confirmed |
| 41 | Lap Avg Cadence | Confirmed |
| 42 | Max Cadence | Confirmed |
| 43 | Avg Cadence | Confirmed |
| 44 | Cadence | Confirmed |

### Calories

| ID | Metric | Status |
|---:|---|---|
| 50 | Lap Calories | Confirmed |
| 51 | Calories | Confirmed |

### Power

| ID | Metric | Status |
|---:|---|---|
| 60 | Lap Max Power | Inferred |
| 61 | Lap Avg Power | Inferred |
| 62 | Max Power | Inferred |
| 63 | Avg Power | Confirmed |
| 64 | Power | Confirmed |

### Time

| ID | Metric | Status |
|---:|---|---|
| 70 | Lap Time | Confirmed |
| 71 | Total Time | Confirmed |

### Altitude

| ID | Metric | Status |
|---:|---|---|
| 80 | Lap Max Altitude | Inferred |
| 81 | Lap Avg Altitude | Inferred |
| 82 | Max Altitude | Inferred |
| 83 | Avg Altitude | Confirmed |
| 84 | Altitude | Confirmed |

### Speed

| ID | Metric | Status |
|---:|---|---|
| 90 | Lap Max Speed | Inferred |
| 91 | Lap Avg Speed | Inferred |
| 92 | Max Speed | Confirmed |
| 93 | Avg Speed | Confirmed |
| 94 | Speed | Confirmed |

### Heart rate

| ID | Metric | Status |
|---:|---|---|
| A0 | Lap Max Heart Rate | Inferred |
| A1 | Lap Avg Heart Rate | Inferred |
| A2 | Max Heart Rate | Inferred |
| A3 | Avg Heart Rate | Inferred |
| A4 | Heart Rate | Confirmed |

## LCD field restrictions

The C406 uses a segmented LCD. A stored metric ID does not necessarily mean that every physical field can display that metric correctly.

The application therefore uses a position-specific whitelist.

### Big field

Confirmed:

```text
94  Speed
64  Power
```

Other tested metric IDs are not currently exposed by OpenBike Companion in the big field.

### Row 1 Left / Row 3 Right

Physical label group:

```text
GRA / DIS / CAD
```

Supported families used by the application:

```text
Grade family
Distance / Lap Distance
Cadence family
Clock
```

### Row 1 Right / Row 3 Left

Physical label group:

```text
CAL / PWR / TOL
```

Supported families used by the application:

```text
Calories / Lap Calories
Power family
Total Time / Lap Time
Clock
```

### Row 2 Left / Row 2 Right

Physical label group:

```text
ASL / SPD / BPM
```

Supported families used by the application:

```text
Altitude family
Speed family
Heart-rate family
```

Clock cannot be displayed correctly in the middle row because of the physical digit/colon segment arrangement.

## Rider profile

Rider-profile read and write are confirmed on a physical Magene C406.

The profile stored on the bike computer contains the numeric rider parameters used by the device. First name, last name, and date of birth are not present in this BLE payload; the application converts date of birth to age before synchronizing the profile.

### Read rider profile

Request:

```text
40 40
```

Successful response:

```text
40 40 00 <11-byte profile payload>
```

Confirmed example:

```text
40 40 00 02 2C AF BE B4 96 00 F2 03 84 17
```

Payload layout after the `00` status byte:

```text
byte 0      Gender raw value
byte 1      Age, years
byte 2      Height, cm
byte 3      Maximum heart rate, bpm
byte 4      LTHR, bpm
bytes 5-6   FTP, uint16 little-endian, watts
bytes 7-8   Vehicle weight, uint16 little-endian, 0.01 kg units
bytes 9-10  Rider weight, uint16 little-endian, 0.01 kg units
```

The confirmed example above decodes as:

```text
Gender raw      02
Age             44 years
Height          175 cm
Max HR          190 bpm
LTHR            180 bpm
FTP             150 W
Vehicle weight  10.10 kg
Rider weight    60.20 kg
```

`Gender = 02` has been observed for an unset / not-selected profile. The meanings of the other possible gender values have not yet been confirmed.

### Write rider profile

Request:

```text
40 41 <11-byte profile payload>
```

Successful acknowledgement:

```text
40 41 00
```

Write and readback have been verified end-to-end on real hardware. In one test, changing rider weight from `60.10 kg` to `60.20 kg` produced:

```text
TX  40 41 02 2C AF BE B4 96 00 F2 03 84 17
RX  40 41 00
```

A subsequent `40 40` read returned the same payload, confirming that the new profile was stored by the C406.

## Function settings

Function-settings read and write are confirmed on a physical Magene C406.

### Read function settings

Request:

```text
40 4C
```

Successful response:

```text
40 4C 00 <11-byte settings payload>
```

Confirmed example:

```text
40 4C 00 08 01 03 00 01 01 01 01 B5 C8 00
```

Payload layout after the `00` status byte:

```text
byte 0      Time-zone raw value
byte 1      Auto backlight
byte 2      Auto shutdown timeout, minutes
byte 3      Auto Pause
byte 4      Function tone
byte 5      Keyboard clicks
byte 6      Estimated power
byte 7      Start reminding
byte 8      Heart-rate warning threshold, bpm
bytes 9-10  Power warning threshold, uint16 little-endian, watts
```

Confirmed boolean values use:

```text
00 = Off
01 = On
```

Confirmed time-zone values include:

```text
07 = UTC+7
08 = UTC+8
```

The value `1C` has also been observed when the original application was using the phone/system time-zone synchronization mode. Its exact semantics are therefore currently **Inferred**, not Confirmed.

Auto shutdown is stored as a timeout value rather than a simple Boolean:

```text
00 = disabled
03 = 3 minutes
05 = 5 minutes
```

The `03` value was verified by writing it to the C406 and observing that the unit powered off exactly three minutes after the BLE connection ended.

Heart-rate warning uses the threshold byte directly:

```text
00 = disabled
B4 = 180 bpm
B5 = 181 bpm
```

Power warning uses a little-endian 16-bit threshold:

```text
00 00 = disabled
C8 00 = 200 W
```

### Write function settings

Request:

```text
40 4D <11-byte settings payload>
```

Successful acknowledgement:

```text
40 4D 00
```

Write and readback have been verified end-to-end on real hardware.

For example, changing Auto shutdown from five minutes to three minutes produced:

```text
TX  40 4D 08 01 03 00 01 01 01 01 B5 C8 00
RX  40 4D 00
```

A subsequent `40 4C` read returned:

```text
40 4C 00 08 01 03 00 01 01 01 01 B5 C8 00
```

## Ride list

Ride-list access is confirmed.

Initial request:

```text
40 49 00 00 00 00
```

Subsequent requests use the previous-page cursor / ride identifier:

```text
40 49 <cursor_le32>
```

Observed response structure:

```text
40 49 00 <count> <more> <ride IDs...>
```

Ride IDs are four-byte little-endian values and correspond to ride start timestamps observed on the device.

This structure should still be treated as provisional until more devices and firmware versions are tested.

## Ride download

Ride download is confirmed.

Request:

```text
40 4A <ride_id_le32>
```

Acknowledgement:

```text
40 4A 00
```

The ride data is then streamed through the CC03 characteristic.

A valid FIT file has been reconstructed from this stream and successfully saved.

The exact CC03 chunk framing is not yet documented here and should be considered work in progress.

## Other observed commands

The following commands have been observed but are not yet fully documented:

```text
40 08
40 44
40 4E
40 4F
```

Some of these appear to form read/write pairs or initialization/configuration commands, but they should not be relied upon until their semantics are confirmed.

Additional traffic has also been observed on:

```text
8ce5ee02-0a4d-11e9-ab14-d663bd873d93
```

Its role is not yet understood.

## Safe write strategy

OpenBike Companion currently treats configuration writes conservatively.

For Pages, the implemented sequence is:

```text
1. Read current Pages with 40 42
2. Confirm that they still match the editor's original snapshot
3. Write the edited Pages using 40 43
4. Require 40 43 00 acknowledgement
5. Read Pages again using 40 42
6. Compare the readback byte-for-byte with the requested configuration
7. If verification fails, attempt to restore the original Pages
8. Read again and verify the rollback
```

This behavior is intentional because a GATT write succeeding does not by itself prove that the requested configuration is valid or correctly applied by the device.

## Compatibility

The protocol documented here has currently been tested primarily against the Magene C406 used during development.

Other Magene bicycle computers may use some or all of the same services and command formats, but compatibility must not be assumed until tested.

## Contributions

Protocol captures, observations, firmware-version differences, and tests on other compatible bicycle computers are welcome.

When adding protocol information, please clearly distinguish:

```text
Confirmed
Inferred
Unknown
```

and include enough evidence for the result to be reproduced.
