package com.maxpi.openbikecompanion.protocol

import com.maxpi.openbikecompanion.model.PageFieldUi
import com.maxpi.openbikecompanion.model.PageUi
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class C406PagesCodecTest {

    @Test
    fun decodeConfirmedThreePageResponse() {
        val response = bytes(
            0x40, 0x42, 0x00, 0x03,
            0x94, 0x31, 0x71, 0xA4, 0x93, 0x64, 0x11,
            0x64, 0x44, 0x63, 0x83, 0x92, 0x51, 0x23,
            0x94, 0x44, 0x71, 0xA4, 0x93, 0x70, 0x31
        )

        val pages = C406PagesCodec.decodePagesResponse(response)

        assertNotNull(pages)
        pages!!

        assertEquals(3, pages.size)
        assertEquals(
            listOf(0x94, 0x31, 0x71, 0xA4, 0x93, 0x64, 0x11),
            pages[0].fields.map { it.code }
        )
        assertEquals("Speed", pages[0].fields[0].name)
        assertEquals("Distance", pages[0].fields[1].name)
        assertEquals("Clock", pages[0].fields[6].name)
    }

    @Test
    fun buildWritePacketFromConfirmedThreePageConfiguration() {
        val response = bytes(
            0x40, 0x42, 0x00, 0x03,
            0x94, 0x31, 0x71, 0xA4, 0x93, 0x64, 0x11,
            0x64, 0x44, 0x63, 0x83, 0x92, 0x51, 0x23,
            0x94, 0x44, 0x71, 0xA4, 0x93, 0x70, 0x31
        )

        val pages = C406PagesCodec.decodePagesResponse(response)
        val packet = C406PagesCodec.buildPagesWritePacket(pages!!)

        assertArrayEquals(
            bytes(
                0x40, 0x43, 0x03,
                0x94, 0x31, 0x71, 0xA4, 0x93, 0x64, 0x11,
                0x64, 0x44, 0x63, 0x83, 0x92, 0x51, 0x23,
                0x94, 0x44, 0x71, 0xA4, 0x93, 0x70, 0x31
            ),
            packet
        )
    }

    @Test
    fun buildWritePacketSortsPagesByNumber() {
        val page1 = page(
            1,
            listOf(0x94, 0x31, 0x71, 0xA4, 0x93, 0x64, 0x11)
        )
        val page2 = page(
            2,
            listOf(0x64, 0x44, 0x63, 0x83, 0x92, 0x51, 0x23)
        )

        val packet = C406PagesCodec.buildPagesWritePacket(
            listOf(page2, page1)
        )

        assertArrayEquals(
            bytes(
                0x40, 0x43, 0x02,
                0x94, 0x31, 0x71, 0xA4, 0x93, 0x64, 0x11,
                0x64, 0x44, 0x63, 0x83, 0x92, 0x51, 0x23
            ),
            packet
        )
    }

    @Test
    fun rejectsInvalidInput() {
        assertNull(
            C406PagesCodec.decodePagesResponse(
                bytes(0x40, 0x42, 0x00)
            )
        )

        assertNull(
            C406PagesCodec.decodePagesResponse(
                bytes(0x40, 0x42, 0x00, 0x01, 0x94, 0x31)
            )
        )

        assertNull(
            C406PagesCodec.buildPagesWritePacket(emptyList())
        )
    }

    private fun page(number: Int, codes: List<Int>): PageUi {
        return PageUi(
            number = number,
            fields = codes.mapIndexed { index, code ->
                PageFieldUi(
                    position = C406Protocol.FIELD_POSITIONS[index],
                    code = code,
                    name = C406Protocol.METRIC_NAMES[code]
                        ?: "Unknown 0x%02X".format(code)
                )
            }
        )
    }

    private fun bytes(vararg values: Int): ByteArray {
        return ByteArray(values.size) { index ->
            values[index].toByte()
        }
    }
}
