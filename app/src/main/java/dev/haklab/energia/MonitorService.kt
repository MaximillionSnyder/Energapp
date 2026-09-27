package dev.haklab.energia

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import dev.haklab.energia.data.EnergyRepository
import dev.haklab.energia.diag.Diag
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Servicio de monitoreo continuo.
 *
 * En Android 14 todo foreground service debe declarar un tipo; este usa
 * `specialUse` (el sistema no tiene una categoria para "medicion de energia").
 * El proceso se mantiene vivo con una notificacion permanente y un wake lock
 * parcial: sin ellos el sistema puede cachear el proceso y dejar de entregar
 * los broadcasts de bateria.
 */
class MonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loop: Job? = null
    private lateinit var sampler: BatterySampler
    private lateinit var wakeLock: PowerManager.WakeLock

    /** Telemetria de salud: el servicio no registra ticks normales, solo anomalias. */
    private var intervalMs: Long = DEFAULT_INTERVAL_MS
    private var lastTickMs = 0L
    private var prevDiscardedMs = 0L
    private var warnedNoHardware = false
    private var enCarga = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        sampler = BatterySampler(this)
        sampler.start()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "energia:monitor").apply {
            setReferenceCounted(false)
            acquire()
        }
        createChannel()
        Diag.info("servicio", "servicio creado")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Diag.info("servicio", "parada solicitada por el usuario")
                stopSelf()
                return START_NOT_STICKY
            }
        }
        val intervalMs = intent?.getLongExtra(EXTRA_INTERVAL_MS, DEFAULT_INTERVAL_MS)
            ?: DEFAULT_INTERVAL_MS
        this.intervalMs = intervalMs
        EnergyRepository.setRunning(true, intervalMs)
        startForeground(NOTIFICATION_ID, buildNotification())
        restartLoop(intervalMs)
        Diag.sessionStarted()
        Diag.info("servicio", "medicion iniciada con intervalo de ${intervalMs / 1000} s")
        return START_STICKY
    }

    private fun restartLoop(intervalMs: Long) {
        loop?.cancel()
        EnergyRepository.setRunning(true, intervalMs)
        loop = scope.launch {
            while (isActive) {
                tick()
                delay(intervalMs)
            }
        }
    }

    /** Una lectura + integracion + publicacion de estado. */
    private fun tick() {
        val now = System.currentTimeMillis()
        try {
            if (lastTickMs > 0) {
                val transcurrido = now - lastTickMs
                if (transcurrido > intervalMs * 2 + 2_000) {
                    Diag.warn(
                        "salud",
                        "tick retrasado: ${transcurrido / 1000} s desde el anterior " +
                            "(esperado ~${intervalMs / 1000} s)",
                    )
                }
            }
            lastTickMs = now

            val sample = sampler.sample(now)
            val dE = model.add(sample)

            if (sample.isCharging && !enCarga) {
                // Empezo a cargar: la acumulacion anterior no es comparable.
                enCarga = true
                Diag.info("sesion", "empezo a cargar: se reinicia la acumulacion")
                model.reset()
                EnergyRepository.reset(now)
            } else if (!sample.isCharging && enCarga) {
                enCarga = false
                Diag.info("sesion", "carga terminada: se reanuda la medicion")
            }

            if (model.discardedMillis < prevDiscardedMs) {
                // El modelo se reinicio (p. ej. por carga).
                prevDiscardedMs = model.discardedMillis
            } else if (!enCarga && model.discardedMillis > prevDiscardedMs) {
                val delta = model.discardedMillis - prevDiscardedMs
                Diag.warn(
                    "muestreo",
                    "tramo descartado de ${delta / 1000} s: ${model.lastDiscardReason ?: "motivo desconocido"}",
                )
                prevDiscardedMs = model.discardedMillis
            }

            if (!sampler.hasHardwareCurrent() && !warnedNoHardware) {
                warnedNoHardware = true
                Diag.warn("lectura", "sin medidor de corriente por hardware: se usara el nivel (baja precision)")
            }

            EnergyRepository.publish(
                sample = sample,
                energyJ = model.energyJ,
                chargeMah = model.chargeMah,
                validMs = model.validMillis,
                discardedMs = model.discardedMillis,
                sampleCount = model.sampleCount,
                hasGaps = model.hasGaps,
                avgMw = model.averageMw(),
                percentOfBattery = model.percentOfBattery(),
                fullCapacityMah = model.estimatedFullCapacityMah(sample),
                hasHardwareCurrent = sampler.hasHardwareCurrent(),
                screen = model.screenLedger(),
                deltaJ = if (enCarga) 0.0 else model.lastDeltaJ,
            )
            updateNotification()
        } catch (t: Throwable) {
            // Un fallo en un tick no debe matar el servicio: se registra y se
            // reintenta en el siguiente intervalo.
            Diag.error("tick", "fallo en el ciclo de medicion", t)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.channel_desc) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val s = EnergyRepository.state.value
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = if (s.sample?.dischargeMa?.isNaN() != false) {
            getString(R.string.notif_no_current)
        } else {
            getString(R.string.notif_text, s.avgMw, s.percentOfBattery)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(null, getString(R.string.action_stop), stop).build()
            )
            .build()
    }

    private fun updateNotification() {
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification())
        }
    }

    override fun onDestroy() {
        loop?.cancel()
        scope.cancel()
        sampler.stop()
        if (::wakeLock.isInitialized && wakeLock.isHeld) wakeLock.release()
        EnergyRepository.setRunning(false)
        Diag.info("servicio", "servicio detenido")
        Diag.sessionStopped()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "dev.haklab.energia.STOP"
        const val EXTRA_INTERVAL_MS = "interval_ms"
        const val DEFAULT_INTERVAL_MS = 5_000L
        private const val CHANNEL_ID = "energia_monitor"
        private const val NOTIFICATION_ID = 42

        private val model = EnergyModel()

        fun resetSession() {
            model.reset()
            EnergyRepository.reset(System.currentTimeMillis())
        }

        fun start(context: Context, intervalMs: Long = DEFAULT_INTERVAL_MS) {
            val i = Intent(context, MonitorService::class.java)
                .putExtra(EXTRA_INTERVAL_MS, intervalMs)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, MonitorService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
