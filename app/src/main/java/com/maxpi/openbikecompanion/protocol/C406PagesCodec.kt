package com.maxpi.openbikecompanion.protocol

import com.maxpi.openbikecompanion.model.PageFieldUi
import com.maxpi.openbikecompanion.model.PageUi
import com.maxpi.openbikecompanion.protocol.C406Protocol.FIELD_POSITIONS
import com.maxpi.openbikecompanion.protocol.C406Protocol.METRIC_NAMES

object C406PagesCodec {

    fun decodePagesResponse(value: ByteArray): List<PageUi>? {
        if (value.size < 4) return null

        val pageCount = value[3].toInt() and 0xFF
        val expectedLength = 4 + pageCount * 7

        if (value.size != expectedLength) return null

        return buildList {
            for (pageIndex in 0 until pageCount) {
                val fields = buildList {
                    for (fieldIndex in 0 until 7) {
                        val offset = 4 + pageIndex * 7 + fieldIndex
                        val code = value[offset].toInt() and 0xFF

                        add(
                            PageFieldUi(
                                position = FIELD_POSITIONS[fieldIndex],
                                code = code,
                                name = METRIC_NAMES[code]
                                    ?: "Unknown 0x%02X".format(code)
                            )
                        )
                    }
                }

                add(
                    PageUi(
                        number = pageIndex + 1,
                        fields = fields
                    )
                )
            }
        }
    }

    fun buildPagesWritePacket(pages: List<PageUi>): ByteArray? {
        if (pages.isEmpty() || pages.size > 255) return null

        val sortedPages = pages.sortedBy { it.number }

        if (sortedPages.any { it.fields.size != 7 }) return null

        val packet = ByteArray(3 + sortedPages.size * 7)

        packet[0] = 0x40
        packet[1] = 0x43
        packet[2] = sortedPages.size.toByte()

        var offset = 3

        for (page in sortedPages) {
            for (field in page.fields) {
                packet[offset++] = field.code.toByte()
            }
        }

        return packet
    }
}
