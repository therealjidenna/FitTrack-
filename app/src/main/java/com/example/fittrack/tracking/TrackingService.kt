package com.example.fittrack.tracking

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.fittrack.data.AppDatabase
import com.example.fittrack.data.Workout
import com.google.android.gms.location.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Live state the UI observes. */
object TrackingState {
    val points = MutableStateFlow<List<Location>>(emptyList())
    val steps = MutableStateFlow(0)
    val distanceMeters = MutableStateFlow(0f)
    val startTime = MutableStateFlow(0L)
    val isTracking = MutableStateFlow(false)
}

class TrackingService : Service(), SensorEventListener {

    private lateinit var fused: FusedLocationProviderClient
    private lateinit var sensors: SensorManager
    private var baseSteps = -1f

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (loc in result.locations) {
                if (loc.accuracy > 30f) continue // skip jittery fixes
                val list = TrackingState.points.value
                list.lastOrNull()?.let {
                    TrackingState.distanceMeters.value += it.distanceTo(loc)
                }
                TrackingState.points.value = list + loc
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
        sensors = getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTracking()
            return START_NOT_STICKY
        }
        startForeground(1, buildNotification())
        if (!TrackingState.isTracking.value) startTracking()
        return START_STICKY
    }

    @Suppress("MissingPermission")
    private fun startTracking() {
        TrackingState.points.value = emptyList()
        TrackingState.steps.value = 0
        TrackingState.distanceMeters.value = 0f
        TrackingState.startTime.value = System.currentTimeMillis()
        TrackingState.isTracking.value = true
        baseSteps = -1f

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L)
            .setMinUpdateDistanceMeters(3f)
            .build()
        fused.requestLocationUpdates(request, callback, Looper.getMainLooper())

        sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let {
            sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    private fun stopTracking() {
        fused.removeLocationUpdates(callback)
        sensors.unregisterListener(this)

        val pts = TrackingState.points.value
        val workout = Workout(
            startTime = TrackingState.startTime.value,
            endTime = System.currentTimeMillis(),
            steps = TrackingState.steps.value,
            distanceMeters = TrackingState.distanceMeters.value,
            route = pts.joinToString(";") { "${it.latitude},${it.longitude}" }
        )
        if (pts.size > 1 || workout.steps > 0) {
            val appContext = applicationContext
            CoroutineScope(Dispatchers.IO).launch {
                AppDatabase.get(appContext).dao().insert(workout)
            }
        }

        TrackingState.isTracking.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (baseSteps < 0) baseSteps = e.values[0]
        TrackingState.steps.value = (e.values[0] - baseSteps).toInt()
    }

    override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Workout tracking", NotificationManager.IMPORTANCE_LOW)
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Tracking your workout")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "fittrack.STOP"
        private const val CHANNEL = "tracking"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, TrackingService::class.java)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, TrackingService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
