package com.maxpi.openbikecompanion.protocol

import com.maxpi.openbikecompanion.model.C406RiderProfile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class C406ProfileCodecTest {

    @Test
    fun decodeConfirmedProfileResponse() {
        val response = bytes(
            0x40, 0x40, 0x00,
            0x02,
            0x2C,
            0xAF,
            0xBE,
            0xB4,
            0x96, 0x00,
            0xF2, 0x03,
            0x84, 0x17
        )

        val profile = C406ProfileCodec.decodeReadResponse(response)!!

        assertEquals(0x02, profile.genderRaw)
        assertEquals(44, profile.age)
        assertEquals(175, profile.heightCm)
        assertEquals(190, profile.maxHeartRateBpm)
        assertEquals(180, profile.lthrBpm)
        assertEquals(150, profile.ftpWatts)
        assertEquals(1010, profile.vehicleWeightHundredthsKg)
        assertEquals(6020, profile.riderWeightHundredthsKg)
        assertEquals(10.10, profile.vehicleWeightKg, 0.0001)
        assertEquals(60.20, profile.riderWeightKg, 0.0001)
    }

    @Test
    fun buildConfirmedProfileWritePacket() {
        val profile = C406RiderProfile(
            genderRaw = 0x02,
            age = 44,
            heightCm = 175,
            maxHeartRateBpm = 190,
            lthrBpm = 180,
            ftpWatts = 150,
            vehicleWeightHundredthsKg = 1010,
            riderWeightHundredthsKg = 6020
        )

        assertArrayEquals(
            bytes(
                0x40, 0x41,
                0x02,
                0x2C,
                0xAF,
                0xBE,
                0xB4,
                0x96, 0x00,
                0xF2, 0x03,
                0x84, 0x17
            ),
            C406ProfileCodec.buildWritePacket(profile)
        )
    }

    @Test
    fun confirmedReadResponseRoundTripsToWritePayload() {
        val response = bytes(
            0x40, 0x40, 0x00,
            0x02, 0x2C, 0xAF, 0xBE, 0xB4,
            0x96, 0x00, 0xF2, 0x03, 0x84, 0x17
        )

        val profile = C406ProfileCodec.decodeReadResponse(response)!!
        val write = C406ProfileCodec.buildWritePacket(profile)!!

        assertArrayEquals(
            bytes(
                0x40, 0x41,
                0x02, 0x2C, 0xAF, 0xBE, 0xB4,
                0x96, 0x00, 0xF2, 0x03, 0x84, 0x17
            ),
            write
        )
    }

    @Test
    fun rejectsInvalidReadResponses() {
        assertNull(
            C406ProfileCodec.decodeReadResponse(
                bytes(0x40, 0x40, 0x00)
            )
        )

        assertNull(
            C406ProfileCodec.decodeReadResponse(
                bytes(
                    0x40, 0x40, 0x01,
                    0x02, 0x2C, 0xAF, 0xBE, 0xB4,
                    0x96, 0x00, 0xF2, 0x03, 0x84, 0x17
                )
            )
        )
    }

    @Test
    fun rejectsOutOfRangeWriteValues() {
        val invalid = C406RiderProfile(
            genderRaw = 0x02,
            age = 44,
            heightCm = 175,
            maxHeartRateBpm = 190,
            lthrBpm = 180,
            ftpWatts = 70000,
            vehicleWeightHundredthsKg = 1010,
            riderWeightHundredthsKg = 6020
        )

        assertNull(C406ProfileCodec.buildWritePacket(invalid))
    }

    private fun bytes(vararg values: Int): ByteArray {
        return ByteArray(values.size) { index ->
            values[index].toByte()
        }
    }
}
