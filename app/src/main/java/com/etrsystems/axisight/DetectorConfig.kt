package com.etrsystems.axisight

data class DetectorConfig(
    var minAreaPx: Int = 200,
    var maxAreaPx: Int = 8000,
    var minCircularity: Double = 0.5,
    var kStd: Double = 1.0,
    var downscale: Int = 4
)