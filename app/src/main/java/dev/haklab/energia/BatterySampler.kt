package dev.haklab.energia

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import dev.haklab.energia.diag.Diag
import java.util.concurrent.ConcurrentHashMap

/**
 * Una lectura del medidor de bateria, ya normalizada.
 *
 * Convencion de signo: [dischargeMa] es SIEMPRE positiva mientras la bateria
 * se descarga. Android documenta que CURRENT_NOW es positivo cuando la
 * corriente ENTRA en la bateria (cargando), asi que para medir consumo hay que
 * invertir el signo; ademas algunos fuel gauges de OEM lo reportan al reves, y
 * eso se detecta en runtime (ver [negateCurrent]).
 */
data class BatterySample(
    val timestampMs: Long,
    /** V, derivado de EXTRA_VOLTAGE (mV). */
    val voltageV: Double,
    /** C, de EXTRA_TEMPERATURE (decimas de grado). */
    val temperatureC: Double,
    /** 0..100, resolucion entera. Solo para mostrar; NO para integrar. */
    val levelPct: Int,
    /** Estado del sistema: CHARGING / DISCHARGING / FULL / NOT_CHARGING / UNKNOWN. */
    val status: Int,
    /** 0 = a bateria; 1/2/4/8 = AC/USB/wireless/dock. */
    val plugged: Int,
    /** mA de descarga (positivo = consumo). NaN si no hay ninguna fuente. */
    val dischargeMa: Double,
    /** true si [dischargeMa] salio de CURRENT_AVERAGE (media del hardware). */
    val currentFromAverage: Boolean,
    /** true si no hay medidor de corriente y hubo que derivarla del nivel. */
    val currentDerived: Boolean,
    /** uAh del contador de carga; null si el dispositivo no lo soporta. */
    val chargeCounterUah: Int?,
    /**
     * true si la pantalla esta interactiva (encendida y sin dozing).
     * De [PowerManager.isInteractive], que no requiere permisos (API 20+).
     * Es la clave con la que se separa el gasto de pantalla encendida del de
     * apagada, que es la comparacion mas util para saber que consume "sin que
     * yo lo use".
     */
    val screenOn: Boolean = false,
) {
    val isCharging: Boolean
        get() = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL ||
            plugged != 0

    val powerMw: Double
        get() = if (dischargeMa.isNaN()) Double.NaN else dischargeMa / 1000.0 * voltageV * 1000.0
}

/**
 * Lee el medidor de bateria del sistema.
 *
 * No requiere permisos: las propiedades 1..6 de BatteryManager son publicas y
 * el servidor solo exige BATTERY_STATS para las propiedades 7..12 (ocultas).
 * Verificado en AOSP BatteryService.BatteryPropertiesRegistrar.getProperty().
 *
 * NO usa /sys/class/power_supply: en Android 8+ (full Treble) la sepolicy
 * AOSP prohibe a TODO coredomain (incluidas las apps) leer sysfs_batteryinfo:
 *
 *   neverallow { coredomain -shell -apexd -init -ueventd -recovery -charger -incidentd }
 *       sysfs_batteryinfo:file { open read };
 */
class BatterySampler(private val context: Context) {

    private val bm: BatteryManager? =
        context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

    private val power: PowerManager? =
        context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    /*
     * El receptor actualiza estos campos en el hilo principal y el bucle de
     * muestreo los lee desde un hilo de fondo: deben ser volatiles para que la
     * lectura vea el ultimo valor.
     */
    @Volatile private var cachedLevelPct: Int = -1
    @Volatile private var cachedStatus: Int = BatteryManager.BATTERY_STATUS_UNKNOWN
    @Volatile private var cachedPlugged: Int = 0
    @Volatile private var cachedVoltageMv: Int = -1
    @Volatile private var cachedTempTenthsC: Int = Int.MIN_VALUE

    /**
     * true si el fuel gauge reporta la corriente con el signo invertido
     * respecto a la documentacion. Se decide con evidencia fisica: si el
     * dispositivo dice estar descargando pero la corriente "entrante" es
     * positiva, el gauge esta invertido.
     */
    private var negateCurrent: Boolean? = null

    /** Avisos que solo deben registrarse una vez por proceso. */
    private var warnedNoCurrent = false
    private var warnedNoVoltage = false
    private var warnedNoSticky = false
    private val propsFallidas: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            val i = intent ?: return
            cachedLevelPct = i.getIntExtra(BatteryManager.EXTRA_LEVEL, cachedLevelPct)
            cachedStatus = i.getIntExtra(BatteryManager.EXTRA_STATUS, cachedStatus)
            cachedPlugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, cachedPlugged)
            cachedVoltageMv = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, cachedVoltageMv)
            cachedTempTenthsC =
                i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, cachedTempTenthsC)
        }
    }

    fun start() {
        // ACTION_BATTERY_CHANGED es sticky y SOLO se entrega a receptores
        // registrados en runtime (FLAG_RECEIVER_REGISTERED_ONLY). Registrarlo
        // en el manifiesto no recibe nada.
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        // Lectura inicial sincrona, sin esperar al primer broadcast.
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (sticky == null && !warnedNoSticky) {
            warnedNoSticky = true
            Diag.warn("lectura", "ACTION_BATTERY_CHANGED no llego como sticky: faltan nivel/voltaje hasta el primer broadcast")
        }
        receiver.onReceive(context, sticky)
        Diag.info(
            "lectura",
            "capacidades del dispositivo: corriente por hardware=" +
                (if (hasHardwareCurrent()) "si" else "no") +
                ", contador de carga=" + (if (chargeCounterUah() != null) "si" else "no") +
                ", voltaje=" + (if (cachedVoltageMv > 0) "si" else "no"),
        )
    }

    fun stop() {
        runCatching { context.unregisterReceiver(receiver) }
    }

    private fun intProp(property: Int): Int? {
        val v = try {
            bm?.getIntProperty(property) ?: return null
        } catch (t: Throwable) {
            // Algun OEM lanza SecurityException o IllegalStateException en
            // propiedades que en AOSP son publicas.
            if (propsFallidas.add(property)) {
                Diag.error("lectura", "getIntProperty($property) fallo", t)
            }
            return null
        }
        // El javadoc es explicito: una propiedad no soportada devuelve
        // Integer.MIN_VALUE cuando targetSdk >= P (nosotros 34), y 0 si es
        // menor. Descartamos ambos casos.
        if (v == Int.MIN_VALUE || v == 0) return null
        return v
    }

    /** uAh restantes segun el coulomb counter, o null si no lo soporta. */
    fun chargeCounterUah(): Int? = intProp(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)

    /** true si el hardware expone una corriente real (no derivada del nivel). */
    fun hasHardwareCurrent(): Boolean =
        intProp(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE) != null ||
            intProp(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) != null

    fun sample(nowMs: Long, derived: DerivedCurrent? = null): BatterySample {
        val status = cachedStatus
        val plugged = cachedPlugged
        val discharging = status == BatteryManager.BATTERY_STATUS_DISCHARGING ||
            status == BatteryManager.BATTERY_STATUS_NOT_CHARGING

        val avgUa = intProp(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
        val nowUa = intProp(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val fromAverage = avgUa != null
        val rawUa = avgUa ?: nowUa

        var dischargeMa = Double.NaN
        var isDerived = false
        if (rawUa != null) {
            // Decidir el signo una sola vez, con evidencia fisica. Sin
            // evidencia (estado aun desconocido) se deja sin decidir, para no
            // fijar un signo equivocado para toda la sesion.
            if (negateCurrent == null) {
                negateCurrent = when {
                    discharging && rawUa > 0 -> true   // dice descargar y "entra" corriente
                    discharging && rawUa < 0 -> false  // coincide con el javadoc
                    else -> null                       // sin evidencia aun
                }
                if (negateCurrent != null) {
                    Diag.info(
                        "lectura",
                        if (negateCurrent == true) "fuel gauge con signo invertido: se corrige"
                        else "signo del fuel gauge coherente con el javadoc",
                    )
                }
            }
            val ua = if (negateCurrent == true) -rawUa else rawUa
            dischargeMa = -ua / 1000.0 // javadoc: positivo = entrando
        } else if (derived != null && derived.valid) {
            dischargeMa = derived.ma
            isDerived = true
        } else if (!warnedNoCurrent) {
            warnedNoCurrent = true
            Diag.warn("lectura", "sin corriente por hardware ni derivada: el tramo se descartara")
        }

        // isInteractive() no requiere permisos y refleja el estado real del
        // display en este instante (mejor que inferirlo de SCREEN_ON/OFF, que
        // son eventos y no estado).
        val screenOn = power?.isInteractive ?: true

        val voltageV = if (cachedVoltageMv > 0) cachedVoltageMv / 1000.0 else Double.NaN
        if (voltageV.isNaN() && !warnedNoVoltage) {
            warnedNoVoltage = true
            Diag.warn("lectura", "sin voltaje (EXTRA_VOLTAGE): no se puede calcular potencia")
        }
        val tempC = if (cachedTempTenthsC != Int.MIN_VALUE) cachedTempTenthsC / 10.0 else Double.NaN

        return BatterySample(
            timestampMs = nowMs,
            voltageV = voltageV,
            temperatureC = tempC,
            levelPct = cachedLevelPct.coerceAtLeast(0),
            status = status,
            plugged = plugged,
            dischargeMa = dischargeMa,
            currentFromAverage = fromAverage,
            currentDerived = isDerived,
            chargeCounterUah = chargeCounterUah(),
            screenOn = screenOn,
        )
    }

    /**
     * Corriente derivada del nivel de bateria, para dispositivos sin medidor.
     * El nivel tiene resolucion de 1 % (escalon de ~51 mAh en 5100 mAh), asi
     * que solo sirve promediando una ventana larga.
     */
    data class DerivedCurrent(val ma: Double, val valid: Boolean)
}
