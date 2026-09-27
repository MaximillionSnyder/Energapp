package dev.haklab.energia.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.haklab.energia.MonitorService
import dev.haklab.energia.UsageAttribution
import dev.haklab.energia.data.EnergyRepository
import dev.haklab.energia.data.EnergyUiState
import dev.haklab.energia.diag.Diag
import dev.haklab.energia.ui.glyphs.Glifo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Pantallas de la app. Solo [pestanas] viven en la barra inferior. */
enum class Pantalla(val titulo: String, val glifo: Glifo) {
    VIVO("En vivo", Glifo.Rayo),
    ANALISIS("Análisis", Glifo.Pulso),
    SISTEMA("Sistema", Glifo.Escudo),
    REGISTRO("Registro", Glifo.Terminal);

    companion object {
        /** Las tres pestanas de la barra inferior, en orden. */
        val pestanas: List<Pantalla> = listOf(VIVO, ANALISIS, SISTEMA)
    }
}

class EnergyViewModel(app: Application) : AndroidViewModel(app) {

    private val attribution = UsageAttribution(app)

    /** Estado de la medicion; lo produce el servicio a traves del repositorio. */
    val estado: StateFlow<EnergyUiState> = EnergyRepository.state

    private val _pantalla = MutableStateFlow(Pantalla.VIVO)
    val pantalla: StateFlow<Pantalla> = _pantalla.asStateFlow()

    /** Pestana desde la que se abrio el registro, para poder volver. */
    private var pestanaPrevia: Pantalla = Pantalla.VIVO

    private val _apps = MutableStateFlow<List<UsageAttribution.AppUsage>>(emptyList())
    val apps: StateFlow<List<UsageAttribution.AppUsage>> = _apps.asStateFlow()

    private val _permisoUso = MutableStateFlow(false)
    val permisoUso: StateFlow<Boolean> = _permisoUso.asStateFlow()

    /** Registro de diagnostico ya cargado, listo para mostrar o compartir. */
    private val _registro = MutableStateFlow("")
    val registro: StateFlow<String> = _registro.asStateFlow()

    /** Sesiones de diagnostico, de la mas reciente a la mas antigua. */
    private val _sesiones = MutableStateFlow<List<Diag.Sesion>>(emptyList())
    val sesiones: StateFlow<List<Diag.Sesion>> = _sesiones.asStateFlow()

    /** Texto de la sesion abierta en el detalle, o null si no hay ninguna. */
    private val _sesionDetalle = MutableStateFlow<String?>(null)
    val sesionDetalle: StateFlow<String?> = _sesionDetalle.asStateFlow()

    /** Resumen de la sesion abierta; null con "ver todo" o sin detalle. */
    private val _sesionAbierta = MutableStateFlow<Diag.Sesion?>(null)
    val sesionAbierta: StateFlow<Diag.Sesion?> = _sesionAbierta.asStateFlow()

    /** true si la ultima sesion de medicion murio sin parada ordenada. */
    private val _anomaliaPrevia = MutableStateFlow(Diag.anomaliaPrevia.value)
    val anomaliaPrevia: StateFlow<Boolean> = _anomaliaPrevia.asStateFlow()

    init {
        refrescarPermiso()
    }

    fun seleccionar(p: Pantalla) { _pantalla.value = p }

    /** Abre el registro recordando desde que pestana se hizo. */
    fun abrirRegistro() {
        if (_pantalla.value != Pantalla.REGISTRO) pestanaPrevia = _pantalla.value
        _sesionAbierta.value = null
        _sesionDetalle.value = null
        _pantalla.value = Pantalla.REGISTRO
    }

    /** Vuelve a la pestana desde la que se abrio el registro. */
    fun cerrarRegistro() {
        _sesionAbierta.value = null
        _sesionDetalle.value = null
        _pantalla.value = pestanaPrevia
    }

    fun iniciar() = MonitorService.start(getApplication(), estado.value.intervalMs)

    fun detener() = MonitorService.stop(getApplication())

    fun reiniciar() = MonitorService.resetSession()

    private var permisoAvisado = false

    fun refrescarPermiso() {
        val disponible = seguro("permiso") { attribution.hasPermission() } ?: false
        if (!disponible && !permisoAvisado) {
            permisoAvisado = true
            Diag.info("permiso", "sin acceso de uso: el reparto por app no esta disponible")
        }
        _permisoUso.value = disponible
    }

    /** Intent de Ajustes para que el usuario conceda "acceso de uso". */
    fun intentPermiso(): Intent = attribution.settingsIntent()

    /** Recarga el registro y la lista de sesiones fuera del hilo principal. */
    fun refrescarRegistro() {
        viewModelScope.launch {
            _registro.value = seguro("registro") {
                withContext(Dispatchers.IO) { Diag.read() }
            } ?: _registro.value
            _sesiones.value = seguro("registro") {
                withContext(Dispatchers.IO) { Diag.sesiones() }
            } ?: _sesiones.value
        }
    }

    /** Abre una sesion concreta (o todo el historico con [inicioMs] = 0). */
    fun abrirSesion(inicioMs: Long) {
        _sesionAbierta.value = _sesiones.value.firstOrNull { it.inicioMs == inicioMs }
        viewModelScope.launch {
            _sesionDetalle.value = seguro("registro") {
                withContext(Dispatchers.IO) {
                    if (inicioMs == 0L) Diag.read() else Diag.leerSesion(inicioMs)
                }
            }
        }
    }

    /** Vuelve de la sesion abierta a la lista de sesiones. */
    fun cerrarSesion() {
        _sesionAbierta.value = null
        _sesionDetalle.value = null
    }

    fun borrarRegistro() {
        viewModelScope.launch {
            val limpio = seguro("registro") {
                withContext(Dispatchers.IO) {
                    Diag.clear()
                    Diag.read()
                }
            }
            if (limpio != null) {
                _registro.value = limpio
                _sesionAbierta.value = null
                _sesionDetalle.value = null
                _sesiones.value = seguro("registro") {
                    withContext(Dispatchers.IO) { Diag.sesiones() }
                } ?: emptyList()
                _anomaliaPrevia.value = false
            }
        }
    }

    fun descartarAnomalia() {
        Diag.descartarAnomalia()
        _anomaliaPrevia.value = false
    }

    /** Hoja de compartir del sistema con el registro como texto plano. */
    fun intentCompartirRegistro(): Intent =
        Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Registro de diagnóstico de Energía")
            .putExtra(Intent.EXTRA_TEXT, _registro.value.ifBlank { "(registro vacío)" })

    /** Hoja de compartir del sistema con la sesion abierta. */
    fun intentCompartirSesion(): Intent =
        Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Sesión de diagnóstico de Energía")
            .putExtra(Intent.EXTRA_TEXT, _sesionDetalle.value?.ifBlank { "(sesión vacía)" } ?: "(sin sesión)")

    /**
     * Recalcula el reparto por app con la energia medida hasta ahora. Es una
     * operacion de consulta al sistema, asi que va fuera del hilo principal.
     */
    fun refrescarApps() {
        viewModelScope.launch {
            val filas = seguro("atribucion") {
                val marks = EnergyRepository.marksSnapshot()
                val s = estado.value
                withContext(Dispatchers.IO) {
                    if (marks.isEmpty()) emptyList()
                    else attribution.attribute(marks, s.sessionStartMs, System.currentTimeMillis())
                }
            } ?: return@launch
            _apps.value = filas
            seguro("permiso") { attribution.hasPermission() }?.let { _permisoUso.value = it }
        }
    }

    /**
     * Ejecuta una operacion de consulta al sistema sin dejar que una excepcion
     * (p. ej. de UsageStatsManager) tumbe el proceso: se registra y se sigue.
     */
    private inline fun <T> seguro(etiqueta: String, bloque: () -> T): T? = try {
        bloque()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Diag.error(etiqueta, "operacion fallida, se continua", t)
        null
    }
}
