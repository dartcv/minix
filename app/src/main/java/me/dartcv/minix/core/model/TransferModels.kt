package me.dartcv.minix.core.model

data class LocalDataSnapshot(
    val settings: AppSettings,
    val presets: List<Preset>,
    val mapPoints: List<MapPoint>,
)
