// AppPrefs.kt
package com.example.attendancemvp

import android.content.Context
import java.util.UUID

object AppPrefs {
    private const val PREFS = "attendance_prefs"
    private const val KEY_STUDENT_ID = "studentId"
    private const val KEY_URL = "functionUrl"
    private const val KEY_API = "apiKey"
    private const val KEY_DEVICE = "deviceId"

    fun getStudentId(ctx: Context) = ctx.sp().getString(KEY_STUDENT_ID, "") ?: ""
    fun setStudentId(ctx: Context, v: String) = ctx.sp().edit().putString(KEY_STUDENT_ID, v.trim()).apply()

    fun getUrl(ctx: Context) = ctx.sp().getString(KEY_URL, "") ?: ""
    fun setUrl(ctx: Context, v: String) = ctx.sp().edit().putString(KEY_URL, v.trim()).apply()

    fun getApiKey(ctx: Context) = ctx.sp().getString(KEY_API, "") ?: ""
    fun setApiKey(ctx: Context, v: String) = ctx.sp().edit().putString(KEY_API, v.trim()).apply()

    fun getDeviceId(ctx: Context): String {
        val existing = ctx.sp().getString(KEY_DEVICE, null)
        if (!existing.isNullOrBlank()) return existing
        val fresh = UUID.randomUUID().toString()
        ctx.sp().edit().putString(KEY_DEVICE, fresh).apply()
        return fresh
    }

    private fun Context.sp() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

fun String.isHttpsUrl(): Boolean = startsWith("https://", ignoreCase = true)

