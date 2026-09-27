package dev.haklab.energia.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.haklab.energia.data.PowerPoint
import kotlin.math.max

/**
 * Grafico de linea de potencia en el tiempo, dibujado con Canvas de Compose.
 *
 * Se dibuja a mano en vez de usar una libreria (Vico, KoalaPlot...) porque:
 *  - no anade dependencias ni peso al APK,
 *  - permite pintar los tramos con la pantalla APAGADA en otro color, que es
 *    justo la lectura que interesa ("que consume sin que yo lo use"),
 *  - no hay que lidiar con el ciclo de vida de una libreria para datos que
 *    llegan cada 5 s.
 *
 * Los valores NaN (dispositivos sin medidor de corriente) se saltan y la linea
 * se corta, en lugar de dibujar un cero enganoso.
 */
@Composable
fun PowerLineChart(
    points: List<PowerPoint>,
    modifier: Modifier = Modifier,
    pantallaApagada: Color = MaterialTheme.colorScheme.secondary,
) {
    val acento = MaterialTheme.colorScheme.primary
    val rejilla = MaterialTheme.colorScheme.outlineVariant
    val superficie = MaterialTheme.colorScheme.surfaceVariant

    Box(modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val validos = points.filter { it.milliwatts.isFinite() && it.milliwatts >= 0f }
            if (validos.size < 2) return@Canvas

            val maxY = max(validos.maxOf { it.milliwatts }.toDouble(), 1.0).toFloat()
            val t0 = validos.first().tSeconds
            val t1 = validos.last().tSeconds
            val spanT = max((t1 - t0), 0.001f)

            // margenes: arriba para que el pico no toque el borde, abajo para el eje
            val padTop = size.height * 0.12f
            val padBottom = size.height * 0.14f
            val alto = size.height - padTop - padBottom

            fun px(t: Float) = (t - t0) / spanT * size.width
            fun py(mw: Float) = padTop + alto * (1f - (mw / maxY).coerceIn(0f, 1f))

            // Rejilla horizontal con 3 lineas y su etiqueta de valor
            val pasos = 3
            for (i in 0..pasos) {
                val y = padTop + alto * i / pasos
                drawLine(
                    color = rejilla,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 12f)),
                )
            }

            // Relleno degradado bajo la curva
            val area = Path().apply {
                moveTo(px(validos.first().tSeconds), py(validos.first().milliwatts))
                validos.forEach { lineaSegura(this, px(it.tSeconds), py(it.milliwatts)) }
                lineTo(px(validos.last().tSeconds), size.height - padBottom)
                lineTo(px(validos.first().tSeconds), size.height - padBottom)
                close()
            }
            drawPath(
                path = area,
                brush = Brush.verticalGradient(
                    colors = listOf(acento.copy(alpha = 0.35f), Color.Transparent),
                    startY = padTop,
                    endY = size.height - padBottom,
                ),
            )

            // La curva se parte en segmentos segun el estado de pantalla, para
            // que el color indique en que estado se consumio esa energia.
            var inicio = 0
            while (inicio < validos.size - 1) {
                val encendida = validos[inicio].screenOn
                var fin = inicio
                while (fin < validos.size - 1 && validos[fin + 1].screenOn == encendida) fin++
                val segmento = Path().apply {
                    moveTo(px(validos[inicio].tSeconds), py(validos[inicio].milliwatts))
                    for (k in inicio + 1..fin) {
                        lineaSegura(this, px(validos[k].tSeconds), py(validos[k].milliwatts))
                    }
                }
                drawPath(
                    path = segmento,
                    color = if (encendida) acento else pantallaApagada,
                    style = Stroke(width = 3f, cap = StrokeCap.Round),
                )
                inicio = fin
            }

            // Punto actual, para que se vea que esta vivo
            val ultimo = validos.last()
            drawCircle(color = acento, radius = 6f, center = Offset(px(ultimo.tSeconds), py(ultimo.milliwatts)))
            drawCircle(
                color = superficie,
                radius = 3f,
                center = Offset(px(ultimo.tSeconds), py(ultimo.milliwatts)),
            )
        }

        // Escala del eje, calculada fuera del composable para no mezclar
        // aritmetica con el formateo de texto.
        val picoMw: Int = points
            .asSequence()
            .map { it.milliwatts }
            .filter { it.isFinite() }
            .maxOrNull()
            ?.toInt()
            ?.coerceAtLeast(1)
            ?: 0

        Text(
            text = "$picoMw mW",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 2.dp, top = 2.dp),
        )
    }
}

/** lineTo tolerante a NaN: si el valor no es finito, no se anade el punto. */
private fun lineaSegura(path: Path, x: Float, y: Float) {
    if (x.isFinite() && y.isFinite()) path.lineTo(x, y)
}

/**
 * Barra apilada horizontal: reparto de una magnitud entre dos o mas categorias.
 * Se hace con pesos de Compose en vez de Canvas porque es mas simple y accesible.
 */
@Composable
fun StackedShareBar(
    segmentos: List<Triple<String, Double, Color>>,
    modifier: Modifier = Modifier,
) {
    val total = segmentos.sumOf { it.second }
    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().height(18.dp).clip(RoundedCornerShape(9.dp)),
            horizontalArrangement = Arrangement.Start,
        ) {
            if (total <= 0.0) {
                Box(Modifier.fillMaxSize().weight(1f).clip(RoundedCornerShape(9.dp))) {}
            } else {
                segmentos.forEach { (_, valor, color) ->
                    val peso = (valor / total).toFloat().coerceAtLeast(0.0001f)
                    Box(
                        Modifier
                            .weight(peso)
                            .fillMaxSize()
                            .padding(horizontal = 0.5.dp)
                            .clip(RoundedCornerShape(4.dp)),
                    ) {
                        Canvas(Modifier.fillMaxSize()) { drawRect(color = color) }
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            segmentos.forEach { (etiqueta, valor, color) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(RoundedCornerShape(3.dp)),
                    ) {
                        Canvas(Modifier.fillMaxSize()) { drawRect(color = color) }
                    }
                    Text(
                        text = etiqueta,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                    if (total > 0.0) {
                        Text(
                            text = " ${(valor / total * 100).toInt()} %",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
