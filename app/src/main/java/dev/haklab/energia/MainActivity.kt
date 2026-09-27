package dev.haklab.energia

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.haklab.energia.diag.Diag
import dev.haklab.energia.ui.EnergyViewModel
import dev.haklab.energia.ui.Pantalla
import dev.haklab.energia.ui.components.PuntoLatido
import dev.haklab.energia.ui.glyphs.Glifo
import dev.haklab.energia.ui.screens.PantallaAnalisis
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
                // El reparto por app y el registro se recargan al abrir su pantalla.
                val pantalla by vm.pantalla.collectAsStateWithLifecycle()
                LaunchedEffect(pantalla) {
                    when (pantalla) {
                        Pantalla.ANALISIS -> vm.refrescarApps()
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
        Diag.memoria("onResume")
    }

    override fun onStop() {
        Diag.memoria("onStop")
        super.onStop()
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

@Composable
private fun AppEnergia(vm: EnergyViewModel) {
    val estado by vm.estado.collectAsStateWithLifecycle()
    val pantalla by vm.pantalla.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    val permiso by vm.permisoUso.collectAsStateWithLifecycle()
    val registro by vm.registro.collectAsStateWithLifecycle()
    val sesiones by vm.sesiones.collectAsStateWithLifecycle()
    val sesionDetalle by vm.sesionDetalle.collectAsStateWithLifecycle()
    val sesionAbierta by vm.sesionAbierta.collectAsStateWithLifecycle()
    val anomalia by vm.anomaliaPrevia.collectAsStateWithLifecycle()
    val contexto = LocalContext.current

    BackHandler(enabled = pantalla == Pantalla.REGISTRO) {
        if (sesionDetalle != null) vm.cerrarSesion() else vm.cerrarRegistro()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (pantalla != Pantalla.REGISTRO) {
                Cabecera(
                    corriendo = estado.running,
                    intervaloMs = estado.intervalMs,
                    anomalia = anomalia,
                    onRegistro = vm::abrirRegistro,
                )
            }
        },
        bottomBar = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 10.dp)
                    .navigationBarsPadding(),
            ) {
                Dock(pantalla = pantalla, onSeleccionar = vm::seleccionar)
            }
        },
    ) { relleno ->
        AnimatedContent(
            targetState = pantalla,
            transitionSpec = {
                (fadeIn(tween(240)) + slideInVertically(tween(280)) { alto -> alto / 26 })
                    .togetherWith(fadeOut(tween(140)))
            },
            label = "pantalla",
            modifier = Modifier.fillMaxSize().padding(relleno),
        ) { p ->
            when (p) {
                Pantalla.VIVO -> PantallaVivo(
                    estado = estado,
                    onIniciar = vm::iniciar,
                    onDetener = vm::detener,
                    onReiniciar = vm::reiniciar,
                )
                Pantalla.ANALISIS -> PantallaAnalisis(
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
                    sesiones = sesiones,
                    sesionDetalle = sesionDetalle,
                    sesionAbierta = sesionAbierta,
                    anomaliaPrevia = anomalia,
                    onVolver = vm::cerrarRegistro,
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
                    onAbrirSesion = vm::abrirSesion,
                    onCerrarSesion = vm::cerrarSesion,
                    onCompartirSesion = {
                        contexto.startActivity(
                            Intent.createChooser(
                                vm.intentCompartirSesion(),
                                "Compartir sesión",
                            ),
                        )
                    },
                )
            }
        }
    }
}

/** Cabecera: marca, estado de la medicion y acceso al registro. */
@Composable
private fun Cabecera(
    corriendo: Boolean,
    intervaloMs: Long,
    anomalia: Boolean,
    onRegistro: () -> Unit,
) {
    val tenue = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 18.dp, end = 8.dp, top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Energía", style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                PuntoLatido(
                    color = if (corriendo) MaterialTheme.colorScheme.primary else tenue,
                    activo = corriendo,
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = if (corriendo) "midiendo · cada ${intervaloMs / 1000} s" else "en reposo",
                    style = MaterialTheme.typography.labelSmall,
                    color = tenue,
                )
            }
        }
        Box {
            IconButton(onClick = onRegistro) {
                Glifo(
                    Glifo.Terminal,
                    Modifier.size(22.dp),
                    color = MaterialTheme.colorScheme.onSurface,
                    trazo = 1.8f,
                )
            }
            if (anomalia) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = (-10).dp, y = 10.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error),
                )
            }
        }
    }
}

/** Barra inferior flotante: la pestana activa se expande con su nombre. */
@Composable
private fun Dock(pantalla: Pantalla, onSeleccionar: (Pantalla) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 10.dp,
    ) {
        Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Pantalla.pestanas.forEach { p ->
                val seleccionada = p == pantalla
                val fondo by animateColorAsState(
                    targetValue = if (seleccionada) MaterialTheme.colorScheme.primary else Color.Transparent,
                    animationSpec = tween(220),
                    label = "fondo-pestana",
                )
                val tinta by animateColorAsState(
                    targetValue = if (seleccionada) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    animationSpec = tween(220),
                    label = "tinta-pestana",
                )
                Row(
                    Modifier
                        .weight(1f)
                        .clip(CircleShape)
                        .background(fondo)
                        .clickable(role = Role.Tab) { onSeleccionar(p) }
                        .padding(vertical = 11.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Glifo(p.glifo, Modifier.size(18.dp), color = tinta, trazo = 2.1f)
                    if (seleccionada) {
                        Spacer(Modifier.width(8.dp))
                        Text(p.titulo, style = MaterialTheme.typography.labelLarge, color = tinta)
                    }
                }
            }
        }
    }
}
