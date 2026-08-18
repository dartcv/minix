package me.dartcv.minix.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import me.dartcv.minix.core.model.PermissionSnapshot

class PermissionCoordinator(private val context: Context) {
    fun snapshot(): PermissionSnapshot {
        val notificationRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val notificationGranted = !notificationRequired ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

        return PermissionSnapshot(
            canDrawOverlays = Settings.canDrawOverlays(context),
            notificationGranted = notificationGranted,
            notificationRequired = notificationRequired,
        )
    }

    fun overlaySettingsIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        "package:${context.packageName}".toUri(),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
