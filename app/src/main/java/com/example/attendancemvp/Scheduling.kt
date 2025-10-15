package com.example.attendancemvp

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.max

object Scheduling {

    enum class Slot(val label: String, val hour: Int, val minute: Int) {
        Entry("Entry", 7, 45),
        Break("Break", 13, 0),
        Exit("Exit", 17, 0);

        companion object {
            // Always return a non-null value (fallback to Entry)
            fun from(label: String): Slot =
                values().firstOrNull { it.label == label } ?: Entry
        }
    }

    private fun millisUntilNext(hour: Int, minute: Int): Long {
        val now = ZonedDateTime.now()
        var target = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!target.isAfter(now)) target = target.plusDays(1)
        return max(0, ChronoUnit.MILLIS.between(now, target))
    }

    fun scheduleDaily(ctx: Context) {
        scheduleSlot(ctx, Slot.Entry)
        scheduleSlot(ctx, Slot.Break)
        scheduleSlot(ctx, Slot.Exit)
    }

    fun scheduleSlot(ctx: Context, slot: Slot) {
        val delay = millisUntilNext(slot.hour, slot.minute)
        val req = AttendanceWorker.oneTime(slot.label, delay)
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            "Daily-${slot.label}",
            ExistingWorkPolicy.REPLACE,
            req
        )
    }

    fun scheduleNextDay(ctx: Context, slot: Slot) {
        val delay = millisUntilNext(slot.hour, slot.minute)
        val req = AttendanceWorker.oneTime(slot.label, delay)
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            "Daily-${slot.label}",
            ExistingWorkPolicy.REPLACE,
            req
        )
    }

    // Optional dev helper: quick run in ~10s
    fun scheduleIn10s(ctx: Context, label: String = "Manual10s") {
        val req = AttendanceWorker.oneTime(label, 10_000)
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            "OneShot-$label",
            ExistingWorkPolicy.REPLACE,
            req
        )
    }
}
