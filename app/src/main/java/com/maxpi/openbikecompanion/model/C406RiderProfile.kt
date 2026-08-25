package com.maxpi.openbikecompanion.model

data class C406RiderProfile(
    val genderRaw: Int,
    val age: Int,
    val heightCm: Int,
    val maxHeartRateBpm: Int,
    val lthrBpm: Int,
    val ftpWatts: Int,
    val vehicleWeightHundredthsKg: Int,
    val riderWeightHundredthsKg: Int
) {
    val vehicleWeightKg: Double
        get() = vehicleWeightHundredthsKg / 100.0

    val riderWeightKg: Double
        get() = riderWeightHundredthsKg / 100.0
}
