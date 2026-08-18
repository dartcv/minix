package me.dartcv.minix.control

/**
 * Static target list recovered from the reference APK's game selector.
 *
 * The order is intentional: it matches the original selector and is also the
 * probe priority used when automatically looking for a running game process.
 */
enum class ControlTargetChannel(
    val channelLabel: String,
    val packageName: String,
) {
    OFFICIAL("官方服", "com.minitech.miniworld"),
    VIVO("vivo服", "com.minitech.miniworld.vivo"),
    OPPO("oppo服", "com.minitech.miniworld.nearme.gamecenter"),
    XIAOMI("小米服", "com.minitech.miniworld.TMobile.mi"),
    M4399("4399服", "com.minitech.miniworld.m4399"),
    UC("九游服", "com.minitech.miniworld.uc"),
    TENCENT("应用宝服", "com.tencent.tmgp.minitech.miniworld"),
    // The recovered arrays intentionally pair these two labels with these package names.
    CHANNEL_233("233服", "com.minitech.miniworld.kuaishou"),
    KUAISHOU("快手服", "com.minitech.miniworld.meta"),
    ;

    val displayLabel: String
        get() = "$channelLabel · $packageName"
}

object ControlTargetCatalog {
    val entries: List<ControlTargetChannel> = ControlTargetChannel.entries.toList()

    val default: ControlTargetChannel
        get() = entries.first()

    fun fromPackageName(packageName: String): ControlTargetChannel? =
        entries.firstOrNull { it.packageName == packageName }
}
