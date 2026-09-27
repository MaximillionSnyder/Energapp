package dev.haklab.energia.ui.charts

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.haklab.energia.data.PowerPoint
import dev.haklab.energia.ui.Format
import kotlin.math.max

/*
 * Graficos dibujados a mano con Canvas de Compose: sin dependencias, sin peso
 * y con control total del color, que aqui es semantico (aqua = pantalla
 * encendida, ambar = apagada). La linea NO se suaviza a proposito: una medida
 * que sube y baja se dibuja como sube y baja, sin inventar curvatura.
 */

/** Un tramo de una barra apilada, con su color de texto para las etiquetas. */
data class Segmento(
    val etiqueta: String,
    val valor: Double,
    val color: Color,
    val colorTexto: Color = Color.Unspecified,
)

/**
 * Linea de potencia en el tiempo.
 *
 * Los valores NaN (dispositivos sin medidor de corriente) se saltan y la linea
 * se corta, en lugar de dibujar un cero enganoso.
 */
@Composable
fun PowerLineChart(
    points: List<PowerPoint>,
    modifier: Modifier = Modifier,
    pantallaApagada: Color = MaterialTheme.colorScheme.secondary,
    vivo: Boolean = true,
) {
    val acento = MaterialTheme.colorScheme.primary
    val rejilla = MaterialTheme.colorScheme.outlineVariant
    val hueco = MaterialTheme.colorScheme.surfaceContainerLowest
    val tenue = MaterialTheme.colorScheme.onSurfaceVariant
    val medidor = rememberTextMeasurer()

    val validos = remember(points) {
        points.filter { it.milliwatts.isFinite() && it.milliwatts >= 0f }
    }

    val pulso = if (vivo) {
        val transicion = rememberInfiniteTransition(label = "trazo")
        transicion.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Restart),
            label = "destello",
        )
    } else {
        null
    }

    Box(modifier) {
        if (validos.size < 2) {
            Text(
                "Se dibujara en cuanto haya dos muestras.",
                style = MaterialTheme.typography.bodyMedium,
                color = tenue,
                modifier = Modifier.align(Alignment.Center).padding(16.dp),
            )
            return@Box
        }

        Canvas(Modifier.fillMaxSize()) {
            val margenAbajo = 26.dp.toPx()
            val margenArriba = 18.dp.toPx()
            val margenLados = 4.dp.toPx()
            val ancho = size.width - 2 * margenLados
            val alto = size.height - margenArriba - margenAbajo

            val picoMw = validos.maxOf { it.milliwatts }
            val escala = escalaBonita(picoMw.toDouble()).toFloat()
            val t0 = validos.first().tSeconds
            val t1 = validos.last().tSeconds
            val spanT = max(t1 - t0, 0.001f)

            fun px(t: Float) = margenLados + (t - t0) / spanT * ancho
            fun py(mw: Float) = margenArriba + alto * (1f - (mw / escala).coerceIn(0f, 1f))

            // Rejilla: cuatro divisiones, con la superior etiquetada.
            val divisiones = 4
            for (i in 0..divisiones) {
                val y = margenArriba + alto * i / divisiones
                drawLine(
                    color = rejilla,
                    start = Offset(margenLados, y),
                    end = Offset(size.width - margenLados, y),
                    strokeWidth = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 12f)),
                )
            }
            val estiloEscala = TextStyle(color = tenue, fontSize = 10.sp, fontWeight = FontWeight.Medium)
            drawText(
                textMeasurer = medidor,
                text = Format.mw(escala.toDouble()),
                topLeft = Offset(margenLados + 2.dp.toPx(), margenArriba - 14.dp.toPx()),
                style = estiloEscala,
            )

            // Relleno degradado bajo la curva.
            val area = Path().apply {
                moveTo(px(validos.first().tSeconds), py(validos.first().milliwatts))
                for (k in 1 until validos.size) {
                    val o = Offset(px(validos[k].tSeconds), py(validos[k].milliwatts))
                    if (o.x.isFinite() && o.y.isFinite()) lineTo(o.x, o.y)
                }
                lineTo(px(validos.last().tSeconds), margenArriba + alto)
                lineTo(px(validos.first().tSeconds), margenArriba + alto)
                close()
            }
            drawPath(
                path = area,
                brush = Brush.verticalGradient(
                    colors = listOf(acento.copy(alpha = 0.30f), acento.copy(alpha = 0.02f)),
                    startY = margenArriba,
                    endY = margenArriba + alto,
                ),
            )

            // La curva se parte segun el estado de pantalla: el color dice en
            // que estado se consumio esa energia. El tramo arranca en [inicio]
            // y llega al menos hasta [inicio + 1]: el punto donde cambia el
            // estado se comparte con el tramo siguiente, de modo que la linea
            // no se corta y el indice avanza SIEMPRE (un `fin` igual a `inicio`
            // dejaria el bucle girando sin fin, creando un Path por vuelta).
            var inicio = 0
            while (inicio < validos.size - 1) {
                val encendida = validos[inicio].screenOn
                var fin = inicio + 1
                while (fin < validos.size - 1 && validos[fin].screenOn == encendida) fin++
                val segmento = Path().apply {
                    moveTo(px(validos[inicio].tSeconds), py(validos[inicio].milliwatts))
                    for (k in inicio + 1..fin) {
                        val o = Offset(px(validos[k].tSeconds), py(validos[k].milliwatts))
                        if (o.x.isFinite() && o.y.isFinite()) lineTo(o.x, o.y)
                    }
                }
                drawPath(
                    path = segmento,
                    color = if (encendida) acento else pantallaApagada,
                    style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round),
                )
                inicio = fin
            }

            // Punto actual: un destello que sale del punto y se apaga recuerda
            // que la medida sigue viva.
            val ultimo = Offset(px(validos.last().tSeconds), py(validos.last().milliwatts))
            if (pulso != null) {
                val p by pulso
                drawCircle(
                    color = acento.copy(alpha = (1f - p) * 0.35f),
                    radius = (4.dp + 10.dp * p).toPx(),
                    center = ultimo,
                )
            }
            drawCircle(color = acento, radius = 4.5.dp.toPx(), center = ultimo)
            drawCircle(color = hueco, radius = 2.dp.toPx(), center = ultimo)

            // Extremos del eje temporal.
            drawText(
                textMeasurer = medidor,
                text = Format.segundos((t0 * 1000).toLong()),
                topLeft = Offset(margenLados, size.height - margenAbajo + 6.dp.toPx()),
                style = estiloEscala,
            )
            val etiquetaFin = medidor.measure(Format.segundos((t1 * 1000).toLong()), estiloEscala)
            drawText(
                textMeasurer = medidor,
                text = Format.segundos((t1 * 1000).toLong()),
                topLeft = Offset(
                    size.width - margenLados - etiquetaFin.size.width,
                    size.height - margenAbajo + 6.dp.toPx(),
                ),
                style = estiloEscala,
            )
        }
    }
}

/**
 * Barra apilada horizontal: reparto de una magnitud entre dos o mas categorias.
 * Se hace con pesos de Compose en vez de Canvas porque es mas simple y permite
 * etiquetar el interior.
 */
@Composable
fun StackedShareBar(
    segmentos: List<Segmento>,
    modifier: Modifier = Modifier,
    alto: Dp = 26.dp,
) {
    val total = segmentos.sumOf { it.valor }
    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().height(alto).clip(CircleShape),
            horizontalArrangement = Arrangement.Start,
        ) {
            if (total <= 0.0) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest))
            } else {
                segmentos.forEach { s ->
                    val peso = (s.valor / total).toFloat().coerceAtLeast(0.0001f)
                    Box(
                        Modifier
                            .weight(peso)
                            .fillMaxSize()
                            .padding(horizontal = 1.dp)
                            .clip(CircleShape)
                            .background(s.color),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (peso >= 0.16f) {
                            Text(
                                text = "${(peso * 100).toInt()} %",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = s.colorTexto,
                            )
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            segmentos.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(s.color),
                    )
                    Text(
                        text = s.etiqueta,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                    if (total > 0.0) {
                        Text(
                            text = " ${(s.valor / total * 100).toInt()} %",
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

/** Redondea a la escala superior mas legible: 500, 1000, 2000, 2500, 5000... */
internal fun escalaBonita(pico: Double): Double {
    if (!pico.isFinite() || pico <= 0.0) return 1000.0
    val margen = pico * 1.08
    var escala = 500.0
    while (escala < margen) {
        escala = when (escala) {
            500.0 -> 1000.0
            1000.0 -> 2000.0
            2000.0 -> 2500.0
            2500.0 -> 5000.0
            5000.0 -> 7500.0
            7500.0 -> 10_000.0
            else -> escala * 2
        }
    }
    return escala
}
