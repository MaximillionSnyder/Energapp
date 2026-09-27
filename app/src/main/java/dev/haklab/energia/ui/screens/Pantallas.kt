package dev.haklab.energia.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.haklab.energia.UsageAttribution
import dev.haklab.energia.diag.Diag
import dev.haklab.energia.data.EnergyUiState
import dev.haklab.energia.ui.Format
import dev.haklab.energia.ui.charts.GaugePotencia
import dev.haklab.energia.ui.charts.PowerLineChart
import dev.haklab.energia.ui.charts.Segmento
import dev.haklab.energia.ui.charts.StackedShareBar
import dev.haklab.energia.ui.components.BarraProporcion
import dev.haklab.energia.ui.components.Cifra
import dev.haklab.energia.ui.components.Nota
import dev.haklab.energia.ui.components.Panel
import dev.haklab.energia.ui.components.Pastilla
import dev.haklab.energia.ui.components.Tile
import dev.haklab.energia.ui.glyphs.Glifo

/* ------------------------------------------------------------------ */
/* Piezas compartidas por las pantallas                                */
/* ------------------------------------------------------------------ */

/** Dato de una rejilla de tiles. */
private data class Dato(
    val etiqueta: String,
    val valor: String,
    val nota: String? = null,
    val acento: Color? = null,
)

/** Rejilla de tiles de una o dos columnas, siempre del mismo ancho. */
@Composable
private fun Rejilla(
    datos: List<Dato>,
    modifier: Modifier = Modifier,
    columnas: Int = 2,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        datos.chunked(columnas).forEach { fila ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                fila.forEach { d ->
                    Tile(
                        etiqueta = d.etiqueta,
                        valor = d.valor,
                        modifier = Modifier.weight(1f),
                        nota = d.nota,
                        acento = d.acento,
                    )
                }
                repeat(columnas - fila.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Leyenda de color para los graficos. */
@Composable
private fun Leyenda(entradas: List<Pair<String, Color>>, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        entradas.forEach { (texto, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(color),
                )
                Text(
                    text = texto,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* Pantalla: en vivo                                                   */
/* ------------------------------------------------------------------ */

@Composable
fun PantallaVivo(
    estado: EnergyUiState,
    onIniciar: () -> Unit,
    onDetener: () -> Unit,
    onReiniciar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = estado.sample
    val acento = MaterialTheme.colorScheme.primary
    val apagada = MaterialTheme.colorScheme.secondary
    val tenue = MaterialTheme.colorScheme.onSurfaceVariant

    val pico = estado.series
        .asSequence()
        .map { it.milliwatts }
        .filter { it.isFinite() }
        .maxOrNull()
        ?.toDouble()
        ?: 0.0

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Pastilla(
                texto = if (estado.running) "midiendo" else "detenido",
                color = if (estado.running) acento else tenue,
                pulso = estado.running,
            )
            Pastilla(
                texto = "cada ${estado.intervalMs / 1000} s",
                color = tenue,
                glifo = Glifo.Reloj,
            )
            Spacer(Modifier.weight(1f))
            if (!estado.hasHardwareCurrent) {
                Pastilla("sin medidor", color = MaterialTheme.colorScheme.error, glifo = Glifo.Aviso)
            }
        }

        Panel(titulo = "Potencia ahora", acento = acento) {
            GaugePotencia(
                valorMw = s?.powerMw ?: Double.NaN,
                mediaMw = estado.avgMw,
                picoMw = pico,
                corriendo = estado.running,
            )
        }

        Button(
            onClick = if (estado.running) onDetener else onIniciar,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = CircleShape,
        ) {
            if (estado.running) {
                Glifo(Glifo.Parar, Modifier.size(15.dp), color = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(8.dp))
                Text("Detener medición")
            } else {
                Glifo(Glifo.Reproducir, Modifier.size(17.dp), color = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(8.dp))
                Text("Iniciar medición")
            }
        }
        TextButton(
            onClick = onReiniciar,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Glifo(Glifo.Reiniciar, Modifier.size(15.dp), color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(7.dp))
            Text("Reiniciar sesión")
        }

        Panel(titulo = "Potencia en el tiempo") {
            PowerLineChart(
                points = estado.series,
                modifier = Modifier.fillMaxWidth().height(172.dp),
                vivo = estado.running,
            )
            if (estado.series.size >= 2) {
                Spacer(Modifier.height(10.dp))
                Leyenda(
                    listOf(
                        "pantalla encendida" to acento,
                        "pantalla apagada" to apagada,
                    ),
                )
            }
        }

        Panel(titulo = "Lectura instantánea") {
            if (s == null) {
                Text(
                    "Esperando la primera muestra…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = tenue,
                )
            } else {
                val fuente = when {
                    s.currentDerived -> "derivada"
                    s.currentFromAverage -> "media HW"
                    else -> "instantánea"
                }
                val datos = buildList {
                    add(Dato("Corriente", if (s.dischargeMa.isNaN()) "—" else "${s.dischargeMa.toInt()} mA"))
                    add(Dato("Voltaje", Format.voltios(s.voltageV)))
                    add(Dato("Potencia", Format.mw(s.powerMw)))
                    add(Dato("Temperatura", Format.celsius(s.temperatureC)))
                    add(Dato("Nivel sistema", "${s.levelPct} %", acento = acento))
                    add(
                        Dato(
                            etiqueta = "Fuente corriente",
                            valor = fuente,
                            nota = if (s.currentDerived) "baja precisión" else null,
                            acento = if (s.currentDerived) apagada else null,
                        ),
                    )
                    s.chargeCounterUah?.let {
                        add(Dato("Contador de carga", Format.mah(it / 1000.0)))
                    }
                    estado.fullCapacityMah?.let {
                        add(Dato("Capacidad real", Format.mah(it)))
                    }
                }
                Rejilla(datos)
                if (!estado.hasHardwareCurrent) {
                    Spacer(Modifier.height(12.dp))
                    Nota(
                        "Este dispositivo no expone corriente por hardware: la medida es aproximada.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        if (estado.hasGaps) {
            Nota("Se descartaron tramos (huecos o carga): no se extrapolan.")
        }
    }
}

/* ------------------------------------------------------------------ */
/* Pantalla: analisis (estado de pantalla + reparto por app)            */
/* ------------------------------------------------------------------ */

@Composable
fun PantallaAnalisis(
    estado: EnergyUiState,
    apps: List<UsageAttribution.AppUsage>,
    tienePermiso: Boolean,
    onConcederPermiso: () -> Unit,
    onRefrescar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val on = estado.screen.on
    val off = estado.screen.off
    val acento = MaterialTheme.colorScheme.primary
    val apagada = MaterialTheme.colorScheme.secondary
    val tenue = MaterialTheme.colorScheme.onSurfaceVariant

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Panel(titulo = "Reparto por estado de pantalla") {
                StackedShareBar(
                    segmentos = listOf(
                        Segmento(
                            etiqueta = "Encendida",
                            valor = on.energyJ,
                            color = acento,
                            colorTexto = MaterialTheme.colorScheme.onPrimary,
                        ),
                        Segmento(
                            etiqueta = "Apagada",
                            valor = off.energyJ,
                            color = apagada,
                            colorTexto = MaterialTheme.colorScheme.onSecondary,
                        ),
                    ),
                )
                Spacer(Modifier.height(16.dp))
                Rejilla(
                    listOf(
                        Dato(
                            etiqueta = "Encendida",
                            valor = Format.joules(on.energyJ),
                            nota = "${Format.mw(on.avgMw)} · ${Format.segundos(on.millis)}",
                            acento = acento,
                        ),
                        Dato(
                            etiqueta = "Apagada",
                            valor = Format.joules(off.energyJ),
                            nota = "${Format.mw(off.avgMw)} · ${Format.segundos(off.millis)}",
                            acento = apagada,
                        ),
                    ),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Potencia media medida por hardware en cada estado. La cifra de " +
                        "«apagada» responde a la pregunta que importa: cuánto gasta el " +
                        "teléfono sin que lo estés usando.",
                    style = MaterialTheme.typography.bodySmall,
                    color = tenue,
                )
            }
        }

        item {
            val total = estado.validMs + estado.discardedMs
            val fraccion = if (total > 0) estado.validMs.toFloat() / total else 0f
            Panel(titulo = "Calidad del muestreo") {
                Cifra(
                    valor = "${(fraccion * 100).toInt()} %",
                    etiqueta = "del tiempo es válido",
                    estilo = MaterialTheme.typography.displaySmall,
                    color = acento,
                )
                Spacer(Modifier.height(14.dp))
                BarraProporcion(fraccion = fraccion, color = acento)
                Spacer(Modifier.height(16.dp))
                Rejilla(
                    listOf(
                        Dato("Muestras", estado.sampleCount.toString()),
                        Dato("Intervalo", "${estado.intervalMs / 1000} s"),
                        Dato("Tiempo válido", Format.segundos(estado.validMs)),
                        Dato(
                            etiqueta = "Descartado",
                            valor = Format.segundos(estado.discardedMs),
                            acento = if (estado.discardedMs > 0) apagada else null,
                        ),
                    ),
                )
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Por aplicación", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "energía medida mientras estuvo delante",
                        style = MaterialTheme.typography.bodySmall,
                        color = tenue,
                    )
                }
                TextButton(onClick = onRefrescar) {
                    Glifo(Glifo.Refrescar, Modifier.size(15.dp), color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(7.dp))
                    Text("Actualizar")
                }
            }
        }

        if (!tienePermiso) {
            item {
                Panel(titulo = "Falta un permiso") {
                    Text(
                        "Para repartir el consumo por aplicación hay que conceder el " +
                            "«acceso de uso». Sin él, la medición de consumo sigue " +
                            "funcionando pero no se puede desglosar.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = onConcederPermiso, shape = CircleShape) {
                        Text("Conceder acceso de uso")
                    }
                }
            }
        } else if (apps.isEmpty()) {
            item {
                Panel(titulo = "Sin datos todavía") {
                    Text(
                        "Aún no hay tramos medidos. Deja la app midiendo un rato y vuelve.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        } else {
            val total = apps.sumOf { it.energyJ }.coerceAtLeast(0.0001)
            items(apps, key = { it.packageName }) { app ->
                FilaApp(app = app, totalSesion = total)
            }
            item {
                Nota(
                    "No es una estimación del sistema: es la energía medida, repartida " +
                        "por el tiempo que cada app estuvo en primer plano. Lo que no tuvo " +
                        "pantalla delante va a «sin atribuir».",
                    color = MaterialTheme.colorScheme.tertiary,
                    glifo = Glifo.Escudo,
                )
            }
        }
    }
}

@Composable
private fun FilaApp(app: UsageAttribution.AppUsage, totalSesion: Double) {
    val sinAtribuir = app.packageName.startsWith("__")
    val acento = if (sinAtribuir) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary
    val tenue = MaterialTheme.colorScheme.onSurfaceVariant
    val proporcion = (app.energyJ / totalSesion).toFloat()

    Panel(
        color = if (sinAtribuir) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.titleMedium,
                color = if (sinAtribuir) tenue else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = Format.joules(app.energyJ),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Spacer(Modifier.height(10.dp))
        BarraProporcion(fraccion = proporcion, color = acento, alto = 8.dp)
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "${(proporcion * 100).toInt()} % de la sesión",
                style = MaterialTheme.typography.labelMedium,
                color = tenue,
            )
            Text(
                "${Format.segundos(app.foregroundMs)} delante · ${Format.mw(app.avgMw)}",
                style = MaterialTheme.typography.labelMedium,
                color = tenue,
            )
        }
    }
}

/* ------------------------------------------------------------------ */
/* Pantalla: sistema (el veredicto frente al medidor de Android)       */
/* ------------------------------------------------------------------ */

/** Error del muestreo frente a la integración fina (paso 0,2 s). */
private val PRECISION = listOf(
    "1 s" to 0.107,
    "5 s" to 0.529,
    "30 s" to 2.203,
    "120 s" to 9.610,
)

@Composable
fun PantallaSistema(estado: EnergyUiState, modifier: Modifier = Modifier) {
    val acento = MaterialTheme.colorScheme.primary
    val tenue = MaterialTheme.colorScheme.onSurfaceVariant

    val capacidadMah = estado.fullCapacityMah ?: 5100.0
    val voltaje = estado.sample?.voltageV?.takeIf { it.isFinite() && it > 0.0 } ?: 3.87
    val energiaBateriaJ = capacidadMah / 1000.0 * voltaje * 3600.0
    val escalonJ = energiaBateriaJ / 100.0
    val mw = estado.avgMw
    val segundosPorEscalon = if (mw.isFinite() && mw > 0.0) escalonJ / (mw / 1000.0) else Double.NaN

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Panel(titulo = "El veredicto", acento = acento) {
            Text(
                "Android no mide el consumo de cada app: reparte el 100 % del gasto " +
                    "del periodo con un modelo (power_profile.xml) sobre un nivel con " +
                    "resolución de 1 %.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(14.dp))
            Rejilla(
                listOf(
                    Dato("Medido ahora", Format.mw(mw), acento = acento),
                    Dato("Un escalón del 1 %", Format.joules(escalonJ)),
                ),
            )
            Spacer(Modifier.height(14.dp))
            if (segundosPorEscalon.isFinite()) {
                Text(
                    "A tu consumo medido, un solo escalón del medidor de Android " +
                        "equivale a ${Format.segundos((segundosPorEscalon * 1000).toLong())} " +
                        "de uso real. Por debajo de ese tiempo el sistema no tiene " +
                        "resolución: no puede ver lo que esta app sí mide.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = tenue,
                )
            } else {
                Text(
                    "Mide al menos unos segundos para poder comparar tu consumo " +
                        "real con la resolución del medidor del sistema.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = tenue,
                )
            }
        }

        Panel(titulo = "Lo que mide esta app") {
            Rejilla(
                listOf(
                    Dato("Potencia media", Format.mw(mw), acento = acento),
                    Dato("Energía acumulada", Format.joules(estado.energyJ)),
                    Dato("De la batería", Format.porcentaje(estado.percentOfBattery)),
                    Dato("Tiempo válido", Format.segundos(estado.validMs)),
                    Dato("Carga integrada", Format.mah(estado.chargeMah)),
                    Dato(
                        etiqueta = "Descartado",
                        valor = Format.segundos(estado.discardedMs),
                        acento = if (estado.discardedMs > 0) MaterialTheme.colorScheme.secondary else null,
                    ),
                ),
            )
        }

        Panel(titulo = "Método") {
            Text(
                "Potencia = V × I, leída del medidor por hardware e integrada con la " +
                    "regla del trapecio sobre intervalos reales. Se descartan los tramos " +
                    "con carga, los huecos de más de 2 minutos y los saltos del contador " +
                    "de carga: no se extrapola nada.",
                style = MaterialTheme.typography.bodySmall,
                color = tenue,
            )
            Spacer(Modifier.height(18.dp))
            Text(
                "ERROR FRENTE A LA INTEGRACIÓN FINA (0,2 S)",
                style = MaterialTheme.typography.labelSmall,
                color = tenue,
            )
            Spacer(Modifier.height(12.dp))
            PRECISION.forEach { (muestreo, error) ->
                Column(Modifier.padding(bottom = 12.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Muestreo a $muestreo", style = MaterialTheme.typography.bodySmall)
                        Text(
                            "${String.format(java.util.Locale.getDefault(), "%.3f", error)} %",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    BarraProporcion(
                        fraccion = (error / 10.0).toFloat(),
                        color = if (error < 1.0) acento else MaterialTheme.colorScheme.secondary,
                        alto = 6.dp,
                    )
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* Pantalla: registro (diagnostico local por sesiones)                 */
/* ------------------------------------------------------------------ */

/** Cuantas lineas de una sesion se muestran en el detalle. */
private const val MAX_LINEAS_DETALLE = 800

@Composable
fun PantallaRegistro(
    estado: EnergyUiState,
    registro: String,
    sesiones: List<Diag.Sesion>,
    sesionDetalle: String?,
    sesionAbierta: Diag.Sesion?,
    anomaliaPrevia: Boolean,
    onVolver: () -> Unit,
    onRefrescar: () -> Unit,
    onBorrar: () -> Unit,
    onDescartar: () -> Unit,
    onCompartir: () -> Unit,
    onAbrirSesion: (Long) -> Unit,
    onCerrarSesion: () -> Unit,
    onCompartirSesion: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (sesionDetalle != null) {
        DetalleRegistro(
            sesion = sesionAbierta,
            texto = sesionDetalle,
            onVolver = onCerrarSesion,
            onCompartir = onCompartirSesion,
            modifier = modifier,
        )
        return
    }

    val tenue = MaterialTheme.colorScheme.onSurfaceVariant
    val acento = MaterialTheme.colorScheme.primary
    val ultima = estado.sample?.timestampMs
    val edadMs = if (ultima != null) System.currentTimeMillis() - ultima else null
    val retrasada = estado.running && edadMs != null && edadMs > estado.intervalMs * 3

    Column(
        modifier.fillMaxSize().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onVolver) {
                Glifo(Glifo.Atras, Modifier.size(22.dp), color = MaterialTheme.colorScheme.onSurface)
            }
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text("Registro", style = MaterialTheme.typography.titleLarge)
                Text(
                    "diagnóstico local, sin red",
                    style = MaterialTheme.typography.bodySmall,
                    color = tenue,
                )
            }
            Pastilla(
                texto = if (retrasada) "tick retrasado" else "al día",
                color = if (retrasada) MaterialTheme.colorScheme.secondary else acento,
                glifo = if (retrasada) Glifo.Aviso else Glifo.Reloj,
            )
        }

        if (anomaliaPrevia) {
            Panel(
                color = MaterialTheme.colorScheme.errorContainer,
                titulo = "Sesión anterior sin cerrar",
                acento = MaterialTheme.colorScheme.error,
            ) {
                Text(
                    "La app o el servicio murieron sin parada ordenada: un crash o el " +
                        "sistema mató el proceso. Abre esa sesión para ver el detalle.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                val sospechosa = sesiones.firstOrNull { it.sinCerrar || !it.enCurso && it.errores > 0 }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (sospechosa != null) {
                        TextButton(onClick = { onAbrirSesion(sospechosa.inicioMs) }) {
                            Text("Ver sesión", color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                    TextButton(onClick = onDescartar) {
                        Text("Descartar aviso", color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }

        Panel(titulo = "Estado del diagnóstico") {
            Rejilla(
                listOf(
                    Dato("Medición", if (estado.running) "activa" else "detenida", acento = if (estado.running) acento else null),
                    Dato("Muestras", estado.sampleCount.toString()),
                    Dato(
                        etiqueta = "Última lectura",
                        valor = when {
                            edadMs == null -> "—"
                            else -> Format.segundos(edadMs)
                        },
                        nota = if (retrasada) "retrasada" else null,
                        acento = if (retrasada) MaterialTheme.colorScheme.secondary else null,
                    ),
                    Dato("Registro", "${(registro.length + 1023) / 1024} KB"),
                ),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Todo se guarda solo en este teléfono y se puede compartir como texto. " +
                    "Nada sale a la red por sí solo.",
                style = MaterialTheme.typography.bodySmall,
                color = tenue,
            )
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(onClick = onCompartir, modifier = Modifier.weight(1f), shape = CircleShape) {
                Glifo(Glifo.Compartir, Modifier.size(15.dp), color = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(7.dp))
                Text("Compartir")
            }
            OutlinedButton(onClick = onRefrescar, modifier = Modifier.weight(1f), shape = CircleShape) {
                Glifo(Glifo.Refrescar, Modifier.size(15.dp), color = acento)
                Spacer(Modifier.width(7.dp))
                Text("Actualizar")
            }
            OutlinedButton(
                onClick = onBorrar,
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Glifo(Glifo.Papelera, Modifier.size(16.dp), color = MaterialTheme.colorScheme.error)
            }
        }

        Panel(modifier = Modifier.weight(1f), titulo = "Sesiones") {
            if (sesiones.isEmpty()) {
                Text(
                    "Sin sesiones todavía.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = tenue,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(sesiones, key = { it.inicioMs }) { s ->
                        FilaSesion(sesion = s, onAbrir = onAbrirSesion)
                    }
                    item {
                        TextButton(
                            onClick = { onAbrirSesion(0L) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Glifo(Glifo.Terminal, Modifier.size(15.dp), color = acento)
                            Spacer(Modifier.width(7.dp))
                            Text("Ver todo el histórico")
                        }
                    }
                }
            }
        }
    }
}

/** Fila de la lista: fecha, resumen y estado de una sesion. */
@Composable
private fun FilaSesion(sesion: Diag.Sesion, onAbrir: (Long) -> Unit) {
    val tenue = MaterialTheme.colorScheme.onSurfaceVariant
    val acento = MaterialTheme.colorScheme.primary
    val sinCerrar = sesion.sinCerrar
    val conError = sesion.errores > 0
    Surface(
        modifier = Modifier.fillMaxWidth(),
        onClick = { onAbrir(sesion.inicioMs) },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(Format.fechaHora(sesion.inicioMs), style = MaterialTheme.typography.titleMedium)
                Text(
                    textoResumen(sesion),
                    style = MaterialTheme.typography.bodySmall,
                    color = tenue,
                )
            }
            Pastilla(
                texto = when {
                    sesion.enCurso -> "en curso"
                    sinCerrar -> "sin cerrar"
                    else -> "cerrada"
                },
                color = when {
                    conError -> MaterialTheme.colorScheme.error
                    sinCerrar -> MaterialTheme.colorScheme.secondary
                    else -> acento
                },
                glifo = if (conError || sinCerrar) Glifo.Aviso else null,
            )
        }
    }
}

/** Detalle de una sesion (o de todo el historico con [sesion] = null). */
@Composable
private fun DetalleRegistro(
    sesion: Diag.Sesion?,
    texto: String,
    onVolver: () -> Unit,
    onCompartir: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contexto = LocalContext.current
    val tenue = MaterialTheme.colorScheme.onSurfaceVariant
    val acento = MaterialTheme.colorScheme.primary

    Column(
        modifier.fillMaxSize().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onVolver) {
                Glifo(Glifo.Atras, Modifier.size(22.dp), color = MaterialTheme.colorScheme.onSurface)
            }
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = sesion?.let { Format.fechaHora(it.inicioMs) } ?: "Todo el histórico",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = sesion?.let { textoResumen(it) } ?: "sesiones + histórico anterior",
                    style = MaterialTheme.typography.bodySmall,
                    color = tenue,
                )
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(onClick = onCompartir, modifier = Modifier.weight(1f), shape = CircleShape) {
                Glifo(Glifo.Compartir, Modifier.size(15.dp), color = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(7.dp))
                Text("Compartir")
            }
            OutlinedButton(
                onClick = { copiarAlPortapapeles(contexto, texto) },
                modifier = Modifier.weight(1f),
                shape = CircleShape,
            ) {
                Text("Copiar")
            }
        }

        Panel(modifier = Modifier.weight(1f), titulo = "Diario") {
            if (texto.isBlank()) {
                Text(
                    "Sin entradas todavía.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = tenue,
                )
            } else {
                val lineas = texto.lines()
                val mostradas = if (lineas.size > MAX_LINEAS_DETALLE) lineas.takeLast(MAX_LINEAS_DETALLE) else lineas
                val recorte = if (lineas.size > MAX_LINEAS_DETALLE) {
                    "(...${lineas.size - MAX_LINEAS_DETALLE} líneas anteriores omitidas...)\n"
                } else {
                    ""
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                        .padding(12.dp),
                ) {
                    SelectionContainer {
                        Text(
                            text = recorte + mostradas.joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = tenue,
                            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        )
                    }
                }
            }
        }
    }
}

/** Resumen de una sesion en una linea: duracion, lineas, avisos y errores. */
private fun textoResumen(s: Diag.Sesion): String = buildString {
    val duracion = when {
        s.enCurso -> "en curso"
        s.finMs > s.inicioMs -> Format.segundos(s.finMs - s.inicioMs)
        else -> "—"
    }
    append(duracion)
    append(" · ${s.lineas} líneas")
    if (s.avisos > 0) append(" · ${s.avisos} avisos")
    if (s.errores > 0) append(" · ${s.errores} errores")
}

private fun copiarAlPortapapeles(contexto: Context, texto: String) {
    val gestor = contexto.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    gestor.setPrimaryClip(ClipData.newPlainText("Energía", texto))
}
