package dev.haklab.energia.ui.glyphs

import androidx.compose.foundation.Canvas
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Iconografia propia dibujada con Canvas: no anade dependencias (ni el peso de
 * material-icons-extended), mantiene el trazo coherente en las dos pantallas y
 * se puede ajustar el grosor al tamano del icono.
 *
 * Todo se define en una reticula de 24x24 y se escala al tamano del Canvas; el
 * glifo debe recibir un tamano fijo (p. ej. `Modifier.size(22.dp)`).
 */
enum class Glifo {
    Rayo, Pulso, Escudo, Reproducir, Parar, Reiniciar, Refrescar,
    Compartir, Papelera, Terminal, Atras, Aviso, Reloj, Bateria,
}

@Composable
fun Glifo(
    glifo: Glifo,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current,
    trazo: Float = 1.9f,
) {
    Canvas(modifier) { dibujarGlifo(glifo, color, trazo) }
}

private fun DrawScope.dibujarGlifo(g: Glifo, color: Color, trazo: Float) {
    val l = Lapiz(this, color, trazo)
    when (g) {
        // Rayo: la marca de la casa.
        Glifo.Rayo -> l.relleno(
            13f, 2f, 5.5f, 13.5f, 11f, 13.5f, 10f, 22f, 18.5f, 10.5f, 13f, 10.5f,
        )
        // Pulso: latido de la medida.
        Glifo.Pulso -> l.lineas(
            2.5f, 13.5f, 7f, 13.5f, 9.5f, 8f, 12.5f, 17.5f, 15f, 11.5f, 17f, 13.5f, 21.5f, 13.5f,
        )
        // Escudo con visto: verificado contra el sistema.
        Glifo.Escudo -> {
            l.lineas(
                12f, 3.2f, 19.6f, 6.2f, 19.6f, 11.8f, 12f, 21f, 4.4f, 11.8f, 4.4f, 6.2f,
                cerrado = true,
            )
            l.lineas(8.5f, 12f, 11f, 14.6f, 15.7f, 9.4f)
        }
        Glifo.Reproducir -> l.relleno(8.5f, 6.2f, 18.5f, 12f, 8.5f, 17.8f)
        Glifo.Parar -> l.bloque(7.5f, 7.5f, 16.5f, 16.5f, 2.2f)
        // Flecha circular con punta: reiniciar la sesion.
        Glifo.Reiniciar -> {
            l.arco(12f, 12f, 7.6f, inicio = -50f, barrido = 300f)
            l.puntaDeFlecha(12f, 12f, 7.6f, angulo = 250f)
        }
        // Dos medias lunas con punta: actualizar la lectura.
        Glifo.Refrescar -> {
            l.arco(12f, 12f, 7.2f, inicio = -160f, barrido = 180f)
            l.puntaDeFlecha(12f, 12f, 7.2f, angulo = 20f)
            l.arco(12f, 12f, 7.2f, inicio = 20f, barrido = 140f)
            l.puntaDeFlecha(12f, 12f, 7.2f, angulo = 160f)
        }
        Glifo.Compartir -> {
            l.linea(8.8f, 10.8f, 15.2f, 6.8f)
            l.linea(8.8f, 13.2f, 15.2f, 17.2f)
            l.circulo(17f, 5.6f, 2.5f, relleno = false)
            l.circulo(6.6f, 12f, 2.5f, relleno = false)
            l.circulo(17f, 18.4f, 2.5f, relleno = false)
        }
        Glifo.Papelera -> {
            l.linea(4.2f, 7.2f, 19.8f, 7.2f)
            l.lineas(9.6f, 7.2f, 9.6f, 5.1f, 14.4f, 5.1f, 14.4f, 7.2f)
            l.lineas(6.6f, 7.2f, 7.6f, 20.2f, 16.4f, 20.2f, 17.4f, 7.2f)
            l.linea(10.4f, 10.6f, 10.8f, 17f)
            l.linea(13.6f, 10.6f, 13.2f, 17f)
        }
        // Terminal: el registro de diagnostico.
        Glifo.Terminal -> {
            l.marco(3.8f, 4.2f, 20.2f, 19.8f, 3.2f)
            l.lineas(7.6f, 9.4f, 10.2f, 12f, 7.6f, 14.6f)
            l.linea(12.6f, 15.6f, 16.6f, 15.6f)
        }
        Glifo.Atras -> l.lineas(14.6f, 5.4f, 8f, 12f, 14.6f, 18.6f)
        Glifo.Aviso -> {
            l.lineas(12f, 3.6f, 21.4f, 19.6f, 2.6f, 19.6f, cerrado = true)
            l.linea(12f, 9.2f, 12f, 14.4f)
            l.circulo(12f, 17f, 1.1f)
        }
        Glifo.Reloj -> {
            l.circulo(12f, 12f, 8.4f, relleno = false)
            l.linea(12f, 7.2f, 12f, 12.4f)
            l.linea(12f, 12.4f, 15.8f, 14.4f)
        }
        Glifo.Bateria -> {
            l.marco(2.8f, 8f, 20.2f, 16f, 2.6f)
            l.bloque(4.4f, 9.6f, 13.2f, 14.4f, 1.4f)
            l.bloque(21f, 10.8f, 22.4f, 13.2f, 0.8f)
        }
    }
}

/**
 * Traductor de la reticula 24x24 al Canvas real: centra el dibujo si el Canvas
 * no es cuadrado y aplica la escala en un solo sitio.
 */
private class Lapiz(private val d: DrawScope, val color: Color, trazo: Float) {
    private val s = min(d.size.width, d.size.height) / 24f
    private val ox = (d.size.width - 24f * s) / 2f
    private val oy = (d.size.height - 24f * s) / 2f
    private val grosor = trazo * s

    private fun pt(x: Float, y: Float) = Offset(ox + x * s, oy + y * s)

    fun linea(
        x1: Float, y1: Float, x2: Float, y2: Float,
        color: Color = this.color,
        grosor: Float = this.grosor,
    ) {
        d.drawLine(color, pt(x1, y1), pt(x2, y2), strokeWidth = grosor, cap = StrokeCap.Round)
    }

    /** Polilinea (pares x,y) con esquinas y extremos redondeados. */
    fun lineas(
        vararg puntos: Float,
        cerrado: Boolean = false,
        color: Color = this.color,
        grosor: Float = this.grosor,
    ) {
        val p = Path()
        var i = 0
        while (i + 1 < puntos.size) {
            val o = pt(puntos[i], puntos[i + 1])
            if (i == 0) p.moveTo(o.x, o.y) else p.lineTo(o.x, o.y)
            i += 2
        }
        if (cerrado) p.close()
        d.drawPath(p, color, style = Stroke(width = grosor, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    /** Poligono cerrado y relleno. */
    fun relleno(vararg puntos: Float, color: Color = this.color) {
        val p = Path()
        var i = 0
        while (i + 1 < puntos.size) {
            val o = pt(puntos[i], puntos[i + 1])
            if (i == 0) p.moveTo(o.x, o.y) else p.lineTo(o.x, o.y)
            i += 2
        }
        p.close()
        d.drawPath(p, color)
    }

    fun circulo(x: Float, y: Float, r: Float, relleno: Boolean = true, color: Color = this.color) {
        val o = pt(x, y)
        if (relleno) d.drawCircle(color, r * s, o)
        else d.drawCircle(color, r * s, o, style = Stroke(width = grosor))
    }

    fun bloque(x1: Float, y1: Float, x2: Float, y2: Float, radio: Float, color: Color = this.color) {
        d.drawRoundRect(
            color = color,
            topLeft = pt(x1, y1),
            size = Size((x2 - x1) * s, (y2 - y1) * s),
            cornerRadius = CornerRadius(radio * s),
        )
    }

    fun marco(
        x1: Float, y1: Float, x2: Float, y2: Float, radio: Float,
        color: Color = this.color,
        grosor: Float = this.grosor,
    ) {
        d.drawRoundRect(
            color = color,
            topLeft = pt(x1, y1),
            size = Size((x2 - x1) * s, (y2 - y1) * s),
            cornerRadius = CornerRadius(radio * s),
            style = Stroke(width = grosor),
        )
    }

    fun arco(
        cx: Float, cy: Float, r: Float, inicio: Float, barrido: Float,
        color: Color = this.color,
        grosor: Float = this.grosor,
    ) {
        val c = pt(cx, cy)
        d.drawArc(
            color = color,
            startAngle = inicio,
            sweepAngle = barrido,
            useCenter = false,
            topLeft = Offset(c.x - r * s, c.y - r * s),
            size = Size(2f * r * s, 2f * r * s),
            style = Stroke(width = grosor, cap = StrokeCap.Round),
        )
    }

    /**
     * Punta de flecha en el extremo [angulo] de un arco, orientada en el
     * sentido de avance del arco (horario en pantalla).
     */
    fun puntaDeFlecha(
        cx: Float, cy: Float, r: Float, angulo: Float,
        tamano: Float = 2.9f,
        color: Color = this.color,
    ) {
        val a = angulo * PI / 180.0
        val px = (cx + r * cos(a)).toFloat()
        val py = (cy + r * sin(a)).toFloat()
        val tx = (-sin(a)).toFloat()
        val ty = cos(a).toFloat()
        val nx = -ty
        val ny = tx
        val punta = pt(px + tx * tamano, py + ty * tamano)
        val b1 = pt(px + nx * tamano * 0.8f, py + ny * tamano * 0.8f)
        val b2 = pt(px - nx * tamano * 0.8f, py - ny * tamano * 0.8f)
        val p = Path().apply {
            moveTo(punta.x, punta.y)
            lineTo(b1.x, b1.y)
            lineTo(b2.x, b2.y)
            close()
        }
        d.drawPath(p, color)
    }
}
