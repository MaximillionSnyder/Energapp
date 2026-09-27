package dev.haklab.energia.diag

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Debug
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
 * - Una **sesion por proceso**: `filesDir/diag/sesion-<ms>.log` (+ su rotado
 *   `sesion-<ms>.1.log`). Al desbordar, el actual pasa al rotado.
 * - El Registro lista las sesiones y abre solo la elegida; asi el diario deja
 *   de ser un unico texto de cientos de lineas. Se conservan las ultimas
 *   [MAX_SESIONES] o [MAX_BYTES_TOTAL] bytes, lo que se cumpla primero.
 * - Los ficheros `diag.log` / `diag.1.log` de versiones anteriores se
 *   conservan como "historico" y siguen visibles, pero no se escriben.
 * - Las escrituras normales van a un hilo propio para no bloquear UI ni
 *   servicio; la de un crash es sincrona, porque despues el proceso muere.
 * - Instalando [installCrashHandler] se captura cualquier excepcion no
 *   controlada con su traza completa.
 * - La sesion de medicion se marca al arrancar y al parar: si el proceso
 *   muere sin parada ordenada (crash o kill del sistema), la siguiente
 *   inicializacion lo detecta y lo deja en [anomaliaPrevia] y en el registro.
 */
object Diag {

    private const val MAX_BYTES_SESION = 96 * 1024L
    private const val MAX_SESIONES = 12
    private const val MAX_BYTES_TOTAL = 1024 * 1024L
    private const val MAX_LINEAS_CRASH = 250
    private const val MAX_TRAZA_SALIDA = 16 * 1024
    private const val PREFIJO_SESION = "sesion-"
    private const val SUFIJO_ROTADO = ".1.log"
    private const val MARCA_CIERRE = "sesion de medicion cerrada"
    private const val PREFS = "diag"
    private const val KEY_STARTED = "svc_started_ms"
    private const val KEY_CLEAN = "svc_clean"
    private const val KEY_ULTIMA_SALIDA = "ultima_salida_ms"

    /** Resumen de una sesion, para listarla y abrirla desde el Registro. */
    data class Sesion(
        val inicioMs: Long,
        val finMs: Long,
        val lineas: Int,
        val avisos: Int,
        val errores: Int,
        val enCurso: Boolean,
        val sinCerrar: Boolean,
    )

    private val formato = SimpleDateFormat("yy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val niveles = setOf("I", "W", "E", "C")
    private val cerrojo = Any()
    private val escritor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "diag-writer").apply { isDaemon = true }
    }

    private lateinit var dir: File
    private lateinit var archivo: File
    private lateinit var rodado: File
    private lateinit var historico: File
    private lateinit var historicoRodado: File
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
            dir = File(context.filesDir, "diag").apply { mkdirs() }
            historico = File(dir, "diag.log")
            historicoRodado = File(dir, "diag.1.log")
            val ahora = System.currentTimeMillis()
            archivo = File(dir, "$PREFIJO_SESION$ahora.log")
            rodado = File(dir, "$PREFIJO_SESION$ahora$SUFIJO_ROTADO")
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
            registrarSalidasDelProceso(context)
            runCatching { purgarSesiones() }
        }
        memoria("arranque de proceso")
    }

    /**
     * Registra por que termino el proceso anterior segun el propio sistema.
     *
     * [ActivityManager.getHistoricalProcessExitReasons] es accesible para la
     * propia app sin permisos (API 30+) e incluye la traza que guarda el
     * sistema para crashes y ANR. Es la unica forma de ver un crash nativo, un
     * ANR o un kill del sistema que no pasa por [installCrashHandler].
     */
    private fun registrarSalidasDelProceso(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val gestor =
            context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val ultima = prefs.getLong(KEY_ULTIMA_SALIDA, 0L)
        val salidas = try {
            gestor.getHistoricalProcessExitReasons(context.packageName, 0, 8)
        } catch (t: Throwable) {
            return
        }
        var masReciente = ultima
        // Llegan de la mas reciente a la mas antigua; se registran en orden.
        for (info in salidas.asReversed()) {
            val ts = info.timestamp
            if (ts <= ultima) continue
            if (ts > masReciente) masReciente = ts
            val traza = try {
                info.traceInputStream?.use { entrada ->
                    entrada.readBytes().toString(Charsets.UTF_8)
                }
            } catch (_: Throwable) {
                null
            }?.trim()?.take(MAX_TRAZA_SALIDA)
            escribirAhora(
                "W", "salida",
                "el sistema cerro el proceso anterior: ${razon(info.reason)}" +
                    (info.description?.let { " · $it" } ?: "") +
                    " · ${formato.format(Date(ts))}" +
                    (if (!traza.isNullOrEmpty()) "\n$traza" else ""),
            )
        }
        if (masReciente > ultima) prefs.edit().putLong(KEY_ULTIMA_SALIDA, masReciente).apply()
    }

    private fun razon(codigo: Int): String = when (codigo) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "salida propia"
        ApplicationExitInfo.REASON_SIGNALED -> "matado por senal (SIGKILL/SIGSEGV)"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "matado por memoria baja (LMK)"
        ApplicationExitInfo.REASON_CRASH -> "crash de la app (excepcion)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash nativo"
        ApplicationExitInfo.REASON_ANR -> "ANR: la app dejo de responder"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "fallo de inicializacion"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "cambio de permisos"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "uso excesivo de recursos"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "cerrado por el usuario"
        ApplicationExitInfo.REASON_USER_STOPPED -> "detenido por el usuario"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependencia caida"
        ApplicationExitInfo.REASON_OTHER -> "otro (kill del sistema)"
        else -> "codigo $codigo"
    }

    fun info(etiqueta: String, mensaje: String) = registro("I", etiqueta, mensaje)

    fun warn(etiqueta: String, mensaje: String) = registro("W", etiqueta, mensaje)

    fun error(etiqueta: String, mensaje: String, t: Throwable? = null) {
        val texto = if (t == null) mensaje else "$mensaje · ${t.javaClass.simpleName}: ${t.message}"
        registro("E", etiqueta, texto)
    }

    /**
     * Deja en el registro una foto del consumo de memoria del proceso.
     *
     * El trabajo real (leer `smaps_rollup` para el PSS) va al hilo del
     * escritor: nunca debe ejecutarse en el hilo principal, que es el que
     * sufre los ANR.
     */
    fun memoria(etiqueta: String) {
        if (!listo) return
        escritor.execute {
            try {
                val rt = Runtime.getRuntime()
                val javaMb = (rt.totalMemory() - rt.freeMemory()) / 1_048_576
                val maxMb = rt.maxMemory() / 1_048_576
                val nativoMb = Debug.getNativeHeapAllocatedSize() / 1_048_576
                val pssMb = Debug.getPss() / 1024
                escribirAhora(
                    "I", "memoria",
                    "$etiqueta: pss=${pssMb}MB · java=${javaMb}MB/$maxMb · nativo=${nativoMb}MB",
                )
            } catch (_: Throwable) {
                // El diagnostico de memoria nunca debe tumbar a la app.
            }
        }
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
        registro("I", "sesion", "$MARCA_CIERRE limpiamente")
    }

    /** Resumen de las sesiones guardadas, de la mas reciente a la mas antigua. */
    fun sesiones(): List<Sesion> {
        if (!listo) return emptyList()
        val actual = archivo.name
        val ficheros = synchronized(cerrojo) {
            dir.listFiles { f -> idDeSesion(f) != null }
        } ?: return emptyList()
        return ficheros.mapNotNull { f ->
            val id = idDeSesion(f) ?: return@mapNotNull null
            val texto = synchronized(cerrojo) { textoDeSesion(id) }
            var lineas = 0
            var avisos = 0
            var errores = 0
            for (linea in texto.lineSequence()) {
                lineas++
                when (nivelDeLinea(linea)) {
                    "W" -> avisos++
                    "E", "C" -> errores++
                }
            }
            val fin = if (f.name == actual) 0L else f.lastModified()
            Sesion(
                inicioMs = id,
                finMs = fin,
                lineas = lineas,
                avisos = avisos,
                errores = errores,
                enCurso = f.name == actual,
                sinCerrar = f.name != actual &&
                    texto.contains("medicion iniciada") &&
                    !texto.contains(MARCA_CIERRE),
            )
        }.sortedByDescending { it.inicioMs }
    }

    /** Texto completo de una sesion concreta, recortado a [maxCaracteres]. */
    fun leerSesion(inicioMs: Long, maxCaracteres: Int = 64 * 1024): String {
        if (!listo) return ""
        val texto = synchronized(cerrojo) { textoDeSesion(inicioMs) }
        return recortar(texto, maxCaracteres)
    }

    /**
     * Devuelve el historico completo (ficheros antiguos + todas las sesiones),
     * recortado a [maxCaracteres]. Es la vista "ver todo".
     */
    fun read(maxCaracteres: Int = 64 * 1024): String {
        if (!listo) return ""
        val texto = synchronized(cerrojo) {
            buildString {
                if (historicoRodado.exists()) append(historicoRodado.readText())
                if (historico.exists()) append(historico.readText())
                for (id in idsDeSesion().sorted()) append(textoDeSesion(id))
            }
        }
        return recortar(texto, maxCaracteres)
    }

    /** Borra el registro, todas las sesiones y las marcas de sesion. */
    fun clear() {
        if (!listo) return
        synchronized(cerrojo) {
            historico.delete()
            historicoRodado.delete()
            for (id in idsDeSesion()) {
                File(dir, "$PREFIJO_SESION$id.log").delete()
                File(dir, "$PREFIJO_SESION$id$SUFIJO_ROTADO").delete()
            }
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
                if (archivo.length() + linea.toByteArray().size > MAX_BYTES_SESION) {
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

    /**
     * Conserva las ultimas [MAX_SESIONES] o [MAX_BYTES_TOTAL] bytes, lo que se
     * cumpla primero. La sesion en curso es la mas reciente y nunca se borra.
     */
    private fun purgarSesiones() {
        val ids = idsDeSesion().sortedDescending()
        val conservar = mutableListOf<Long>()
        var bytes = 0L
        for (id in ids) {
            val tam = bytesDeSesion(id)
            if (conservar.isNotEmpty() && (conservar.size >= MAX_SESIONES || bytes + tam > MAX_BYTES_TOTAL)) {
                break
            }
            conservar += id
            bytes += tam
        }
        val mantener = conservar.toHashSet()
        for (id in ids) {
            if (id in mantener) continue
            File(dir, "$PREFIJO_SESION$id.log").delete()
            File(dir, "$PREFIJO_SESION$id$SUFIJO_ROTADO").delete()
        }
    }

    private fun idsDeSesion(): List<Long> =
        dir.listFiles { f -> idDeSesion(f) != null }?.mapNotNull { idDeSesion(it) } ?: emptyList()

    private fun idDeSesion(f: File): Long? {
        if (!f.name.startsWith(PREFIJO_SESION) || !f.name.endsWith(".log")) return null
        val id = f.name.removePrefix(PREFIJO_SESION).removeSuffix(".log").toLongOrNull() ?: return null
        return if (id > 0) id else null
    }

    private fun textoDeSesion(id: Long): String = buildString {
        val r = File(dir, "$PREFIJO_SESION$id$SUFIJO_ROTADO")
        val f = File(dir, "$PREFIJO_SESION$id.log")
        if (r.exists()) append(r.readText())
        if (f.exists()) append(f.readText())
    }

    private fun bytesDeSesion(id: Long): Long =
        File(dir, "$PREFIJO_SESION$id.log").length() +
            File(dir, "$PREFIJO_SESION$id$SUFIJO_ROTADO").length()

    /** Nivel de una linea del diario ("I"/"W"/"E"/"C"), o null si es una traza. */
    private fun nivelDeLinea(linea: String): String? {
        val partes = linea.split(' ', limit = 4)
        if (partes.size < 3 || partes[1].length < 8 || !partes[1].contains(':')) return null
        return partes[2].takeIf { it in niveles }
    }

    private fun recortar(texto: String, maxCaracteres: Int): String =
        if (texto.length <= maxCaracteres) texto
        else "(...recorte de ${texto.length - maxCaracteres} caracteres...)\n" +
            texto.takeLast(maxCaracteres)

    private fun contextoDispositivo(context: Context): String {
        val version = runCatching {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            "${pi.versionName} (${pi.longVersionCode})"
        }.getOrDefault("?")
        return "Energia $version · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · " +
            "${Build.MANUFACTURER} ${Build.MODEL}"
    }
}
