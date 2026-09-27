package dev.haklab.energia

import android.content.Context
import android.content.pm.PackageManager

/** Utilidades de presentacion de nombres de paquete. */
object AppLabels {
    fun label(context: Context, pkg: String): String {
        if (pkg == UsageAttribution.UNATTRIBUTED) {
            return context.getString(R.string.unattributed)
        }
        val pm: PackageManager = context.packageManager
        return runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }
}
