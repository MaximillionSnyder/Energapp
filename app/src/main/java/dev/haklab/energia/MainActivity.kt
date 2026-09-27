package dev.haklab.energia

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.haklab.energia.diag.Diag
import dev.haklab.energia.ui.EnergyViewModel
import dev.haklab.energia.ui.Pantalla
import dev.haklab.energia.ui.screens.PantallaApps
import dev.haklab.energia.ui.screens.PantallaHistorial
import dev.haklab.energia.ui.screens.PantallaRegistro
import dev.haklab.energia.ui.screens.PantallaSistema
import dev.haklab.energia.ui.screens.PantallaVivo
import dev.haklab.energia.ui.theme.EnergiaTheme

class MainActivity : ComponentActivity() {

    /** El mismo ViewModel que consume Compose, para poder refrescar al volver. */
    private val vm: EnergyViewModel by viewModels()

    private val pedirNotificaciones =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
            Diag.info(
                "permiso",
                if (concedido) "notificaciones concedidas"
                else "notificaciones denegadas: el servicio medira sin ser visible",
            )
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pedirPermisoNotificaciones()
        setContent {
            EnergiaTheme {
                AppEnergia(vm)
                // El reparto por app y el registro se recargan al abrir su pestaña.
                val pantalla by vm.pantalla.collectAsStateWithLifecycle()
                LaunchedEffect(pantalla) {
                    when (pantalla) {
                        Pantalla.APPS -> vm.refrescarApps()
                        Pantalla.REGISTRO -> vm.refrescarRegistro()
                        else -> Unit
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Al volver de Ajustes, el usuario pudo haber concedido el acceso de uso.
        vm.refrescarPermiso()
        vm.refrescarApps()
    }

    /**
     * Android 13+ exige POST_NOTIFICATIONS en runtime. Sin ella el foreground
     * service arranca pero su notificacion no se ve, y el usuario no tiene
     * forma de saber que la medicion sigue activa.
     */
    private fun pedirPermisoNotificaciones() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val concedido = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!concedido) pedirNotificaciones.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppEnergia(vm: EnergyViewModel) {
    val estado by vm.estado.collectAsStateWithLifecycle()
    val pantalla by vm.pantalla.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    val permiso by vm.permisoUso.collectAsStateWithLifecycle()
    val registro by vm.registro.collectAsStateWithLifecycle()
    val anomalia by vm.anomaliaPrevia.collectAsStateWithLifecycle()
    val contexto = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Column {
                    Text("Energía", style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = if (estado.running) "Midiendo · ${estado.intervalMs / 1000} s" else "Detenido",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            })
        },
        bottomBar = {
            NavigationBar {
                Pantalla.entries.forEach { p ->
                    NavigationBarItem(
                        selected = pantalla == p,
                        onClick = { vm.seleccionar(p) },
                        icon = { IconoSinRecurso(p) },
                        label = { Text(p.titulo) },
                    )
                }
            }
        },
    ) { relleno ->
        Column(Modifier.fillMaxSize().padding(relleno)) {
            when (pantalla) {
                Pantalla.VIVO -> PantallaVivo(
                    estado = estado,
                    onIniciar = vm::iniciar,
                    onDetener = vm::detener,
                    onReiniciar = vm::reiniciar,
                )
                Pantalla.HISTORIAL -> PantallaHistorial(estado = estado)
                Pantalla.APPS -> PantallaApps(
                    estado = estado,
                    apps = apps,
                    tienePermiso = permiso,
                    onConcederPermiso = { contexto.startActivity(vm.intentPermiso()) },
                    onRefrescar = vm::refrescarApps,
                )
                Pantalla.SISTEMA -> PantallaSistema(estado = estado)
                Pantalla.REGISTRO -> PantallaRegistro(
                    estado = estado,
                    registro = registro,
                    anomaliaPrevia = anomalia,
                    onRefrescar = vm::refrescarRegistro,
                    onBorrar = vm::borrarRegistro,
                    onDescartar = vm::descartarAnomalia,
                    onCompartir = {
                        contexto.startActivity(
                            Intent.createChooser(
                                vm.intentCompartirRegistro(),
                                "Compartir registro",
                            ),
                        )
                    },
                )
            }
        }
    }
}

/**
 * Iconos de la barra inferior sin depender de material-icons-extended (que no
 * esta en cache): se usa el icono de bateria del sistema para todas y el
 * estado se distingue por la etiqueta y la seleccion. Documentado como
 * limitacion consciente en el README.
 */
@Composable
private fun IconoSinRecurso(p: Pantalla) {
    Icon(
        painter = painterResource(android.R.drawable.ic_lock_idle_charging),
        contentDescription = p.titulo,
    )
}
