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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Pestañas de la app. */
enum class Pantalla(val titulo: String) {
    VIVO("En vivo"),
    HISTORIAL("Historial"),
    APPS("Apps"),
    SISTEMA("Sistema"),
    REGISTRO("Registro"),
}

class EnergyViewModel(app: Application) : AndroidViewModel(app) {

    private val attribution = UsageAttribution(app)

    /** Estado de la medicion; lo produce el servicio a traves del repositorio. */
    val estado: StateFlow<EnergyUiState> = EnergyRepository.state

    private val _pantalla = MutableStateFlow(Pantalla.VIVO)
    val pantalla: StateFlow<Pantalla> = _pantalla.asStateFlow()

    private val _apps = MutableStateFlow<List<UsageAttribution.AppUsage>>(emptyList())
    val apps: StateFlow<List<UsageAttribution.AppUsage>> = _apps.asStateFlow()

    private val _permisoUso = MutableStateFlow(false)
    val permisoUso: StateFlow<Boolean> = _permisoUso.asStateFlow()

    /** Registro de diagnostico ya cargado, listo para mostrar o compartir. */
    private val _registro = MutableStateFlow("")
    val registro: StateFlow<String> = _registro.asStateFlow()

    /** true si la ultima sesion de medicion murio sin parada ordenada. */
    private val _anomaliaPrevia = MutableStateFlow(Diag.anomaliaPrevia.value)
    val anomaliaPrevia: StateFlow<Boolean> = _anomaliaPrevia.asStateFlow()

    init {
        refrescarPermiso()
    }

    fun seleccionar(p: Pantalla) { _pantalla.value = p }

    fun iniciar() = MonitorService.start(getApplication(), estado.value.intervalMs)

    fun detener() = MonitorService.stop(getApplication())

    fun reiniciar() = MonitorService.resetSession()

    private var permisoAvisado = false

    fun refrescarPermiso() {
        val disponible = attribution.hasPermission()
        if (!disponible && !permisoAvisado) {
            permisoAvisado = true
            Diag.info("permiso", "sin acceso de uso: el reparto por app no esta disponible")
        }
        _permisoUso.value = disponible
    }

    /** Intent de Ajustes para que el usuario conceda "acceso de uso". */
    fun intentPermiso(): Intent = attribution.settingsIntent()

    /** Recarga el registro de diagnostico fuera del hilo principal. */
    fun refrescarRegistro() {
        viewModelScope.launch {
            _registro.value = withContext(Dispatchers.IO) { Diag.read() }
        }
    }

    fun borrarRegistro() {
        viewModelScope.launch {
            _registro.value = withContext(Dispatchers.IO) {
                Diag.clear()
                Diag.read()
            }
            _anomaliaPrevia.value = false
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

    /**
     * Recalcula el reparto por app con la energia medida hasta ahora. Es una
     * operacion de consulta al sistema, asi que va fuera del hilo principal.
     */
    fun refrescarApps() {
        viewModelScope.launch {
            val marks = EnergyRepository.marksSnapshot()
            val s = estado.value
            val filas = withContext(Dispatchers.IO) {
                if (marks.isEmpty()) emptyList()
                else attribution.attribute(marks, s.sessionStartMs, System.currentTimeMillis())
            }
            _apps.value = filas
            _permisoUso.value = attribution.hasPermission()
        }
    }
}
