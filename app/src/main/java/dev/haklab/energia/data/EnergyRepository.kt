package dev.haklab.energia.data

import dev.haklab.energia.BatterySample
import dev.haklab.energia.EnergyModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Estado completo de la sesion de medicion, tal como lo consume la UI.
 *
 * Es un unico objeto inmutable: la UI lo observa como StateFlow y Compose
 * recompone solo lo que cambio. Sustituye al estado global disperso que tenia
 * la version anterior.
 */
data class EnergyUiState(
    val running: Boolean = false,
    val intervalMs: Long = 5_000L,
    val sample: BatterySample? = null,
    val energyJ: Double = 0.0,
    val chargeMah: Double = 0.0,
    val validMs: Long = 0,
    val discardedMs: Long = 0,
    val sampleCount: Int = 0,
    val hasGaps: Boolean = false,
    val avgMw: Double = Double.NaN,
    val percentOfBattery: Double = 0.0,
    val fullCapacityMah: Double? = null,
    val hasHardwareCurrent: Boolean = false,
    val screen: EnergyModel.ScreenLedger = EnergyModel.ScreenLedger(
        on = EnergyModel.ScreenBucket(0.0, 0L),
        off = EnergyModel.ScreenBucket(0.0, 0L),
    ),
    /** Serie temporal para el grafico: (segundos desde el inicio, mW, pantalla encendida). */
    val series: List<PowerPoint> = emptyList(),
    val sessionStartMs: Long = System.currentTimeMillis(),
)

/** Un punto del grafico de potencia. */
data class PowerPoint(val tSeconds: Float, val milliwatts: Float, val screenOn: Boolean)

/**
 * Fuente unica de verdad de la sesion.
 *
 * El servicio de monitoreo escribe aqui; la UI solo lee. Al ser un object, el
 * proceso la comparte entre el servicio y las actividades, que es justo lo que
 * se necesita para que la pantalla siga mostrando datos aunque se cree de nuevo.
 */
object EnergyRepository {

    private const val MAX_SERIES_POINTS = 600

    private val _state = MutableStateFlow(EnergyUiState())
    val state: StateFlow<EnergyUiState> = _state.asStateFlow()

    /**
     * Pares (timestampMs, energiaJ del tramo) para atribuir por app.
     * Lo escribe el bucle del servicio (hilo de fondo) y lo lee la UI: volatile.
     */
    @Volatile
    private var marks: List<Pair<Long, Double>> = emptyList()

    fun marksSnapshot(): List<Pair<Long, Double>> = marks

    fun reset(startedAtMs: Long) {
        marks = emptyList()
        _state.update {
            it.copy(
                energyJ = 0.0,
                chargeMah = 0.0,
                validMs = 0,
                discardedMs = 0,
                sampleCount = 0,
                hasGaps = false,
                avgMw = Double.NaN,
                percentOfBattery = 0.0,
                series = emptyList(),
                sessionStartMs = startedAtMs,
            )
        }
    }

    /** Publica el resultado de un tick del servicio. */
    fun publish(
        sample: BatterySample,
        energyJ: Double,
        chargeMah: Double,
        validMs: Long,
        discardedMs: Long,
        sampleCount: Int,
        hasGaps: Boolean,
        avgMw: Double,
        percentOfBattery: Double,
        fullCapacityMah: Double?,
        hasHardwareCurrent: Boolean,
        screen: EnergyModel.ScreenLedger,
        deltaJ: Double,
    ) {
        if (deltaJ > 0.0) {
            marks = (marks + (sample.timestampMs to deltaJ)).takeLast(20_000)
        }
        _state.update { prev ->
            val t0 = prev.sessionStartMs
            val punto = PowerPoint(
                tSeconds = ((sample.timestampMs - t0) / 1000.0).toFloat(),
                milliwatts = sample.powerMw.toFloat(),
                screenOn = sample.screenOn,
            )
            val serie = if (deltaJ > 0.0 || prev.series.isEmpty()) {
                (prev.series + punto).takeLast(MAX_SERIES_POINTS)
            } else {
                prev.series
            }
            prev.copy(
                sample = sample,
                energyJ = energyJ,
                chargeMah = chargeMah,
                validMs = validMs,
                discardedMs = discardedMs,
                sampleCount = sampleCount,
                hasGaps = hasGaps,
                avgMw = avgMw,
                percentOfBattery = percentOfBattery,
                fullCapacityMah = fullCapacityMah,
                hasHardwareCurrent = hasHardwareCurrent,
                screen = screen,
                series = serie,
            )
        }
    }

    fun setRunning(running: Boolean, intervalMs: Long = _state.value.intervalMs) {
        _state.update { it.copy(running = running, intervalMs = intervalMs) }
    }
}
