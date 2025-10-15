package com.example.attendancemvp

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.concurrent.TimeUnit

class AttendanceWorker(
    private val ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val slot = inputData.getString(KEY_SLOT) ?: "Manual"
        val studentId = AppPrefs.getStudentId(ctx).trim()
        val deviceId = AppPrefs.getDeviceId(ctx)           // persisted UUID
        val functionUrl = AppPrefs.getUrl(ctx).trim()
        val apiKey = AppPrefs.getApiKey(ctx)

        // Guard rails
        if (studentId.isEmpty() || deviceId.isEmpty()) {
            Log.w(TAG, "Missing required IDs; studentId or deviceId is empty")
            return@withContext Result.failure()
        }
        if (!functionUrl.startsWith("https://", ignoreCase = true)) {
            Log.w(TAG, "Non-HTTPS or empty URL: '$functionUrl'")
            return@withContext Result.failure()
        }

        // 1) Get location (current with timeout -> last known fallback)
        val loc = LocationHelper.getBestLocation(ctx)
        if (loc == null) {
            Log.w(TAG, "No location available (permissions/provider). Retrying.")
            return@withContext Result.retry()
        }
        val accuracyM = if (loc.hasAccuracy()) loc.accuracy.toInt() else -1
        if (accuracyM > 0 && accuracyM > 100) {
            // Optional policy: soft-warn but continue
            Log.w(TAG, "Low accuracy ($accuracyM m) – continuing per spec (flag only).")
        }

        // 2) Build payload (ISO-8601 timestamp)
        val payload = JSONObject().apply {
            put("studentId", studentId)
            put("deviceId", deviceId)
            put("slot", slot)
            put("lat", loc.latitude)
            put("lng", loc.longitude)
            put("accuracyM", accuracyM)
            put("clientTimestamp", Instant.now().toString())
        }
        Log.i(TAG, "Sending payload (no key): $payload")

        // 3) HTTPS POST to configured endpoint (no path auto-append)
        val (code, body) = postJson(functionUrl, payload, apiKey)
        Log.i(TAG, "HTTP status=$code body='${body.take(200)}'")

        // 4) Map result to WorkManager semantics
        when (code) {
            in 200..299 -> {
                // Self-reschedule only for scheduled slots
                if (slot in listOf("Entry", "Break", "Exit")) {
                    try {
                        Scheduling.scheduleNextDay(ctx, Scheduling.Slot.from(slot))
                    } catch (e: Exception) {
                        Log.w(TAG, "scheduleNextDay failed: ${e.message}")
                    }
                }
                Result.success()
            }
            in 400..499 -> {
                // client errors → do NOT retry
                Result.failure()
            }
            else -> {
                // 5xx or weird conditions → retry with backoff
                Result.retry()
            }
        }
    }

    private fun postJson(urlStr: String, json: JSONObject, apiKey: String?): Pair<Int, String> {
        val url = URL(urlStr)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 12_000
            doInput = true
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            if (!apiKey.isNullOrBlank()) {
                setRequestProperty("x-api-key", apiKey) // never log this
            }
        }
        conn.outputStream.use { os ->
            OutputStreamWriter(os, Charsets.UTF_8).use { it.write(json.toString()) }
        }
        val code = conn.responseCode
        val body = try {
            conn.inputStream.bufferedReader().use(BufferedReader::readText)
        } catch (_: Exception) {
            conn.errorStream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
        }
        conn.disconnect()
        return code to body
    }

    companion object {
        private const val TAG = "AttendanceWorker"

        const val KEY_SLOT = "slot"
        const val KEY_STUDENT_ID = "studentId"
        const val KEY_DEVICE_ID = "deviceId"
        const val KEY_FUNCTION_URL = "functionUrl"
        const val KEY_API_KEY = "apiKey"

        // Optional helper if you need to programmatically build a one-time request elsewhere
        fun oneTime(slot: String, delayMs: Long? = null): OneTimeWorkRequest {
            val data = Data.Builder().putString(KEY_SLOT, slot).build()
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val b = OneTimeWorkRequestBuilder<AttendanceWorker>()
                .setInputData(data)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            if (delayMs != null && delayMs > 0) b.setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            return b.build()
        }
    }
}
