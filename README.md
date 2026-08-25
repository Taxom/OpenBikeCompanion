# OpenBike Companion

OpenBike Companion is an independent open-source Android companion app for bicycle computers.

The project is currently focused on reverse-engineering and supporting Magene bicycle computers without requiring the manufacturer's cloud account or official companion application.

## Current status

### Magene C406

Tested on a physical Magene C406.

Currently implemented and verified:

- Bluetooth Low Energy device discovery
- GATT connection and compatible-device identification
- Reading the current data-page configuration
- Displaying configured pages and fields
- Local page editing
- Field compatibility restrictions based on the C406 LCD segment layout
- Writing modified page configurations to the C406
- Pre-write safety read
- Device acknowledgement checking
- Post-write readback verification
- Automatic rollback attempt if verification fails

The Pages implementation has been verified end-to-end on real hardware.

## Supported devices

| Device | Status |
| --- | --- |
| Magene C406 | Tested |
| Other Magene bicycle computers | Not yet tested |

Support for additional compatible devices may be added as their protocols are investigated.

## Android support

Minimum Android version:

- Android 7.0 / API 24

The application is written in Kotlin using Jetpack Compose.

## Project status

This project is under active development.

The current code should be considered experimental. Operations that modify device configuration have been reverse-engineered from observed Bluetooth communication and tested on limited hardware.

## Disclaimer

OpenBike Companion is an independent project and is not affiliated with, endorsed by, or sponsored by Magene.

Product names and trademarks belong to their respective owners.

## License

OpenBike Companion is licensed under the GNU General Public License v3.0 or later (`GPL-3.0-or-later`).

See `LICENSE` for the full license text.
