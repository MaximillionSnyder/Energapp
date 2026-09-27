plugins {
    // AGP 9+ trae soporte de Kotlin incorporado: no hace falta el plugin
    // org.jetbrains.kotlin.android (aplicarlo falla con AGP 9.0+).
    id("com.android.application") version "9.3.0" apply false
}
