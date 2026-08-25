package com.maxpi.openbikecompanion.protocol

import java.util.UUID

object C406Protocol {

    val CC_SERVICE_UUID: UUID =
        UUID.fromString("8ce5cc01-0a4d-11e9-ab14-d663bd873d93")

    val CC02_UUID: UUID =
        UUID.fromString("8ce5cc02-0a4d-11e9-ab14-d663bd873d93")

    val CCCD_UUID: UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    val FIELD_POSITIONS = listOf(
        "Big",
        "Row 1 Left",
        "Row 1 Right",
        "Row 2 Left",
        "Row 2 Right",
        "Row 3 Left",
        "Row 3 Right"
    )

    val METRIC_NAMES = mapOf(
        0x11 to "Clock",

        0x20 to "Lap Max Grade",
        0x21 to "Lap Avg Grade",
        0x22 to "Max Grade",
        0x23 to "Avg Grade",
        0x24 to "Grade",

        0x30 to "Lap Distance",
        0x31 to "Distance",

        0x40 to "Lap Max Cadence",
        0x41 to "Lap Avg Cadence",
        0x42 to "Max Cadence",
        0x43 to "Avg Cadence",
        0x44 to "Cadence",

        0x50 to "Lap Calories",
        0x51 to "Calories",

        0x60 to "Lap Max Power",
        0x61 to "Lap Avg Power",
        0x62 to "Max Power",
        0x63 to "Avg Power",
        0x64 to "Power",

        0x70 to "Lap Time",
        0x71 to "Total Time",

        0x80 to "Lap Max Altitude",
        0x81 to "Lap Avg Altitude",
        0x82 to "Max Altitude",
        0x83 to "Avg Altitude",
        0x84 to "Altitude",

        0x90 to "Lap Max Speed",
        0x91 to "Lap Avg Speed",
        0x92 to "Max Speed",
        0x93 to "Avg Speed",
        0x94 to "Speed",

        0xA0 to "Lap Max Heart Rate",
        0xA1 to "Lap Avg Heart Rate",
        0xA2 to "Max Heart Rate",
        0xA3 to "Avg Heart Rate",
        0xA4 to "Heart Rate"
    )

    /*
     * Conservative whitelist based on the physical C406 LCD segment layout
     * and the hardware tests performed so far.
     */
    val ALLOWED_METRICS_BY_FIELD = mapOf(
        0 to listOf(
            0x94, // Speed
            0x64  // Power
        ),

        1 to listOf(
            0x24, 0x23, 0x22, 0x21, 0x20,
            0x31, 0x30,
            0x44, 0x43, 0x42, 0x41, 0x40,
            0x11
        ),

        2 to listOf(
            0x51, 0x50,
            0x64, 0x63, 0x62, 0x61, 0x60,
            0x71, 0x70,
            0x11
        ),

        3 to listOf(
            0x84, 0x83, 0x82, 0x81, 0x80,
            0x94, 0x93, 0x92, 0x91, 0x90,
            0xA4, 0xA3, 0xA2, 0xA1, 0xA0
        ),

        4 to listOf(
            0x84, 0x83, 0x82, 0x81, 0x80,
            0x94, 0x93, 0x92, 0x91, 0x90,
            0xA4, 0xA3, 0xA2, 0xA1, 0xA0
        ),

        5 to listOf(
            0x51, 0x50,
            0x64, 0x63, 0x62, 0x61, 0x60,
            0x71, 0x70,
            0x11
        ),

        6 to listOf(
            0x24, 0x23, 0x22, 0x21, 0x20,
            0x31, 0x30,
            0x44, 0x43, 0x42, 0x41, 0x40,
            0x11
        )
    )
}
