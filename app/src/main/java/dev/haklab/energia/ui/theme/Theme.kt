package dev.haklab.energia.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Identidad visual "panel de instrumentos": fondo casi negro, superficies
 * apiladas con un filo de 1 dp, un acento aqua electrico para la energia y
 * ambar para la pantalla apagada (la semantica se conserva de la 2.0: el color
 * dice en que estado se gasto la energia).
 *
 * La paleta es fija, no Material You: el acento es parte de la lectura del
 * instrumento y no puede depender del fondo de pantalla del telefono.
 */

private val AquaOscuro = Color(0xFF46E8C8)
private val AquaClaro = Color(0xFF00685C)
private val AmbarOscuro = Color(0xFFFFC857)
private val AmbarClaro = Color(0xFF8A6100)

private val EsquemaOscuro = darkColorScheme(
    primary = AquaOscuro,
    onPrimary = Color(0xFF00332C),
    primaryContainer = Color(0xFF0C4A41),
    onPrimaryContainer = Color(0xFFA9F5E4),
    secondary = AmbarOscuro,
    onSecondary = Color(0xFF3B2B00),
    secondaryContainer = Color(0xFF4C3A00),
    onSecondaryContainer = Color(0xFFFFE1A6),
    tertiary = Color(0xFF8CB8FF),
    onTertiary = Color(0xFF002B60),
    tertiaryContainer = Color(0xFF17406E),
    onTertiaryContainer = Color(0xFFD3E3FF),
    background = Color(0xFF07090A),
    onBackground = Color(0xFFE7EEEF),
    surface = Color(0xFF0C1012),
    onSurface = Color(0xFFE7EEEF),
    surfaceVariant = Color(0xFF161C1F),
    onSurfaceVariant = Color(0xFF93A1A6),
    surfaceContainerLowest = Color(0xFF050708),
    surfaceContainerLow = Color(0xFF101416),
    surfaceContainer = Color(0xFF141A1C),
    surfaceContainerHigh = Color(0xFF1A2124),
    surfaceContainerHighest = Color(0xFF202A2D),
    outline = Color(0xFF38454A),
    outlineVariant = Color(0xFF212B2F),
    error = Color(0xFFFF7A7A),
    onError = Color(0xFF40060A),
    errorContainer = Color(0xFF5A1B1B),
    onErrorContainer = Color(0xFFFFD9D9),
)

private val EsquemaClaro = lightColorScheme(
    primary = AquaClaro,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF9FF2E0),
    onPrimaryContainer = Color(0xFF00241F),
    secondary = AmbarClaro,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFE2A8),
    onSecondaryContainer = Color(0xFF2B1D00),
    tertiary = Color(0xFF3D5F9E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD8E2FF),
    onTertiaryContainer = Color(0xFF001A42),
    background = Color(0xFFF5F8F7),
    onBackground = Color(0xFF10191B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF10191B),
    surfaceVariant = Color(0xFFE6EDEB),
    onSurfaceVariant = Color(0xFF4E5C60),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7FAF9),
    surfaceContainer = Color(0xFFEFF4F2),
    surfaceContainerHigh = Color(0xFFE7EFEC),
    surfaceContainerHighest = Color(0xFFDEE8E5),
    outline = Color(0xFF6F7D80),
    outlineVariant = Color(0xFFC0CDC9),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

/**
 * Tipografia de instrumento: cifras con ancho tabular (`tnum`) para que no
 * bailen al cambiar de valor, tracking negativo en los titulares y tracking
 * ancho en las etiquetas pequenas (que se escriben en mayusculas).
 */
private val Tipografia: Typography = Typography().let { t ->
    t.copy(
        displayLarge = t.displayLarge.copy(
            fontWeight = FontWeight.Bold, letterSpacing = (-2).sp, fontFeatureSettings = "tnum",
        ),
        displayMedium = t.displayMedium.copy(
            fontWeight = FontWeight.Bold, letterSpacing = (-1.5).sp, fontFeatureSettings = "tnum",
        ),
        displaySmall = t.displaySmall.copy(
            fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp, fontFeatureSettings = "tnum",
        ),
        headlineMedium = t.headlineMedium.copy(
            fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp, fontFeatureSettings = "tnum",
        ),
        headlineSmall = t.headlineSmall.copy(
            fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp, fontFeatureSettings = "tnum",
        ),
        titleLarge = t.titleLarge.copy(
            fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp, fontFeatureSettings = "tnum",
        ),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
        bodyLarge = t.bodyLarge.copy(fontFeatureSettings = "tnum"),
        bodyMedium = t.bodyMedium.copy(fontFeatureSettings = "tnum"),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp),
        labelMedium = t.labelMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp),
        labelSmall = t.labelSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp),
    )
}

private val Formas = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/**
 * Tema de la app. Sin `dynamicColor`: la identidad del panel manda sobre la
 * paleta del fondo de pantalla. Claro y oscuro siguen al sistema.
 */
@Composable
fun EnergiaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) EsquemaOscuro else EsquemaClaro,
        typography = Tipografia,
        shapes = Formas,
        content = content,
    )
}
