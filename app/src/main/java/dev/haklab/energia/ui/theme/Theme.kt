package dev.haklab.energia.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Paleta de respaldo. El acento es un verde-azulado "energia" y el resto se
 * deriva de los roles de Material 3, de modo que claro/oscuro y contraste
 * salgan coherentes sin definir cada color a mano.
 */
private val Verde = Color(0xFF00696D)
private val VerdeClaro = Color(0xFF4FD8DE)
private val Ambar = Color(0xFF6D5E00)
private val AmbarClaro = Color(0xFFE9C400)

private val EsquemaClaro = lightColorScheme(
    primary = Verde,
    secondary = Ambar,
    tertiary = Color(0xFF4A5F8E),
)

private val EsquemaOscuro = darkColorScheme(
    primary = VerdeClaro,
    secondary = AmbarClaro,
    tertiary = Color(0xFFB1C6F9),
)

/**
 * Tema de la app.
 *
 * @param dynamicColor usa la paleta del fondo de pantalla (Material You).
 *        Solo existe desde Android 12 (API 31), por eso se comprueba la
 *        version en vez de asumirla.
 */
@Composable
fun EnergiaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> EsquemaOscuro
        else -> EsquemaClaro
    }
    MaterialTheme(colorScheme = colors, content = content)
}
