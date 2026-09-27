package dev.haklab.energia

import android.app.Application
import dev.haklab.energia.diag.Diag

/**
 * Punto de entrada del proceso: instala la telemetria local antes de que
 * arranque cualquier componente, de modo que hasta un fallo temprano de
 * arranque quede registrado.
 */
class EnergiaApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Diag.init(this)
        Diag.installCrashHandler()
    }
}
