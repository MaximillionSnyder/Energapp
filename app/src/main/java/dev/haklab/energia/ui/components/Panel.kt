package dev.haklab.energia.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.haklab.energia.ui.glyphs.Glifo

/*
 * Piezas del lenguaje visual: un panel es una superficie elevada con un filo
 * de 1 dp y una etiqueta en mayusculas; dentro se colocan tiles, cifras y
 * barras. Todo deriva del tema, no hay colores ni formas sueltas.
 */

/** Superficie base. La etiqueta es opcional y siempre en mayusculas pequenas. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    titulo: String? = null,
    acento: Color? = null,
    color: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    contenido: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = color,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(18.dp)) {
            if (titulo != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (acento != null) {
                        Box(
                            Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(acento),
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        text = titulo.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(14.dp))
            }
            contenido()
        }
    }
}

/** Cifra grande con unidad opcional al pie, alineada por la base. */
@Composable
fun Cifra(
    valor: String,
    modifier: Modifier = Modifier,
    unidad: String? = null,
    etiqueta: String? = null,
    estilo: TextStyle = MaterialTheme.typography.displayMedium,
    color: Color = Color.Unspecified,
) {
    Column(modifier) {
        if (etiqueta != null) {
            Text(
                text = etiqueta.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = valor,
                style = estilo,
                fontWeight = FontWeight.Bold,
                color = color,
            )
            if (unidad != null) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = unidad,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 5.dp),
                )
            }
        }
    }
}

/** Dato compacto: etiqueta, valor y nota opcional. Pensado para ir en rejilla. */
@Composable
fun Tile(
    etiqueta: String,
    valor: String,
    modifier: Modifier = Modifier,
    nota: String? = null,
    acento: Color? = null,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (acento != null) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(acento),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = etiqueta.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = valor,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (nota != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = nota,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Pastilla de estado. Con [pulso] anade un punto que late: solo debe usarse
 * cuando la medicion esta activa, para no animar sin motivo.
 */
@Composable
fun Pastilla(
    texto: String,
    color: Color,
    modifier: Modifier = Modifier,
    glifo: Glifo? = null,
    pulso: Boolean = false,
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = color.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (pulso) {
                val transicion = rememberInfiniteTransition(label = "pulso")
                val alfa by transicion.animateFloat(
                    initialValue = 1f,
                    targetValue = 0.25f,
                    animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
                    label = "alfa",
                )
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(color.copy(alpha = alfa)),
                )
                Spacer(Modifier.width(8.dp))
            } else if (glifo != null) {
                Glifo(glifo, Modifier.size(14.dp), color = color, trazo = 2.4f)
                Spacer(Modifier.width(7.dp))
            }
            Text(
                text = texto,
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
        }
    }
}

/** Barra de proporcion con relleno animado. */
@Composable
fun BarraProporcion(
    fraccion: Float,
    color: Color,
    modifier: Modifier = Modifier,
    alto: Dp = 10.dp,
) {
    val animada by animateFloatAsState(
        targetValue = fraccion.coerceIn(0f, 1f),
        animationSpec = tween(600),
        label = "barra",
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(alto)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(animada)
                .clip(CircleShape)
                .background(color),
        )
    }
}

/** Fila etiqueta/valor para los paneles de datos. */
@Composable
fun PuntoLatido(
    color: Color,
    activo: Boolean,
    modifier: Modifier = Modifier,
    tamano: Dp = 8.dp,
) {
    val alfa = if (activo) {
        val transicion = rememberInfiniteTransition(label = "latido")
        val animada by transicion.animateFloat(
            initialValue = 1f,
            targetValue = 0.3f,
            animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
            label = "alfa-latido",
        )
        animada
    } else {
        0.45f
    }
    Box(
        modifier
            .size(tamano)
            .clip(CircleShape)
            .background(color.copy(alpha = alfa)),
    )
}

/** Aviso en linea: glifo, texto y color semantico. */
@Composable
fun Nota(
    texto: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.secondary,
    glifo: Glifo = Glifo.Aviso,
) {
    Row(modifier, verticalAlignment = Alignment.Top) {
        Glifo(glifo, Modifier.padding(top = 2.dp).size(15.dp), color = color, trazo = 2.1f)
        Spacer(Modifier.width(8.dp))
        Text(
            text = texto,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
