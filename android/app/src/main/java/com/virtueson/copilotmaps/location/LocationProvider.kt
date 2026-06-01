package com.virtueson.copilotmaps.location

import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.tasks.await

/** Result of a single location request. */
sealed interface LocationResult {
    data class Success(val latitude: Double, val longitude: Double) : LocationResult
    data class Failure(val reason: String) : LocationResult
}

/** One job: get the current location, once. Easy to fake in tests. */
interface LocationProvider {
    suspend fun getCurrentLocation(): LocationResult
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
}
