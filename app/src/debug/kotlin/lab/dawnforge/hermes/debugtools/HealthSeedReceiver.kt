package lab.dawnforge.hermes.debugtools

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Pressure
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.reflect.KClass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Debug builds only: writes a few days of sample health data into Health Connect on an emulator,
 * so health sharing can be tried end to end, and deletes it again (only what this app wrote).
 *
 * adb shell am broadcast -a lab.dawnforge.hermes.debug.SEED_HEALTH -n <package>/lab.dawnforge.hermes.debugtools.HealthSeedReceiver
 * adb shell am broadcast -a lab.dawnforge.hermes.debug.CLEAR_HEALTH -n <package>/lab.dawnforge.hermes.debugtools.HealthSeedReceiver
 */
class HealthSeedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val client = HealthConnectClient.getOrCreate(context)
        val pending = goAsync()
        scope.launch {
            try {
                when (intent.action) {
                    SEED -> {
                        val records = seed(ZoneId.systemDefault(), Instant.now())
                        client.insertRecords(records)
                        Log.i(TAG, "seeded ${records.size} records")
                    }

                    CLEAR -> {
                        val range = TimeRangeFilter.after(Instant.now().minusSeconds(DAYS_BACK * DAY_S))
                        TYPES.forEach { client.deleteRecords(it, range) }
                        Log.i(TAG, "cleared")
                    }
                }
            } catch (failure: Exception) {
                Log.w(TAG, "health seeding failed", failure)
            } finally {
                pending.finish()
            }
        }
    }

    private fun seed(zone: ZoneId, now: Instant): List<Record> {
        val phone = Device(type = Device.TYPE_PHONE)
        val watch = Device(type = Device.TYPE_WATCH)
        val today = LocalDate.now(zone)
        val records = mutableListOf<Record>()
        fun at(day: LocalDate, hour: Int, minute: Int = 0): ZonedDateTime = day.atTime(hour, minute).atZone(zone)

        for (back in 0L..2L) {
            val day = today.minusDays(back)
            for (hour in WAKING_HOURS) {
                val start = at(day, hour)
                val end = start.plusHours(1)
                if (end.toInstant() > now) continue
                val steps = 400L + ((back * 13 + hour * 7) % 5) * 100
                records +=
                    StepsRecord(
                        start.toInstant(),
                        start.offset,
                        end.toInstant(),
                        end.offset,
                        steps,
                        Metadata.autoRecorded(phone),
                    )
                records += DistanceRecord(
                    start.toInstant(),
                    start.offset,
                    end.toInstant(),
                    end.offset,
                    Length.meters(steps * STRIDE_M),
                    Metadata.autoRecorded(phone),
                )
                records += ActiveCaloriesBurnedRecord(
                    start.toInstant(),
                    start.offset,
                    end.toInstant(),
                    end.offset,
                    Energy.kilocalories(steps / 20.0),
                    Metadata.autoRecorded(phone),
                )
                records += HeartRateRecord(
                    start.toInstant(),
                    start.offset,
                    end.toInstant(),
                    end.offset,
                    (0 until 4).map {
                        HeartRateRecord.Sample(
                            start.plusMinutes(it * 15L).toInstant(),
                            68L + (hour % 5) + it,
                        )
                    },
                    Metadata.autoRecorded(watch),
                )
            }
            val morning = at(day, 7)
            if (morning.toInstant() <= now) {
                records +=
                    RestingHeartRateRecord(
                        morning.toInstant(),
                        morning.offset,
                        60L + back,
                        Metadata.autoRecorded(watch),
                    )
            }
        }

        // The nights before each of these days, with stages, from the watch: the ones already over.
        for (back in 0L..2L) {
            val bed = at(today.minusDays(back + 1), 23)
            val wake = bed.plusMinutes(SLEEP_MIN)
            if (wake.toInstant() <= now) records += night(bed, wake, watch)
        }

        // Yesterday's run, with its distance, energy and heart rate.
        val run = at(today.minusDays(1), 18)
        val runEnd = run.plusMinutes(40)
        records += ExerciseSessionRecord(
            run.toInstant(),
            run.offset,
            runEnd.toInstant(),
            runEnd.offset,
            Metadata.activelyRecorded(watch),
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
            "Evening run",
        )
        records +=
            DistanceRecord(
                run.toInstant(),
                run.offset,
                runEnd.toInstant(),
                runEnd.offset,
                Length.meters(RUN_M),
                Metadata.activelyRecorded(watch),
            )
        records += ActiveCaloriesBurnedRecord(
            run.toInstant(),
            run.offset,
            runEnd.toInstant(),
            runEnd.offset,
            Energy.kilocalories(RUN_KCAL),
            Metadata.activelyRecorded(watch),
        )
        records += HeartRateRecord(
            run.toInstant(),
            run.offset,
            runEnd.toInstant(),
            runEnd.offset,
            (0 until 8).map { HeartRateRecord.Sample(run.plusMinutes(it * 5L).toInstant(), 140L + it * 4) },
            Metadata.activelyRecorded(watch),
        )

        // Yesterday's bike ride: its distance is cycling.
        val ride = at(today.minusDays(1), 10)
        val rideEnd = ride.plusMinutes(45)
        records += ExerciseSessionRecord(
            ride.toInstant(),
            ride.offset,
            rideEnd.toInstant(),
            rideEnd.offset,
            Metadata.activelyRecorded(watch),
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
            "Ride",
        )
        records +=
            DistanceRecord(
                ride.toInstant(),
                ride.offset,
                rideEnd.toInstant(),
                rideEnd.offset,
                Length.meters(RIDE_M),
                Metadata.activelyRecorded(watch),
            )

        // Today's weight, blood oxygen and blood pressure.
        val reading = now.minusSeconds(HOUR_S).atZone(zone)
        records += WeightRecord(reading.toInstant(), reading.offset, Mass.kilograms(WEIGHT_KG), Metadata.manualEntry())
        records +=
            OxygenSaturationRecord(
                reading.toInstant(),
                reading.offset,
                Percentage(OXYGEN_PERCENT),
                Metadata.autoRecorded(watch),
            )
        records += BloodPressureRecord(
            reading.toInstant(),
            reading.offset,
            Metadata.manualEntry(),
            Pressure.millimetersOfMercury(SYSTOLIC),
            Pressure.millimetersOfMercury(DIASTOLIC),
        )
        return records
    }

    private fun night(bed: ZonedDateTime, wake: ZonedDateTime, watch: Device): SleepSessionRecord {
        val stages = listOf(
            Triple(0L, 120L, SleepSessionRecord.STAGE_TYPE_LIGHT),
            Triple(120L, 180L, SleepSessionRecord.STAGE_TYPE_DEEP),
            Triple(180L, 240L, SleepSessionRecord.STAGE_TYPE_REM),
            Triple(240L, 250L, SleepSessionRecord.STAGE_TYPE_AWAKE),
            Triple(250L, 360L, SleepSessionRecord.STAGE_TYPE_LIGHT),
            Triple(360L, SLEEP_MIN, SleepSessionRecord.STAGE_TYPE_REM),
        ).map { (from, to, type) ->
            SleepSessionRecord.Stage(bed.plusMinutes(from).toInstant(), bed.plusMinutes(to).toInstant(), type)
        }
        return SleepSessionRecord(
            bed.toInstant(),
            bed.offset,
            wake.toInstant(),
            wake.offset,
            metadata = Metadata.autoRecorded(watch),
            stages = stages,
        )
    }

    private companion object {
        const val TAG = "HealthSeed"
        const val SEED = "lab.dawnforge.hermes.debug.SEED_HEALTH"
        const val CLEAR = "lab.dawnforge.hermes.debug.CLEAR_HEALTH"
        const val DAYS_BACK = 5L
        const val DAY_S = 86_400L
        const val HOUR_S = 3_600L
        const val SLEEP_MIN = 480L
        const val STRIDE_M = 0.7
        const val RUN_M = 6_000.0
        const val RUN_KCAL = 450.0
        const val RIDE_M = 15_000.0
        const val WEIGHT_KG = 72.4
        const val OXYGEN_PERCENT = 97.0
        const val SYSTOLIC = 118.0
        const val DIASTOLIC = 76.0
        val WAKING_HOURS = 8..20
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val TYPES: List<KClass<out Record>> = listOf(
            StepsRecord::class,
            DistanceRecord::class,
            ActiveCaloriesBurnedRecord::class,
            HeartRateRecord::class,
            RestingHeartRateRecord::class,
            SleepSessionRecord::class,
            ExerciseSessionRecord::class,
            WeightRecord::class,
            OxygenSaturationRecord::class,
            BloodPressureRecord::class,
        )
    }
}
