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
import com.arcu.attnd.domain.GeoPoint
import com.arcu.attnd.domain.Geo
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
                }
            }
        }
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
                .addOnFailureListener { running = false }
        } catch (_: Exception) {
            // No Play services or a revoked permission: no distances.
            running = false
        }
    }

    private fun stop() {
        if (!running) return
        running = false
        try {
            client.removeLocationUpdates(callback)
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val UPDATE_INTERVAL_MS = 5_000L
        private const val MIN_UPDATE_INTERVAL_MS = 2_000L
        private const val MIN_UPDATE_DISTANCE_M = 5f
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
        try {
            val request = DeviceOrientationRequest.Builder(SAMPLING_PERIOD_US).build()
            LocationServices.getFusedOrientationProviderClient(appContext)
                .requestOrientationUpdates(request, ContextCompat.getMainExecutor(appContext), fusedListener)
                .addOnFailureListener { if (mode == Mode.FUSED) startSensor() }
        } catch (_: Exception) {
            startSensor()
        }
    }

    private fun startSensor() {
        stopFused()
        val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sensor == null || users == 0) {
            mode = Mode.STOPPED
            return
        }
        mode = Mode.SENSOR
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
        } catch (_: Exception) {
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
