package com.virtueson.copilotmaps.location

import android.annotation.SuppressLint
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult as GmsLocationResult
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/** Result of a single location request. */
sealed interface LocationResult {
    data class Success(val latitude: Double, val longitude: Double) : LocationResult
    data class Failure(val reason: String) : LocationResult
}

/** A single continuous-stream location fix. bearing/speed are null when unknown. */
data class LocationSample(
    val latitude: Double,
    val longitude: Double,
    val bearing: Float?,
    val speedMps: Float?,
)

/** Gets the current location once, and streams continuous updates. Easy to fake in tests. */
interface LocationProvider {
    suspend fun getCurrentLocation(): LocationResult
    fun locationUpdates(): Flow<LocationSample>
}

/** Real implementation backed by Google's fused location client. */
class FusedLocationProvider(
    private val client: FusedLocationProviderClient,
) : LocationProvider {
    override suspend fun getCurrentLocation(): LocationResult {
        return try {
            val cts = CancellationTokenSource()
            val location = client
                .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                .await()
            if (location != null) {
                LocationResult.Success(location.latitude, location.longitude)
            } else {
                LocationResult.Failure("No location fix available")
            }
        } catch (e: SecurityException) {
            LocationResult.Failure("Location permission not granted")
        } catch (e: Exception) {
            LocationResult.Failure(e.message ?: "Unknown location error")
        }
    }

    @SuppressLint("MissingPermission")
    override fun locationUpdates(): Flow<LocationSample> = callbackFlow {
        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 1000L,
        ).build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: GmsLocationResult) {
                val loc = result.lastLocation ?: return
                trySend(
                    LocationSample(
                        latitude = loc.latitude,
                        longitude = loc.longitude,
                        bearing = if (loc.hasBearing()) loc.bearing else null,
                        speedMps = if (loc.hasSpeed()) loc.speed else null,
                    )
                )
            }
        }
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        awaitClose { client.removeLocationUpdates(callback) }
    }
}
