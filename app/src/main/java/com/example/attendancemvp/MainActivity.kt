package com.example.attendancemvp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.Calendar
import java.util.UUID
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private lateinit var studentIdEt: EditText
    private lateinit var functionUrlEt: EditText
    private lateinit var apiKeyEt: EditText

    // Persisted deviceId (UUID)
    private val deviceId by lazy {
        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("deviceId", it).apply()
        }
    }

    // Request FINE (+ COARSE declared) first; BACKGROUND separately on Q+
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        ) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                backgroundLocationPermissionLauncher.launch(
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                )
            }
        }
    }

    private val backgroundLocationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Optional: you can react here if user denied/allowed background
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        studentIdEt = findViewById(R.id.studentIdEt)
        functionUrlEt = findViewById(R.id.functionUrlEt)
        apiKeyEt = findViewById(R.id.apiKeyEt)

        // Preload previously saved settings
        studentIdEt.setText(AppPrefs.getStudentId(this))
        functionUrlEt.setText(AppPrefs.getUrl(this))
        apiKeyEt.setText(AppPrefs.getApiKey(this))

        checkAndRequestPermissions()

        findViewById<Button>(R.id.btnSendTest).setOnClickListener {
            enqueueOnce(slot = "Manual")
        }

        findViewById<Button>(R.id.btnSchedule).setOnClickListener {
            scheduleDaily("Entry", 7, 45)
            scheduleDaily("Break", 13, 0)
            scheduleDaily("Exit", 17, 0)
            Toast.makeText(this, "Scheduled daily captures", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkAndRequestPermissions() {
        val toRequest = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // Request both; user can choose Approximate vs Precise
            toRequest += Manifest.permission.ACCESS_FINE_LOCATION
            toRequest += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (toRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(toRequest.toTypedArray())
        } else if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            backgroundLocationPermissionLauncher.launch(
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            )
            Toast.makeText(
                this,
                "Tip: Allow background location so captures can run at set times.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun enqueueOnce(slot: String) {
        val student = studentIdEt.text.toString().trim()
        val rawUrl = functionUrlEt.text.toString().trim()
        val key = apiKeyEt.text.toString().trim()

        if (student.isEmpty()) {
            Toast.makeText(this, "Enter Student ID", Toast.LENGTH_LONG).show()
            return
        }
        val url = normalizeHttpsUrl(rawUrl) ?: run {
            Toast.makeText(this, "Enter a full URL starting with https://", Toast.LENGTH_LONG)
                .show()
            return
        }

        // Persist settings
        saveSettings(student, url, key)

        val data = Data.Builder()
            .putString("slot", slot)
            .putString("studentId", student)
            .putString("deviceId", deviceId)
            .putString("functionUrl", url)
            .putString("apiKey", key)
            .build()

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val req = OneTimeWorkRequestBuilder<AttendanceWorker>()
            .setInputData(data)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(this).enqueue(req)
        Toast.makeText(this, "One-time capture enqueued ($slot)", Toast.LENGTH_SHORT).show()
    }

    private fun scheduleDaily(slot: String, hour: Int, minute: Int) {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (target.before(now)) target.add(Calendar.DAY_OF_YEAR, 1)
        val delay = target.timeInMillis - now.timeInMillis

        val student = studentIdEt.text.toString().trim()
        val rawUrl = functionUrlEt.text.toString().trim()
        val key = apiKeyEt.text.toString().trim()

        if (student.isEmpty()) {
            Toast.makeText(this, "Enter Student ID", Toast.LENGTH_LONG).show()
            return
        }
        val url = normalizeHttpsUrl(rawUrl) ?: run {
            Toast.makeText(this, "Enter a full URL starting with https://", Toast.LENGTH_LONG)
                .show()
            return
        }

        // Persist settings
        saveSettings(student, url, key)

        val data = Data.Builder()
            .putString("slot", slot)
            .putString("studentId", student)
            .putString("deviceId", deviceId)
            .putString("functionUrl", url)
            .putString("apiKey", key)
            .build()

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val req = OneTimeWorkRequestBuilder<AttendanceWorker>()
            .setInputData(data)
            .setConstraints(constraints)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()

        // Unique work per slot; Worker will self-reschedule the next day on success
        WorkManager.getInstance(this)
            .enqueueUniqueWork("$slot-daily", ExistingWorkPolicy.REPLACE, req)
    }

    private fun normalizeHttpsUrl(input: String): String? {
        val s = input.trim()
        return if (s.startsWith("https://", ignoreCase = true)) s else null
    }

    private fun saveSettings(student: String, url: String, key: String) {
        AppPrefs.setStudentId(this, student)
        AppPrefs.setUrl(this, url)
        AppPrefs.setApiKey(this, key)
        // deviceId is already persisted lazily
    }
}
