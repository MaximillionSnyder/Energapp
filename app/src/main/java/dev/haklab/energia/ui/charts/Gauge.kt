package dev.haklab.energia.ui.charts

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.haklab.energia.ui.Format
import dev.haklab.energia.ui.components.Cifra
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Medidor de aguja: la lectura instantanea como en un multimetro.
 *
 * El arco util va de 150 a 390 grados (240 de barrido, el clasico cuadrante
 * abierto por abajo) y la aguja se mueve con muelle para que se note el pulso
 * de la medida sin que el numero baile. Al pasar del 65 % de la escala el arco
 * vira hacia el ambar: es el aviso visual de que el consumo es alto para lo que
 * este telefono suele gastar.
 */
@Composable
fun GaugePotencia(
    valorMw: Double,
    mediaMw: Double,
    picoMw: Double,
    corriendo: Boolean,
    modifier: Modifier = Modifier,
) {
    val hayDato = valorMw.isFinite()

    val picoSeguro = when {
        picoMw.isFinite() && picoMw > 0.0 -> picoMw
        hayDato && valorMw > 0.0 -> valorMw
        else -> 1000.0
    }
    val escala = escalaBonita(picoSeguro)
    val fraccion = if (hayDato) (valorMw / escala).toFloat().coerceIn(0f, 1f) else 0f

    val aguja by animateFloatAsState(
        targetValue = fraccion,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessLow),
        label = "aguja",
    )
    val mostrado by animateFloatAsState(
        targetValue = if (hayDato) valorMw.toFloat() else 0f,
        animationSpec = tween(700),
        label = "valor",
    )

    val aqua = MaterialTheme.colorScheme.primary
    val ambar = MaterialTheme.colorScheme.secondary
    val alto = MaterialTheme.colorScheme.outline
    val pista = MaterialTheme.colorScheme.surfaceContainerHighest
    val tenue = MaterialTheme.colorScheme.onSurfaceVariant
    val fondo = MaterialTheme.colorScheme.surfaceContainerLowest
    val tinta = MaterialTheme.colorScheme.onSurface
    val medidor = rememberTextMeasurer()

    val colorArco = lerp(aqua, ambar, ((aguja - 0.65f) / 0.35f).coerceIn(0f, 1f))

    Box(modifier.fillMaxWidth().height(232.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val pad = 14.dp.toPx()
            val radio = min(size.width / 2f - 34.dp.toPx(), (size.height - 2 * pad) / 1.5f)
            val cx = size.width / 2f
            val cy = size.height - radio * 0.5f - pad

            val inicio = 150f
            val barrido = 240f
            val grosor = 15.dp.toPx()

            fun punto(anguloGrados: Float, r: Float): Offset {
                val a = anguloGrados * PI / 180.0
                return Offset((cx + r * cos(a)).toFloat(), (cy + r * sin(a)).toFloat())
            }

            // Pista y halo del arco: el halo da la sensacion de instrumento
            // encendido sin necesidad de sombras.
            drawArc(
                color = pista,
                startAngle = inicio,
                sweepAngle = barrido,
                useCenter = false,
                topLeft = Offset(cx - radio, cy - radio),
                size = Size(radio * 2, radio * 2),
                style = Stroke(width = grosor, cap = StrokeCap.Round),
            )
            if (aguja > 0f) {
                val arco = barrido * aguja
                drawArc(
                    color = colorArco.copy(alpha = 0.16f),
                    startAngle = inicio,
                    sweepAngle = arco,
                    useCenter = false,
                    topLeft = Offset(cx - radio, cy - radio),
                    size = Size(radio * 2, radio * 2),
                    style = Stroke(width = grosor * 2.1f, cap = StrokeCap.Round),
                )
                drawArc(
                    color = colorArco,
                    startAngle = inicio,
                    sweepAngle = arco,
                    useCenter = false,
                    topLeft = Offset(cx - radio, cy - radio),
                    size = Size(radio * 2, radio * 2),
                    style = Stroke(width = grosor, cap = StrokeCap.Round),
                )
            }

            // Marcas cada 20 grados; las pares mas largas.
            for (i in 0..12) {
                val angulo = inicio + barrido * i / 12f
                val largo = if (i % 2 == 0) 9.dp.toPx() else 5.dp.toPx()
                val exterior = radio + 4.dp.toPx()
                drawLine(
                    color = if (i % 2 == 0) alto.copy(alpha = 0.9f) else alto.copy(alpha = 0.45f),
                    start = punto(angulo, exterior),
                    end = punto(angulo, exterior + largo),
                    strokeWidth = 1.6.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }

            // Aguja con contrapeso: gira sobre el eje del medidor.
            val direccion = punto(inicio + barrido * aguja, 1f) - Offset(cx, cy)
            val largo = radio * 0.74f
            val alfaAguja = if (corriendo) 1f else 0.45f
            drawLine(
                color = tenue.copy(alpha = 0.35f * alfaAguja),
                start = Offset(cx, cy),
                end = Offset(cx - direccion.x * 14.dp.toPx(), cy - direccion.y * 14.dp.toPx()),
                strokeWidth = 2.5.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawLine(
                color = tinta.copy(alpha = alfaAguja),
                start = Offset(cx, cy),
                end = Offset(cx + direccion.x * largo, cy + direccion.y * largo),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawCircle(color = fondo, radius = 11.dp.toPx(), center = Offset(cx, cy))
            drawCircle(
                color = alto,
                radius = 11.dp.toPx(),
                center = Offset(cx, cy),
                style = Stroke(width = 1.2.dp.toPx()),
            )
            drawCircle(color = colorArco, radius = 4.dp.toPx(), center = Offset(cx, cy))

            // Extremos de la escala, dentro del lienzo.
            val estilo = TextStyle(color = tenue, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            val extremoIzq = punto(inicio, radio + 22.dp.toPx())
            drawText(
                textMeasurer = medidor,
                text = "0",
                topLeft = Offset(extremoIzq.x - 4.dp.toPx(), extremoIzq.y - 18.dp.toPx()),
                style = estilo,
            )
            val etiquetaMax = Format.mw(escala)
            val anchoMax = medidor.measure(etiquetaMax, estilo).size.width
            val extremoDer = punto(inicio + barrido, radio + 22.dp.toPx())
            drawText(
                textMeasurer = medidor,
                text = etiquetaMax,
                topLeft = Offset(extremoDer.x - anchoMax + 4.dp.toPx(), extremoDer.y - 18.dp.toPx()),
                style = estilo,
            )
        }

        // Lectura central: numero grande, unidad y estado.
        Column(
            modifier = Modifier.align(Alignment.Center).padding(bottom = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (hayDato) {
                val enVatios = mostrado >= 1000f
                Cifra(
                    valor = if (enVatios) String.format(Locale.getDefault(), "%.2f", mostrado / 1000f) else String.format(Locale.getDefault(), "%.0f", mostrado),
                    unidad = if (enVatios) "W" else "mW",
                    estilo = MaterialTheme.typography.displayMedium,
                )
            } else {
                Cifra(
                    valor = "—",
                    unidad = null,
                    estilo = MaterialTheme.typography.displayMedium,
                    color = tenue,
                )
            }
            Text(
                text = if (hayDato) "potencia instantanea" else "esperando la primera muestra",
                style = MaterialTheme.typography.labelSmall,
                color = tenue,
            )
        }

        Row(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "media · ${Format.mw(mediaMw)}",
                style = MaterialTheme.typography.labelMedium,
                color = tenue,
            )
            Text(
                text = "pico · ${Format.mw(picoMw)}",
                style = MaterialTheme.typography.labelMedium,
                color = tenue,
            )
        }
    }
}
