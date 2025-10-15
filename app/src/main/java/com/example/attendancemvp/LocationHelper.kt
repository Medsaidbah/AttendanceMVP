// LocationHelper.kt
package com.example.attendancemvp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

object LocationHelper {
    private const val TIMEOUT_MS = 8_000L

    fun hasFineLocation(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    suspend fun getBestLocation(ctx: Context): Location? {
        if (!hasFineLocation(ctx)) return null
        val client = LocationServices.getFusedLocationProviderClient(ctx)

        // 1) Try current location (HIGH_ACCURACY) with a short timeout
        val current = try {
            withTimeoutOrNull(TIMEOUT_MS) {
                val cts = CancellationTokenSource()
                suspendCancellableCoroutine<Location?> { cont ->
                    // If timeout/coroutine cancels, cancel the fused request
                    cont.invokeOnCancellation { cts.cancel() }

                    client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                        .addOnSuccessListener { loc ->
                            if (!cont.isCompleted) cont.resume(loc)
                        }
                        .addOnFailureListener {
                            if (!cont.isCompleted) cont.resume(null)
                        }
                        .addOnCanceledListener {
                            if (!cont.isCompleted) cont.resume(null)
                        }
                }
            }
        } catch (_: Exception) { null }

        if (current != null) return current

        // 2) Fallback to last known
        return try { client.lastLocation.awaitNullable() } catch (_: Exception) { null }
    }
}

// Small suspend helpers:
suspend fun <T> Task<T>.awaitNullable(): T? =
    suspendCancellableCoroutine { cont ->
        addOnSuccessListener { if (!cont.isCompleted) cont.resume(it) }
        addOnFailureListener { if (!cont.isCompleted) cont.resume(null) }
        addOnCanceledListener { if (!cont.isCompleted) cont.resume(null) }
    }
