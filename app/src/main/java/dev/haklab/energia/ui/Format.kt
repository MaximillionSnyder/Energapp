package dev.haklab.energia.ui

import java.util.Locale
import kotlin.math.abs

/** Formateo de magnitudes para la UI, en un solo sitio. */
object Format {

    fun mw(v: Double): String = when {
        v.isNaN() -> "—"
        abs(v) >= 1000.0 -> String.format(Locale.getDefault(), "%.2f W", v / 1000.0)
        else -> String.format(Locale.getDefault(), "%.0f mW", v)
    }

    fun joules(v: Double): String = when {
        v >= 1000.0 -> String.format(Locale.getDefault(), "%.2f kJ", v / 1000.0)
        else -> String.format(Locale.getDefault(), "%.1f J", v)
    }

    fun mah(v: Double): String = String.format(Locale.getDefault(), "%.1f mAh", v)

    fun segundos(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 60 -> "$s s"
            s < 3600 -> "${s / 60} min ${s % 60} s"
            else -> "${s / 3600} h ${(s % 3600) / 60} min"
        }
    }

    fun celsius(v: Double): String =
        if (v.isNaN()) "—" else String.format(Locale.getDefault(), "%.1f °C", v)

    fun voltios(v: Double): String =
        if (v.isNaN()) "—" else String.format(Locale.getDefault(), "%.3f V", v)

    fun porcentaje(v: Double): String = String.format(Locale.getDefault(), "%.3f %%", v)
}
