package com.arcu.attnd.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Looper
import android.view.Display
import android.view.Surface
import androidx.core.content.ContextCompat
import com.arcu.attnd.domain.AppLog
import com.arcu.attnd.domain.Redact
import com.arcu.attnd.domain.Geo
import com.arcu.attnd.domain.GeoPoint
import com.arcu.attnd.domain.LogCategory
import com.arcu.attnd.domain.LogEvent
import com.arcu.attnd.domain.LogLevel
import com.google.android.gms.location.DeviceOrientation
import com.google.android.gms.location.DeviceOrientationListener
import com.google.android.gms.location.DeviceOrientationRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

/**
 * The user's position for distances, arrows, walk times and routes. Location updates run only while
 * at least one visible screen needs them ([acquire] / [release], reference counted on the main
 * thread) and only with the location permission; without it [location] is null.
 */
class UserLocationSource(context: Context) {

    private val appContext = context.applicationContext
    private val client by lazy { LocationServices.getFusedLocationProviderClient(appContext) }
    private val _location = MutableStateFlow<GeoPoint?>(null)
    val location: StateFlow<GeoPoint?> = _location

    /** Horizontal accuracy of the last fix in metres (null when unknown). */
    private val _accuracy = MutableStateFlow<Double?>(null)
    val accuracyMeters: StateFlow<Double?> = _accuracy
    private var users = 0
    private var running = false

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { fix ->
                GeoPoint.of(fix.latitude, fix.longitude)?.let { point ->
                    _location.value = point
                    _accuracy.value = if (fix.hasAccuracy()) fix.accuracy.toDouble() else null
                    logFix(fix, point)
                }
            }
        }
    }

    /** `location.fix` (trace), at most one per [FIX_LOG_INTERVAL_MS]; coordinates rounded by `Redact` (0.01°). */
    private var lastFixLoggedAt = 0L

    private fun logFix(fix: android.location.Location, point: GeoPoint) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastFixLoggedAt < FIX_LOG_INTERVAL_MS) return
        lastFixLoggedAt = now
        val ageS = (android.os.SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos) / 1_000_000_000
        AppLog.trace(
            LogCategory.LOCATION, LogEvent.LOCATION_FIX, "Location fix",
            mapOf(
                "accuracy_m" to if (fix.hasAccuracy()) fix.accuracy.toInt() else null,
                "age_s" to ageS, "lat" to Redact.coordinate(point.latitude), "lng" to Redact.coordinate(point.longitude),
            ),
        )
    }


    fun hasPermission(): Boolean = hasLocationPermission(appContext)

    /** Only "approximate" location was granted: distances are rough, arrows are hidden. */
    fun isApproximate(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED

    fun acquire() {
        users++
        start()
    }

    fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) stop()
    }

    @SuppressLint("MissingPermission") // checked in hasPermission()
    private fun start() {
        if (!hasPermission()) {
            _location.value = null
            stop()
            return
        }
        if (running) return
        running = true
        AppLog.debug(LogCategory.LOCATION, LogEvent.LOCATION_UPDATES, "Location updates started", mapOf("on" to true, "heading" to false, "consumers" to users))
        try {
            client.lastLocation.addOnSuccessListener { last ->
                if (last != null && _location.value == null) {
                    _location.value = GeoPoint.of(last.latitude, last.longitude)
                    _accuracy.value = if (last.hasAccuracy()) last.accuracy.toDouble() else null
                }
            }
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS)
                .setMinUpdateIntervalMillis(MIN_UPDATE_INTERVAL_MS)
                .setMinUpdateDistanceMeters(MIN_UPDATE_DISTANCE_M)
                .build()
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
                .addOnFailureListener {
                    running = false
                    locationError(it)
                }
        } catch (e: Exception) {
            // No Play services or a revoked permission: no distances.
            running = false
            locationError(e)
        }
    }

    private fun locationError(e: Exception) {
        AppLog.warning(LogCategory.LOCATION, LogEvent.LOCATION_ERROR, "Location updates failed", mapOf("error" to AppLog.errorKind(e), "domain" to e.javaClass.simpleName))
    }

    private fun stop() {
        if (!running) return
        running = false
        AppLog.debug(LogCategory.LOCATION, LogEvent.LOCATION_UPDATES, "Location updates stopped", mapOf("on" to false, "heading" to false, "consumers" to users))
        try {
            client.removeLocationUpdates(callback)
        } catch (e: Exception) {
            AppLog.caught(LogCategory.LOCATION, "location.stop", e, LogLevel.WARNING)
        }
    }

    companion object {
        private const val UPDATE_INTERVAL_MS = 5_000L
        private const val MIN_UPDATE_INTERVAL_MS = 2_000L
        private const val MIN_UPDATE_DISTANCE_M = 5f
        private const val FIX_LOG_INTERVAL_MS = 30_000L
    }
}

fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/**
 * The device's heading in degrees clockwise from true north, corrected for the display rotation,
 * for the direction arrows. Uses Play services' FusedOrientationProviderClient (fused, true north)
 * and falls back to the rotation-vector sensor (magnetic north + the declination at the user's
 * position when known) where it is unavailable. Runs only while a screen showing arrows is visible
 * ([acquire] / [release]); about 5 updates a second. Null while stopped or without a sensor.
 */
class HeadingSource(context: Context, private val userLocation: UserLocationSource) {

    private val appContext = context.applicationContext
    private val _heading = MutableStateFlow<Float?>(null)
    val heading: StateFlow<Float?> = _heading

    /** Heading accuracy in degrees; negative = unreliable (the arrow is hidden), null = unknown. */
    private val _accuracy = MutableStateFlow<Double?>(null)
    val accuracyDegrees: StateFlow<Double?> = _accuracy
    private var users = 0
    private var mode = Mode.STOPPED

    private enum class Mode { STOPPED, FUSED, SENSOR }

    private val fusedListener = DeviceOrientationListener { orientation: DeviceOrientation ->
        if (mode == Mode.FUSED) {
            val error = orientation.headingErrorDegrees.toDouble()
            _accuracy.value = if (error.isFinite() && error >= 0) error else -1.0
            publish(orientation.headingDegrees.toDouble())
        }
    }

    private val sensorManager by lazy { appContext.getSystemService(SensorManager::class.java) }
    private val rotation = FloatArray(9)
    private val angles = FloatArray(3)
    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (mode != Mode.SENSOR) return
            SensorManager.getRotationMatrixFromVector(rotation, event.values)
            SensorManager.getOrientation(rotation, angles)
            val magnetic = Math.toDegrees(angles[0].toDouble())
            publish(magnetic + declination())
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            if (mode != Mode.SENSOR) return
            // Rough degrees for the sensor's status; unreliable (needs calibration) hides the arrow.
            _accuracy.value = when (accuracy) {
                SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> 10.0
                SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> 20.0
                SensorManager.SENSOR_STATUS_ACCURACY_LOW -> 35.0
                else -> -1.0
            }
        }
    }

    fun acquire() {
        users++
        if (mode == Mode.STOPPED) startFused()
    }

    fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) stop()
    }

    private fun startFused() {
        mode = Mode.FUSED
        AppLog.debug(LogCategory.LOCATION, LogEvent.HEADING_SOURCE, "Heading: fused orientation", mapOf("source" to "fused_orientation"))
        try {
            val request = DeviceOrientationRequest.Builder(SAMPLING_PERIOD_US).build()
            LocationServices.getFusedOrientationProviderClient(appContext)
                .requestOrientationUpdates(request, ContextCompat.getMainExecutor(appContext), fusedListener)
                .addOnFailureListener {
                    AppLog.caught(LogCategory.LOCATION, "heading.fused", it, LogLevel.WARNING)
                    if (mode == Mode.FUSED) startSensor()
                }
        } catch (e: Exception) {
            AppLog.caught(LogCategory.LOCATION, "heading.fused", e, LogLevel.WARNING)
            startSensor()
        }
    }

    private fun startSensor() {
        stopFused()
        val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sensor == null || users == 0) {
            mode = Mode.STOPPED
            if (sensor == null) AppLog.info(LogCategory.LOCATION, LogEvent.LOCATION_ERROR, "No heading sensor: no arrows", mapOf("error" to "no_sensor"))
            return
        }
        mode = Mode.SENSOR
        AppLog.debug(LogCategory.LOCATION, LogEvent.HEADING_SOURCE, "Heading: rotation-vector sensor", mapOf("source" to "rotation_vector"))
        sensorManager?.registerListener(sensorListener, sensor, SAMPLING_PERIOD_US.toInt())
    }

    private fun stop() {
        when (mode) {
            Mode.FUSED -> stopFused()
            Mode.SENSOR -> sensorManager?.unregisterListener(sensorListener)
            Mode.STOPPED -> Unit
        }
        mode = Mode.STOPPED
        _heading.value = null
        _accuracy.value = null
    }

    private fun stopFused() {
        try {
            LocationServices.getFusedOrientationProviderClient(appContext).removeOrientationUpdates(fusedListener)
        } catch (e: Exception) {
            AppLog.caught(LogCategory.LOCATION, "heading.stop", e, LogLevel.WARNING)
        }
    }

    /** Publishes a new heading when it moved by at least a degree (fewer recompositions). */
    private fun publish(deviceHeading: Double) {
        if (!deviceHeading.isFinite()) return
        val heading = Geo.normalize(deviceHeading + displayRotationDegrees()).toFloat()
        val previous = _heading.value
        if (previous == null || abs(Geo.unwrap(previous.toDouble(), heading.toDouble()) - previous) >= 1.0) {
            _heading.value = heading
        }
    }

    private fun declination(): Double {
        val point = userLocation.location.value ?: return 0.0
        return GeomagneticField(point.latitude.toFloat(), point.longitude.toFloat(), 0f, System.currentTimeMillis())
            .declination.toDouble()
    }

    /** The arrow is drawn on the screen, so a phone in landscape adds 90° / 270°. */
    private fun displayRotationDegrees(): Int {
        val display = appContext.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        return when (display?.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    companion object {
        /** ~5 Hz. */
        private const val SAMPLING_PERIOD_US = 200_000L
    }
}
