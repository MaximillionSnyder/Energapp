package dev.haklab.energia

import kotlin.math.abs
import kotlin.math.max

/**
 * Integrador de energia. Reproduce exactamente la matematica validada en
 * verification/verify_energy_math.py:
 *
 *   E[J] += ((P(t0) + P(t1)) / 2) * dt      regla del trapecio
 *   P[mW] = I[mA] * V[V]
 *   Q[mAh] = integral de I dt
 *
 * Por que trapezoidal y no sumar "muestras x intervalo": el intervalo real
 * entre lecturas nunca es constante (Doze, GC, reprogramaciones), y el
 * trapecio pondera el intervalo verdadero de cada tramo.
 *
 * Tramo VALIDO = sin carga en los extremos, dt > 0 y dt <= MAX_GAP_MS.
 * Un hueco mayor (telefono apagado, servicio matado por el sistema) se
 * descarta en vez de extrapolarse: extrapolar inventaria consumo.
 */
class EnergyModel(
    /** Capacidad de referencia en mAh, solo para expresar el gasto en % de bateria. */
    val referenceCapacityMah: Double = 5100.0,
) {
    var energyJ: Double = 0.0
        private set
    var chargeMah: Double = 0.0
        private set
    var validMillis: Long = 0
        private set
    var discardedMillis: Long = 0
        private set
    var sampleCount: Int = 0
        private set
    var firstTs: Long = 0
        private set
    var lastTs: Long = 0
        private set
    /** true si en algun tramo hubo que descartar por hueco. */
    var hasGaps: Boolean = false
        private set

    private var last: BatterySample? = null

    /**
     * Reparto de la energia medida entre pantalla encendida y apagada.
     *
     * Un tramo se asigna al estado del EXTREMO INICIAL (`prev.screenOn`),
     * porque en la regla del trapecio ese extremo es el que domina el
     * intervalo: la muestra final solo aporta su peso en el tramo siguiente.
     * Es la misma convencion que usa la atribucion por app.
     */
    private val screenOnJ = HashMap<Boolean, Double>()
    private val screenOnMs = HashMap<Boolean, Long>()

    /**
     * true cuando el dispositivo empezo a cargar durante la sesion: lo
     * acumulado antes ya no es comparable (el contador de carga y el nivel
     * cambian de sentido). El servicio lo consulta y reinicia la sesion.
     */
    var pendingReset: Boolean = false
        private set

    fun reset() {
        pendingReset = false
        lastDeltaJ = 0.0
        energyJ = 0.0; chargeMah = 0.0; validMillis = 0; discardedMillis = 0
        sampleCount = 0; firstTs = 0; lastTs = 0; hasGaps = false; last = null
        screenOnJ.clear(); screenOnMs.clear()
    }

    /** Gasto y tiempo de un estado de pantalla. */
    data class ScreenBucket(val energyJ: Double, val millis: Long) {
        val avgMw: Double
            get() = if (millis <= 0) Double.NaN else energyJ / (millis / 1000.0) * 1000.0
    }

    /** Reparto pantalla encendida / apagada de la sesion. */
    data class ScreenLedger(val on: ScreenBucket, val off: ScreenBucket)

    fun screenLedger(): ScreenLedger = ScreenLedger(
        on = ScreenBucket(screenOnJ[true] ?: 0.0, screenOnMs[true] ?: 0L),
        off = ScreenBucket(screenOnJ[false] ?: 0.0, screenOnMs[false] ?: 0L),
    )

    /** Energia del ultimo tramo aceptado, para atribuirla a quien corresponda. */
    var lastDeltaJ: Double = 0.0
        private set

    /** Motivo del ultimo tramo descartado, para el diagnostico. */
    var lastDiscardReason: String? = null
        private set

    /** Potencia instantanea en mW (NaN si no hay corriente medida). */
    fun instantaneousMw(): Double {
        val s = last ?: return Double.NaN
        val p = s.powerMw
        return if (p.isNaN() || s.isCharging) Double.NaN else max(0.0, p)
    }

    /**
     * Anade una muestra. Devuelve la energia acumulada por esa muestra en J
     * (0.0 si el tramo se descarto), de modo que quien llama pueda atribuir el
     * incremento a la app en primer plano de ese instante.
     */
    fun add(s: BatterySample): Double {
        sampleCount++
        if (firstTs == 0L) firstTs = s.timestampMs
        lastTs = s.timestampMs

        val prev = last
        last = s
        lastDeltaJ = 0.0
        if (prev == null) {
            // Primera muestra: si ya viene cargando, la sesion anterior no es
            // comparable (niveles y contador cambiaron). Se empieza de cero.
            if (s.isCharging) reset()
            return 0.0
        }

        val dtMs = s.timestampMs - prev.timestampMs
        if (dtMs <= 0) return 0.0
        if (dtMs > MAX_GAP_MS) {
            hasGaps = true
            discardedMillis += dtMs
            lastDiscardReason = "hueco de ${dtMs / 1000} s (maximo ${MAX_GAP_MS / 1000} s)"
            return 0.0
        }
        if (prev.isCharging || s.isCharging) {
            discardedMillis += dtMs
            lastDiscardReason = "carga en el tramo"
            // Al empezar a cargar la acumulacion previa deja de ser comparable.
            if (s.isCharging) pendingReset = true
            return 0.0
        }

        val p0 = prev.powerMw
        val p1 = s.powerMw
        if (p0.isNaN() || p1.isNaN()) {
            // Sin corriente no hay energia; el tiempo tampoco cuenta como valido.
            discardedMillis += dtMs
            lastDiscardReason = "sin corriente medida"
            return 0.0
        }

        // Un contador de carga que salta indica recalibracion del gauge: ese
        // tramo no es comparable con los demas.
        val c0 = prev.chargeCounterUah
        val c1 = s.chargeCounterUah
        if (c0 != null && c1 != null && abs(c1 - c0) > COUNTER_LEAP_UAH) {
            hasGaps = true
            discardedMillis += dtMs
            lastDiscardReason = "salto del contador de carga (${abs(c1 - c0)} uAh)"
            return 0.0
        }

        val dtS = dtMs / 1000.0
        // mW * s = mJ ; el trapecio pondera el intervalo real de cada tramo.
        // Se acota a >= 0: un valor negativo aqui seria ruido del sensor de
        // corriente, no una bateria que se recarga midiendo descarga.
        val dmj = ((max(0.0, p0) + max(0.0, p1)) / 2.0) * dtS / 1000.0
        val dJ = dmj / 1000.0
        energyJ += dJ
        chargeMah += ((prev.dischargeMa + s.dischargeMa) / 2.0) * dtS / 3600.0
        validMillis += dtMs
        screenOnJ[prev.screenOn] = (screenOnJ[prev.screenOn] ?: 0.0) + dJ
        screenOnMs[prev.screenOn] = (screenOnMs[prev.screenOn] ?: 0L) + dtMs
        lastDeltaJ = dJ
        return dJ
    }

    fun elapsedMillis(): Long = if (lastTs > 0 && firstTs > 0) lastTs - firstTs else 0

    /** Potencia media medida en la sesion, en mW. */
    fun averageMw(): Double =
        if (validMillis <= 0) Double.NaN else energyJ / (validMillis / 1000.0) * 1000.0

    /** Gasto expresado como porcentaje de la bateria (referencia nominal). */
    fun percentOfBattery(): Double =
        energyJ / (referenceCapacityMah * NOMINAL_VOLTAGE * 3600.0 / 1000.0) * 100.0

    /**
     * Capacidad real a plena carga, estimada del coulomb counter:
     *   full_uAh = contador_uAh / (nivel / 100)
     * Es informativo y util para detectar baterias degradadas.
     */
    fun estimatedFullCapacityMah(sample: BatterySample): Double? {
        val cc = sample.chargeCounterUah ?: return null
        if (sample.levelPct <= 0 || sample.levelPct > 100) return null
        return cc / 1_000_000.0 * 100.0 / sample.levelPct * 1000.0
    }

    companion object {
        /** Hueco maximo admisible entre muestras (2 min). */
        const val MAX_GAP_MS = 120_000L
        /** Salto del contador que consideramos recalibracion, no consumo. */
        const val COUNTER_LEAP_UAH = 200_000
        /** Tension nominal de referencia para expresar % de bateria. */
        const val NOMINAL_VOLTAGE = 3.87
    }
}
