package com.guardianai.agent.services

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.guardianai.agent.GuardianApp
import com.guardianai.agent.config.Config
import com.guardianai.agent.utils.DeviceInfo
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * LocationService handles GPS tracking using FusedLocationProviderClient.
 * Provides location as Flow, and uploads coordinates to Supabase.
 */
class LocationService(private val context: Context) {

    companion object {
        private const val TAG = "LocationService"
    }

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    /**
     * Returns a Flow that emits location updates continuously.
     * Interval is controlled by Config.LOCATION_INTERVAL_MS.
     */
    @SuppressLint("MissingPermission")
    fun getLocationFlow(): Flow<Location> = callbackFlow {
        if (!hasLocationPermission()) {
            Log.w(TAG, "Location permission not granted")
            close()
            return@callbackFlow
        }

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            Config.LOCATION_INTERVAL_MS
        ).apply {
            setMinUpdateIntervalMillis(Config.LOCATION_INTERVAL_MS / 2)
            setWaitForAccurateLocation(false)
        }.build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    Log.d(TAG, "Location update: ${location.latitude}, ${location.longitude}")
                    trySend(location)
                }
            }
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            callback,
            Looper.getMainLooper()
        )

        awaitClose {
            fusedLocationClient.removeLocationUpdates(callback)
            Log.d(TAG, "Location updates stopped")
        }
    }

    /**
     * Gets the last known location as a one-shot suspend call.
     */
    @SuppressLint("MissingPermission")
    suspend fun getLastLocation(): Location? {
        if (!hasLocationPermission()) return null

        return suspendCancellableCoroutine { cont ->
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location -> cont.resume(location) }
                .addOnFailureListener { e -> cont.resumeWithException(e) }
        }
    }

    /**
     * Uploads location data to Supabase location_history table.
     */
    suspend fun uploadLocation(
        childId: String,
        latitude: Double,
        longitude: Double,
        accuracy: Float,
        speed: Float,
        altitude: Double = 0.0
    ) {
        try {
            val address = getAddress(latitude, longitude)
            val deviceId = DeviceInfo.getDeviceId(context)

            GuardianApp.supabase.postgrest[Config.TABLE_LOCATION_HISTORY].insert(
                buildJsonObject {
                    put("child_id", childId)
                    put("device_id", deviceId)
                    put("latitude", latitude)
                    put("longitude", longitude)
                    put("accuracy", accuracy.toDouble())
                    put("speed", speed.toDouble())
                    put("altitude", altitude)
                    put("address", address)
                    put("recorded_at", Instant.now().toString())
                }
            )

            Log.d(TAG, "Location uploaded: $latitude, $longitude (accuracy: ${accuracy}m)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload location to Supabase", e)
        }
    }

    /**
     * Reverse-geocodes coordinates to a human-readable address string.
     * Returns null if geocoding fails or is unavailable.
     */
    suspend fun getAddress(latitude: Double, longitude: Double): String? {
        return try {
            if (!Geocoder.isPresent()) return null

            val geocoder = Geocoder(context, Locale.getDefault())

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // API 33+ async geocoding
                suspendCancellableCoroutine { cont ->
                    geocoder.getFromLocation(latitude, longitude, 1) { addresses ->
                        cont.resume(formatAddress(addresses))
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(latitude, longitude, 1)
                formatAddress(addresses ?: emptyList())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Geocoding failed", e)
            null
        }
    }

    private fun formatAddress(addresses: List<Address>): String? {
        if (addresses.isEmpty()) return null
        val address = addresses[0]
        val parts = mutableListOf<String>()

        address.thoroughfare?.let { parts.add(it) }
        address.subLocality?.let { parts.add(it) }
        address.locality?.let { parts.add(it) }
        address.adminArea?.let { parts.add(it) }
        address.countryName?.let { parts.add(it) }

        return parts.joinToString(", ").ifBlank { null }
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }
}
