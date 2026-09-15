package org.parkjw.apps.tt

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings as AndroidSettings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.math.roundToInt

/**
 * Foreground service that listens to [Sensor.TYPE_HINGE_ANGLE] and launches the configured
 * action whenever the quick bend-and-reopen gesture completes. Runs while settings.enabled.
 */
class HingeService : Service(), SensorEventListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var sensorManager: SensorManager
    private lateinit var powerManager: PowerManager
    private lateinit var notifyManager: NotificationManager
    private val detector = GestureDetector()

    @Volatile
    private var settings = Settings()

    @Volatile
    private var isForeground = false

    private var screenWasOff = false
    private var hingeSensor: Sensor? = null

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SensorManager::class.java)
        powerManager = getSystemService(PowerManager::class.java)
        notifyManager = getSystemService(NotificationManager::class.java)
        hingeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
        createChannels()
        scope.launch {
            SettingsRepository(applicationContext).settings.collect { updated ->
                settings = updated
                if (isForeground) notifyManager.notify(NOTIFICATION_ID, buildNotification(updated))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(settings), type)
        isForeground = true

        // Re-read after startForeground: the UI toggle writes before starting us, and a
        // stale first emission must never stopSelf() ahead of startForeground (FGS timeout crash).
        val current = runBlocking { SettingsRepository(applicationContext).settings.first() }
        settings = current
        if (!current.enabled) {
            stopSelf()
            return START_NOT_STICKY
        }

        // ADB-only test hook so the launch path can be exercised without the gesture.
        if (intent?.getStringExtra(EXTRA_DEBUG) == "trigger") {
            Log.i(TAG, "debug trigger")
            launchConfigured(settings)
        }
        intent?.getStringExtra(EXTRA_LIST_PKG)?.let { pkg ->
            val list = ShortcutReader.shortcutsFor(this, pkg)
            if (list.isEmpty()) Log.i(TAG, "shortcuts: none for $pkg")
            list.forEach { Log.i(TAG, "shortcut: id=${it.id} | label=${it.label} | ${it.intent}") }
        }
        val sensor = hingeSensor
        if (sensor == null) {
            // Not a foldable (or no hinge sensor): nothing to listen to.
            stopSelf()
        } else {
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onSensorChanged(event: SensorEvent) {
        val s = settings
        if (!s.enabled || !s.isActionReady) return
        // Only arm/trigger while the screen is on, so pocket movements never launch anything.
        if (!powerManager.isInteractive) {
            screenWasOff = true
            return
        }
        if (screenWasOff) {
            detector.reset()
            screenWasOff = false
        }
        if (detector.onAngle(event.values[0], SystemClock.elapsedRealtime(), GestureConfig.STANDARD)) {
            launchConfigured(s)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        scope.cancel()
        isForeground = false
        super.onDestroy()
    }

    private fun launchConfigured(s: Settings) {
        if (!AndroidSettings.canDrawOverlays(this)) {
            // Background activity launches need the overlay permission; ask for it instead of failing silently.
            notifyProblem(
                title = "Permission needed",
                text = "Allow \u201cDisplay over other apps\u201d so TT can launch ${ActionLauncher.describe(s)}.",
                intent = overlaySettingsIntent(),
            )
            return
        }
        val intent = ActionLauncher.buildIntent(this, s)
        if (intent == null) {
            notifyProblem(
                title = "TT could not launch the action",
                text = "The configured app or link could not be resolved. Open TT to fix it.",
                intent = MainActivity.intent(this),
            )
            return
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            notifyProblem(
                title = "TT could not launch the action",
                text = e.message ?: "Unknown error",
                intent = MainActivity.intent(this),
            )
        }
    }

    private fun overlaySettingsIntent(): Intent =
        Intent(
            AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun createChannels() {
        notifyManager.createNotificationChannel(
            NotificationChannel(CHANNEL_MONITORING, "Gesture listener", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Persistent notification while TT watches the hinge"
                setShowBadge(false)
            }
        )
        notifyManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Permission requests and launch failures"
            }
        )
    }

    private fun buildNotification(s: Settings): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0, MainActivity.intent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_MONITORING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("TT is listening")
            .setContentText(
                if (s.isActionReady) {
                    "Bend past 90\u00b0 and reopen \u2192 ${ActionLauncher.describe(s)}"
                } else {
                    "Pick an app or link in TT to arm the gesture"
                }
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun notifyProblem(title: String, text: String, intent: Intent) {
        val contentIntent = PendingIntent.getActivity(
            this, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        notifyManager.notify(
            ALERT_NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .build(),
        )
    }

    companion object {
        private const val TAG = "TT"
        private const val CHANNEL_MONITORING = "tt_monitoring"
        private const val CHANNEL_ALERTS = "tt_alerts"
        private const val NOTIFICATION_ID = 1
        private const val ALERT_NOTIFICATION_ID = 2

        private const val EXTRA_DEBUG = "debug"
        private const val EXTRA_LIST_PKG = "list_shortcuts_pkg"
    }
}
