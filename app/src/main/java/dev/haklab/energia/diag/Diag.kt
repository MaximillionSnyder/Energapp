package dev.haklab.energia.diag

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Process
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Telemetria local: no habla con ninguna red ni anade dependencias. Todo se
 * queda en el almacenamiento privado de la app y el usuario puede verlo y
 * compartirlo desde la pestana Registro.
 *
 * - Registro rotativo en `filesDir/diag/`: `diag.log` + `diag.1.log`
 *   (128 KB cada uno). Al desbordar, el actual pasa a `diag.1.log`.
 * - Las escrituras normales van a un hilo propio para no bloquear UI ni
 *   servicio; la de un crash es sincrona, porque despues el proceso muere.
 * - Instalando [installCrashHandler] se captura cualquier excepcion no
 *   controlada con su traza completa.
 * - La sesion de medicion se marca al arrancar y al parar: si el proceso
 *   muere sin parada ordenada (crash o kill del sistema), la siguiente
 *   inicializacion lo detecta y lo deja en [anomaliaPrevia] y en el registro.
 */
object Diag {

    private const val MAX_BYTES = 128 * 1024L
    private const val MAX_LINEAS_CRASH = 250
    private const val PREFS = "diag"
    private const val KEY_STARTED = "svc_started_ms"
    private const val KEY_CLEAN = "svc_clean"

    private val formato = SimpleDateFormat("yy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val cerrojo = Any()
    private val escritor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "diag-writer").apply { isDaemon = true }
    }

    private lateinit var archivo: File
    private lateinit var rodado: File
    private lateinit var prefs: SharedPreferences
    @Volatile private var listo = false

    private val _anomaliaPrevia = MutableStateFlow(false)

    /** true si la ultima sesion de medicion no termino de forma limpia. */
    val anomaliaPrevia: StateFlow<Boolean> = _anomaliaPrevia.asStateFlow()

    /** Prepara el registro. Idempotente; se llama desde Application.onCreate. */
    fun init(context: Context) {
        if (listo) return
        synchronized(cerrojo) {
            if (listo) return
            val dir = File(context.filesDir, "diag").apply { mkdirs() }
            archivo = File(dir, "diag.log")
            rodado = File(dir, "diag.1.log")
            prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            listo = true

            escribirAhora("I", "diag", "--- arranque de proceso · ${contextoDispositivo(context)} ---")
            val startedMs = prefs.getLong(KEY_STARTED, 0L)
            val limpia = prefs.getBoolean(KEY_CLEAN, true)
            if (startedMs > 0 && !limpia) {
                _anomaliaPrevia.value = true
                escribirAhora(
                    "W", "salud",
                    "la sesion anterior (desde ${formato.format(Date(startedMs))}) no se cerro " +
                        "de forma limpia: crash o proceso matado por el sistema",
                )
                prefs.edit().putBoolean(KEY_CLEAN, true).apply()
            }
        }
    }

    fun info(etiqueta: String, mensaje: String) = registro("I", etiqueta, mensaje)

    fun warn(etiqueta: String, mensaje: String) = registro("W", etiqueta, mensaje)

    fun error(etiqueta: String, mensaje: String, t: Throwable? = null) {
        val texto = if (t == null) mensaje else "$mensaje · ${t.javaClass.simpleName}: ${t.message}"
        registro("E", etiqueta, texto)
    }

    /** Captura cualquier excepcion no controlada y delega en el manejador previo. */
    fun installCrashHandler() {
        val previo = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { hilo, t ->
            crash(hilo.name, t)
            if (previo != null) {
                previo.uncaughtException(hilo, t)
            } else {
                Process.killProcess(Process.myPid())
            }
        }
    }

    private fun crash(hilo: String, t: Throwable) {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        val traza = sw.toString().lineSequence().take(MAX_LINEAS_CRASH).joinToString("\n")
        // Sincrono a proposito: el proceso puede morir justo despues.
        escribirAhora("C", "crash", "excepcion no controlada en el hilo '$hilo'\n$traza")
        if (listo) prefs.edit().putBoolean(KEY_CLEAN, false).apply()
    }

    /** Marca el inicio de una sesion de medicion (deja la marca de "sin cierre"). */
    fun sessionStarted() {
        if (listo) {
            prefs.edit()
                .putLong(KEY_STARTED, System.currentTimeMillis())
                .putBoolean(KEY_CLEAN, false)
                .apply()
        }
    }

    /** Marca la sesion como cerrada limpiamente (parada ordenada del servicio). */
    fun sessionStopped() {
        if (listo) prefs.edit().putBoolean(KEY_CLEAN, true).apply()
    }

    /** Devuelve el registro (rotado + actual), recortado a [maxCaracteres]. */
    fun read(maxCaracteres: Int = 64 * 1024): String {
        if (!listo) return ""
        val texto = synchronized(cerrojo) {
            buildString {
                if (rodado.exists()) append(rodado.readText())
                if (archivo.exists()) append(archivo.readText())
            }
        }
        return if (texto.length <= maxCaracteres) texto
        else "(...recorte de ${texto.length - maxCaracteres} caracteres...)\n" +
            texto.takeLast(maxCaracteres)
    }

    /** Borra el registro y las marcas de sesion. */
    fun clear() {
        if (!listo) return
        synchronized(cerrojo) {
            rodado.delete()
            archivo.delete()
            prefs.edit().putLong(KEY_STARTED, 0L).putBoolean(KEY_CLEAN, true).apply()
        }
        _anomaliaPrevia.value = false
        registro("I", "diag", "registro borrado por el usuario")
    }

    /** Silencia el aviso de sesion anomala sin borrar el registro. */
    fun descartarAnomalia() {
        _anomaliaPrevia.value = false
        if (listo) prefs.edit().putBoolean(KEY_CLEAN, true).apply()
    }

    private fun registro(nivel: String, etiqueta: String, mensaje: String) {
        if (!listo) return
        escritor.execute { escribirAhora(nivel, etiqueta, mensaje) }
    }

    private fun escribirAhora(nivel: String, etiqueta: String, mensaje: String) {
        try {
            synchronized(cerrojo) {
                val linea = "${formato.format(Date())} $nivel $etiqueta: $mensaje\n"
                if (archivo.length() + linea.toByteArray().size > MAX_BYTES) {
                    rodado.delete()
                    archivo.renameTo(rodado)
                }
                archivo.appendText(linea)
            }
            Log.println(
                if (nivel == "I") Log.INFO else Log.ERROR,
                "Energia-$etiqueta",
                mensaje,
            )
        } catch (_: Throwable) {
            // El diagnostico nunca debe tumbar a quien lo usa.
        }
    }

    private fun contextoDispositivo(context: Context): String {
        val version = runCatching {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            "${pi.versionName} (${pi.longVersionCode})"
        }.getOrDefault("?")
        return "Energia $version · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · " +
            "${Build.MANUFACTURER} ${Build.MODEL}"
    }
}
