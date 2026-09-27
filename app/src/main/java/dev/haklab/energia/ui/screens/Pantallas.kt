package dev.haklab.energia.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.haklab.energia.UsageAttribution
import dev.haklab.energia.data.EnergyUiState
import dev.haklab.energia.ui.Format
import dev.haklab.energia.ui.charts.PowerLineChart
import dev.haklab.energia.ui.charts.StackedShareBar

/* ------------------------------------------------------------------ */
/* Piezas reutilizables                                                */
/* ------------------------------------------------------------------ */

@Composable
private fun Tarjeta(
    titulo: String,
    modifier: Modifier = Modifier,
    contenido: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = titulo,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            contenido()
        }
    }
}

@Composable
private fun Metrica(etiqueta: String, valor: String, destacada: Boolean = false) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(
            text = etiqueta,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = valor,
            style = if (destacada) MaterialTheme.typography.headlineSmall
            else MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun FilaMetrica(vararg pares: Pair<String, String>) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        pares.forEach { (etiqueta, valor) ->
            Column {
                Text(
                    etiqueta,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(valor, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val mw = estado.avgMw
        Tarjeta("Consumo medido") {
            Text(
                text = if (mw.isNaN()) "—" else Format.mw(mw),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = if (mw.isNaN()) {
                    "Sin datos válidos todavía"
                } else {
                    "media de ${Format.segundos(estado.validMs)} medidos"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            FilaMetrica(
                "Energía" to Format.joules(estado.energyJ),
                "Carga" to Format.mah(estado.chargeMah),
                "De la batería" to Format.porcentaje(estado.percentOfBattery),
            )
        }

        Tarjeta("Potencia en el tiempo") {
            if (estado.series.size < 2) {
                Text(
                    "Se dibujará en cuanto haya dos muestras.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                PowerLineChart(
                    points = estado.series,
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Verde: pantalla encendida · Ámbar: pantalla apagada",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onIniciar, enabled = !estado.running, modifier = Modifier.weight(1f)) {
                Text("Iniciar")
            }
            OutlinedButton(onClick = onDetener, enabled = estado.running, modifier = Modifier.weight(1f)) {
                Text("Detener")
            }
            TextButton(onClick = onReiniciar, modifier = Modifier.weight(1f)) {
                Text("Reiniciar")
            }
        }

        Tarjeta("Lectura instantánea") {
            if (s == null) {
                Text("Esperando la primera muestra…", style = MaterialTheme.typography.bodyMedium)
            } else {
                val fuente = when {
                    s.currentDerived -> "derivada del nivel (baja precisión)"
                    s.currentFromAverage -> "media del hardware"
                    else -> "instantánea"
                }
                Metrica("Corriente de descarga", if (s.dischargeMa.isNaN()) "—" else "${s.dischargeMa.toInt()} mA")
                Metrica("Potencia", Format.mw(s.powerMw))
                Metrica("Voltaje", Format.voltios(s.voltageV))
                Metrica("Temperatura", Format.celsius(s.temperatureC))
                Metrica("Nivel del sistema", "${s.levelPct} %")
                Metrica("Fuente de la corriente", fuente)
                s.chargeCounterUah?.let { Metrica("Contador de carga", Format.mah(it / 1000.0)) }
                estado.fullCapacityMah?.let { Metrica("Capacidad real estimada", Format.mah(it)) }
                if (!estado.hasHardwareCurrent) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Este dispositivo no expone corriente por hardware.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        if (estado.hasGaps) {
            Text(
                "Se descartaron tramos (huecos o carga): no se extrapolan.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/* ------------------------------------------------------------------ */
/* Pantalla: historial (graficas)                                      */
/* ------------------------------------------------------------------ */

@Composable
fun PantallaHistorial(estado: EnergyUiState, modifier: Modifier = Modifier) {
    val on = estado.screen.on
    val off = estado.screen.off
    val acento = MaterialTheme.colorScheme.primary
    val secundario = MaterialTheme.colorScheme.secondary

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Tarjeta("Energía por estado de pantalla") {
            StackedShareBar(
                segmentos = listOf(
                    Triple("Encendida", on.energyJ, acento),
                    Triple("Apagada", off.energyJ, secundario),
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            FilaMetrica(
                "Encendida" to Format.mw(on.avgMw),
                "Apagada" to Format.mw(off.avgMw),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Potencia media en cada estado, medida por hardware. Responde a " +
                    "«cuánto gasta el teléfono sin que lo use».",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Tiempo encendida", style = MaterialTheme.typography.labelMedium)
                Text(Format.segundos(on.millis), style = MaterialTheme.typography.labelMedium)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Tiempo apagada", style = MaterialTheme.typography.labelMedium)
                Text(Format.segundos(off.millis), style = MaterialTheme.typography.labelMedium)
            }
        }

        Tarjeta("Progreso del muestreo") {
            val total = estado.validMs + estado.discardedMs
            val fraccion = if (total > 0) estado.validMs.toFloat() / total else 0f
            LinearProgressIndicator(
                progress = { fraccion },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "${(fraccion * 100).toInt()} % del tiempo es válido · " +
                    "${Format.segundos(estado.validMs)} válidos, " +
                    "${Format.segundos(estado.discardedMs)} descartados",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            FilaMetrica(
                "Muestras" to estado.sampleCount.toString(),
                "Intervalo" to "${estado.intervalMs / 1000} s",
            )
        }
    }
}

/* ------------------------------------------------------------------ */
/* Pantalla: apps                                                      */
/* ------------------------------------------------------------------ */

@Composable
fun PantallaApps(
    estado: EnergyUiState,
    apps: List<UsageAttribution.AppUsage>,
    tienePermiso: Boolean,
    onConcederPermiso: () -> Unit,
    onRefrescar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val acento = MaterialTheme.colorScheme.primary
    val secundario = MaterialTheme.colorScheme.secondary

    Column(modifier.fillMaxSize().padding(16.dp)) {
        if (!tienePermiso) {
            Tarjeta("Falta un permiso") {
                Text(
                    "Para repartir el consumo por aplicación hay que conceder " +
                        "«acceso de uso». Sin él, la medición de consumo sigue " +
                        "funcionando pero no se puede desglosar.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onConcederPermiso) { Text("Conceder acceso de uso") }
            }
            return@Column
        }

        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${apps.size} apps con actividad",
                style = MaterialTheme.typography.labelLarge,
            )
            TextButton(onClick = onRefrescar) { Text("Actualizar") }
        }

        if (apps.isEmpty()) {
            Text(
                "Aún no hay tramos medidos. Deja la app midiendo y vuelve.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        val total = apps.sumOf { it.energyJ }.coerceAtLeast(0.0001)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(apps, key = { it.packageName }) { app ->
                val esSinAtribuir = app.packageName.startsWith("__")
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (esSinAtribuir) MaterialTheme.colorScheme.surface
                        else MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                app.label,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                Format.joules(app.energyJ),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        StackedShareBar(
                            segmentos = listOf(
                                Triple("de la sesión", app.energyJ, acento),
                                Triple("resto", total - app.energyJ, secundario),
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${Format.segundos(app.foregroundMs)} en primer plano · " +
                                "${Format.mw(app.avgMw)} de media",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* Pantalla: registro (diagnostico local)                              */
/* ------------------------------------------------------------------ */

@Composable
fun PantallaRegistro(
    estado: EnergyUiState,
    registro: String,
    anomaliaPrevia: Boolean,
    onRefrescar: () -> Unit,
    onBorrar: () -> Unit,
    onDescartar: () -> Unit,
    onCompartir: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (anomaliaPrevia) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "La sesión anterior no se cerró limpiamente",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "La app o el servicio murieron sin parada ordenada: un crash o el " +
                            "sistema mató el proceso. El detalle está abajo, en el registro.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onDescartar) { Text("Descartar aviso") }
                }
            }
        }

        Tarjeta("Estado del diagnóstico") {
            val ultima = estado.sample?.timestampMs
            val edadMs = if (ultima != null) System.currentTimeMillis() - ultima else null
            val retrasada = estado.running && edadMs != null && edadMs > estado.intervalMs * 3
            FilaMetrica(
                "Medición" to if (estado.running) "activa" else "detenida",
                "Muestras" to estado.sampleCount.toString(),
            )
            Spacer(Modifier.height(6.dp))
            FilaMetrica(
                "Última lectura" to when {
                    edadMs == null -> "—"
                    retrasada -> "${Format.segundos(edadMs)} (retrasada)"
                    else -> Format.segundos(edadMs)
                },
                "Registro" to "${(registro.length + 1023) / 1024} KB",
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Todo se guarda solo en este teléfono y se puede compartir como texto. " +
                    "Nada sale a la red por sí solo.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onCompartir, modifier = Modifier.weight(1f)) { Text("Compartir") }
            OutlinedButton(onClick = onRefrescar, modifier = Modifier.weight(1f)) { Text("Actualizar") }
            TextButton(onClick = onBorrar, modifier = Modifier.weight(1f)) { Text("Borrar") }
        }

        Tarjeta("Registro", modifier = Modifier.weight(1f)) {
            if (registro.isBlank()) {
                Text("Sin entradas todavía.", style = MaterialTheme.typography.bodyMedium)
            } else {
                SelectionContainer {
                    Text(
                        text = registro.lines().takeLast(400).joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    )
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* Pantalla: sistema (el veredicto)                                    */
/* ------------------------------------------------------------------ */

@Composable
fun PantallaSistema(estado: EnergyUiState, modifier: Modifier = Modifier) {
    val energiaBateriaJ = 5100.0 * 3.87 * 3600.0 / 1000.0

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Tarjeta("Por qué el medidor del sistema te engaña") {
            Text(
                "Android no mide el consumo de cada app: reparte el 100 % del " +
                    "gasto del periodo con un modelo (power_profile.xml). Su " +
                    "nivel tiene resolución de 1 %, y ese escalón vale unos " +
                    "${energiaBateriaJ.toInt() / 100} J.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Por eso una app puede aparecer con «10 % en 3 minutos» sin que " +
                    "sea físicamente posible: exigiría unos 39 W sostenidos.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Tarjeta("Lo que mide esta app") {
            val mw = estado.avgMw
            Metrica("Potencia media", Format.mw(mw), destacada = true)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Metrica("Energía acumulada", Format.joules(estado.energyJ))
            Metrica("Equivale a", Format.porcentaje(estado.percentOfBattery) + " de la batería")
            Metrica("Tiempo válido", Format.segundos(estado.validMs))
            if (estado.hasGaps) {
                Metrica("Tramos descartados", Format.segundos(estado.discardedMs))
            }
        }

        Tarjeta("Método") {
            Text(
                "P = V × I medida por hardware, integrada con la regla del " +
                    "trapecio sobre intervalos reales. Se descartan los tramos " +
                    "con carga, los huecos mayores de 2 minutos y los saltos del " +
                    "contador de carga: no se extrapola nada.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Precisión verificada: 0,53 % a 5 s de muestreo; 2,2 % a 30 s.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
