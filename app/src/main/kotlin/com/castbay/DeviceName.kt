package com.castbay

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings

/**
 * The name senders see: what the user typed in settings, else the TV's own device name
 * ("OnePlus TV"), read fresh each time so renaming the TV in its settings carries over.
 * The old fixed default counts as unset.
 */
fun SharedPreferences.serverName(ctx: Context): String =
    getString(Prefs.SERVER_NAME, null)
        ?.takeIf { it.isNotBlank() && it != Prefs.DEF_SERVER_NAME }
        ?: ctx.deviceName()

fun Context.deviceName(): String {
    val cr = contentResolver
    return listOfNotNull(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) Settings.Global.getString(cr, Settings.Global.DEVICE_NAME) else null,
        Settings.Secure.getString(cr, "bluetooth_name"),
    ).firstOrNull { it.isNotBlank() }
        ?: "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}".trim().ifEmpty { Prefs.DEF_SERVER_NAME }
}
