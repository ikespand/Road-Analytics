package com.ikespand.roadanalytics.util

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*

class LocationHelper(private val activity: Activity) {

    private val client: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(activity)

    private var callback: LocationCallback? = null
    @Volatile private var lastFix: Location? = null

    companion object {
        const val REQ_LOCATION = 2001
    }

    fun ensurePermission(): Boolean {
        val fine = ActivityCompat.checkSelfPermission(
            activity, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ActivityCompat.checkSelfPermission(
            activity, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!fine && !coarse) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                REQ_LOCATION
            )
            return false
        }
        return true
    }

    fun startUpdates(onUpdate: (Location) -> Unit) {
        if (!ensurePermission()) return

        val req = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, /* interval */ 2000L
        ).apply {
            setMinUpdateIntervalMillis(1000L)
            setMaxUpdateDelayMillis(4000L)
        }.build()

        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                lastFix = loc
                onUpdate(loc)
            }
        }
        callback = cb
        client.requestLocationUpdates(req, cb, Looper.getMainLooper())
    }

    fun stopUpdates() {
        callback?.let { client.removeLocationUpdates(it) }
        callback = null
    }

    fun getLastFix(): Location? = lastFix
}
