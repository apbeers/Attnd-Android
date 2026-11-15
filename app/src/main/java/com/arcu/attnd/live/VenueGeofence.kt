package com.arcu.attnd.live

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.arcu.attnd.domain.EventBundle
import com.arcu.attnd.domain.LiveFollow
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import java.time.Duration
import java.time.Instant

/**
 * A Play services geofence around the venue ([LiveFollow.venueFence]) that starts / refreshes the Live
 * Update when the attendee arrives (shared spec §8). Geofences only fire with
 * `ACCESS_BACKGROUND_LOCATION` granted (Android 10+), which this app doesn't request today: declaring it
 * needs a Play Console background-location declaration. Until then [ensure] does nothing and the
 * Live Update starts on its schedule. Re-registered after a reboot (the receiver refreshes on
 * BOOT_COMPLETED); removed when following stops or the event is left.
 */
class VenueGeofence(private val context: Context) {

    private var registeredKey: String? = null

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, LiveUpdateReceiver::class.java).setAction(LiveUpdateReceiver.ACTION_GEOFENCE),
        // Play services fills in the transition, so the intent must be mutable.
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    private fun canUse(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // checked by canUse()
    fun ensure(bundle: EventBundle) {
        if (!canUse()) return
        val (centre, radius) = LiveFollow.venueFence(bundle) ?: return clear()
        val key = "${bundle.event.id}:${centre.latitude},${centre.longitude}:${radius.toInt()}"
        if (key == registeredKey) return
        val end = bundle.event.endTime?.toInstant() ?: Instant.now().plus(Duration.ofDays(1))
        val expiresIn = Duration.between(Instant.now(), end.plus(Duration.ofHours(12)))
        if (expiresIn.isNegative || expiresIn.isZero) return clear()
        val fence = Geofence.Builder()
            .setRequestId("venue-${bundle.event.id}")
            .setCircularRegion(centre.latitude, centre.longitude, radius.toFloat())
            .setExpirationDuration(expiresIn.toMillis())
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
            .build()
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofence(fence)
            .build()
        try {
            LocationServices.getGeofencingClient(context).addGeofences(request, pendingIntent())
                .addOnSuccessListener { registeredKey = key }
                .addOnFailureListener { Log.w(TAG, "Geofence not added", it) }
        } catch (e: Exception) {
            Log.w(TAG, "Geofence not added", e)
        }
    }

    fun clear() {
        if (registeredKey == null && !canUse()) return
        registeredKey = null
        try {
            LocationServices.getGeofencingClient(context).removeGeofences(pendingIntent())
        } catch (e: Exception) {
            Log.w(TAG, "Geofence not removed", e)
        }
    }

    private companion object {
        const val TAG = "VenueGeofence"
        const val REQUEST_CODE = 7_002
    }
}
