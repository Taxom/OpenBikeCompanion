package com.maxpi.openbikecompanion.protocol

import com.maxpi.openbikecompanion.model.C406RiderProfile

object C406ProfileCodec {

    val READ_COMMAND: ByteArray
        get() = byteArrayOf(0x40, 0x40)

    fun decodeReadResponse(value: ByteArray): C406RiderProfile? {
        if (value.size != 14) return null

        if (
            (value[0].toInt() and 0xFF) != 0x40 ||
            (value[1].toInt() and 0xFF) != 0x40 ||
            (value[2].toInt() and 0xFF) != 0x00
        ) {
            return null
        }

        return C406RiderProfile(
            genderRaw = u8(value, 3),
            age = u8(value, 4),
            heightCm = u8(value, 5),
            maxHeartRateBpm = u8(value, 6),
            lthrBpm = u8(value, 7),
            ftpWatts = u16Le(value, 8),
            vehicleWeightHundredthsKg = u16Le(value, 10),
            riderWeightHundredthsKg = u16Le(value, 12)
        )
    }

    fun buildWritePacket(profile: C406RiderProfile): ByteArray? {
        if (
            profile.genderRaw !in 0..0xFF ||
            profile.age !in 0..0xFF ||
            profile.heightCm !in 0..0xFF ||
            profile.maxHeartRateBpm !in 0..0xFF ||
            profile.lthrBpm !in 0..0xFF ||
            profile.ftpWatts !in 0..0xFFFF ||
            profile.vehicleWeightHundredthsKg !in 0..0xFFFF ||
            profile.riderWeightHundredthsKg !in 0..0xFFFF
        ) {
            return null
        }

        return ByteArray(13).also { packet ->
            packet[0] = 0x40
            packet[1] = 0x41
            packet[2] = profile.genderRaw.toByte()
            packet[3] = profile.age.toByte()
            packet[4] = profile.heightCm.toByte()
            packet[5] = profile.maxHeartRateBpm.toByte()
            packet[6] = profile.lthrBpm.toByte()
            putU16Le(packet, 7, profile.ftpWatts)
            putU16Le(packet, 9, profile.vehicleWeightHundredthsKg)
            putU16Le(packet, 11, profile.riderWeightHundredthsKg)
        }
    }

    private fun u8(value: ByteArray, offset: Int): Int {
        return value[offset].toInt() and 0xFF
    }

    private fun u16Le(value: ByteArray, offset: Int): Int {
        return u8(value, offset) or (u8(value, offset + 1) shl 8)
    }

    private fun putU16Le(
        target: ByteArray,
        offset: Int,
        value: Int
    ) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }
}
