package com.maxpi.openbikecompanion.model

data class PagePreset(
    val id: String,
    val name: String,
    val pages: List<PagePresetPage>,
    val createdAt: Long,
    val modifiedAt: Long
)

data class PagePresetPage(
    val number: Int,
    val metricCodes: List<Int>
)
