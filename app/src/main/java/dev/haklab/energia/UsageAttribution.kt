package dev.haklab.energia

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings

/**
 * Atribucion de consumo a la app en primer plano.
 *
 * LIMITE HONESTO: sin root ni ser app de sistema NO existe API para saber
 * cuanto consume cada app. `dumpsys batterystats` requiere el permiso DUMP, y
 * desde Android 12 las estadisticas por app estan reservadas. Lo que si se
 * puede es medir el consumo TOTAL real del hardware (BatteryManager) y
 * repartirlo por el tiempo que cada app estuvo en primer plano. El resultado
 * es "energia gastada mientras X estaba delante", que es medible y verificable,
 * en lugar del reparto estimado que muestra el sistema.
 *
 * Fuente de datos: UsageStatsManager.queryEvents + ACTIVITY_RESUMED, que exige
 * el app-op PACKAGE_USAGE_STATS (el usuario lo concede en Ajustes; declararlo
 * en el manifiesto NO basta).
 */
class UsageAttribution(private val context: Context) {

    data class Segment(val packageName: String, val startMs: Long, val endMs: Long) {
        val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0)
    }

    data class AppUsage(
        val packageName: String,
        val label: String,
        val foregroundMs: Long,
        val energyJ: Double,
        val chargeMah: Double,
        val avgMw: Double,
        val shareOfSession: Double,
    )

    private val usm: UsageStatsManager? =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager

    /** true si el usuario ya concedio "acceso de uso". */
    fun hasPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        val mode = if (android.os.Build.VERSION.SDK_INT >= 29) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Intent para que el usuario conceda el acceso (Ajustes > Acceso de uso). */
    fun settingsIntent(): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Segmentos de primer plano. `outputs` es la lista de eventos que devuelve
     * queryEvents; por eso guardamos el paquete en el evento y no el evento.
     */
    fun segments(fromMs: Long, toMs: Long): List<Segment> {
        val out = mutableListOf<Segment>()
        if (!hasPermission()) return out
        val mgr = usm ?: return out

        // El evento ACTIVITY_RESUMED marca el inicio; ACTIVITY_PAUSED/STOPPED el
        // fin. Guardamos solo el paquete, que es lo unico que necesitamos.
        var openPkg: String? = null
        var openAt: Long = 0
        val events: UsageEvents = mgr.queryEvents(fromMs, toMs)
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    openPkg?.let { prev ->
                        if (e.timeStamp > openAt) out += Segment(prev, openAt, e.timeStamp)
                    }
                    openPkg = e.packageName
                    openAt = e.timeStamp
                }
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED -> {
                    if (openPkg != null && e.packageName == openPkg) {
                        out += Segment(openPkg!!, openAt, e.timeStamp)
                        openPkg = null
                    }
                }
            }
        }
        openPkg?.let { if (toMs > openAt) out += Segment(it, openAt, toMs) }
        return out
    }

    /**
     * Reparte los incrementos de energia medidos entre las apps en primer plano.
     *
     * @param marks pares (timestampMs, energiaJ del tramo que termina ahi)
     */
    fun attribute(marks: List<Pair<Long, Double>>, fromMs: Long, toMs: Long): List<AppUsage> {
        if (marks.isEmpty()) return emptyList()
        val segs = segments(fromMs, toMs)
        val energyByPkg = HashMap<String, Double>()
        val fgMsByPkg = HashMap<String, Long>()
        var total = 0.0

        for ((ts, dE) in marks) {
            if (dE <= 0.0) continue
            total += dE
            // El tramo (t_prev, t] pertenece a la app que estaba delante en t.
            val pkg = segs.firstOrNull { ts > it.startMs && ts <= it.endMs }?.packageName
                ?: UNATTRIBUTED
            energyByPkg[pkg] = (energyByPkg[pkg] ?: 0.0) + dE
        }
        for (s in segs) {
            fgMsByPkg[s.packageName] = (fgMsByPkg[s.packageName] ?: 0L) + s.durationMs
        }

        return energyByPkg.map { (pkg, e) ->
            val label = AppLabels.label(context, pkg)
            val fg = fgMsByPkg[pkg] ?: 0L
            AppUsage(
                packageName = pkg,
                label = label,
                foregroundMs = fg,
                energyJ = e,
                chargeMah = 0.0,
                avgMw = if (fg > 0) e / (fg / 1000.0) * 1000.0 else Double.NaN,
                shareOfSession = if (total > 0) e / total * 100.0 else 0.0,
            )
        }.sortedByDescending { it.energyJ }
    }

    companion object {
        const val UNATTRIBUTED = "__sin_atribuir__"
    }
}
