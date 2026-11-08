package com.cmolnar65.swimlog.sync

import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.WeightRecord
import java.math.BigDecimal

data class HealthRecords(
    val sessions: List<ExerciseSessionRecord>,
    val distances: List<DistanceRecord>,
    val heartRates: List<HeartRateRecord>,
    val weights: List<WeightRecord>,
    val bodyFats: List<BodyFatRecord>,
    val leanMasses: List<LeanBodyMassRecord>,
    val bloodPressures: List<BloodPressureRecord>,
)

object HealthMapper {
    fun map(records: HealthRecords): ImportBatch {
        val supported = records.sessions.mapNotNull { session ->
            val mapped = activity(session.exerciseType) ?: return@mapNotNull null
            val origin = session.metadata.dataOrigin.packageName
            val matchingDistances = records.distances.filter {
                it.metadata.dataOrigin.packageName == origin &&
                    !it.startTime.isBefore(session.startTime) && !it.endTime.isAfter(session.endTime)
            }.sortedBy { it.startTime }
            val hasOverlaps = matchingDistances.zipWithNext().any { (a, b) -> b.startTime.isBefore(a.endTime) }
            val distance = if (matchingDistances.isNotEmpty() && !hasOverlaps) {
                matchingDistances.sumOf { it.distance.inMeters }
            } else null
            val hr = records.heartRates.flatMap { hrRecord ->
                if (hrRecord.metadata.dataOrigin.packageName != origin) return@flatMap emptyList()
                hrRecord.samples.mapIndexedNotNull { index, sample ->
                    val owners = records.sessions.count {
                        sample.time >= it.startTime && sample.time <= it.endTime &&
                            it.metadata.dataOrigin.packageName == origin && activity(it.exerciseType) != null
                    }
                    if (owners != 1 || sample.time < session.startTime || sample.time > session.endTime) null
                    else HeartRateSample(
                        recordId = "${hrRecord.metadata.id}:$index",
                        origin = origin,
                        time = Mapping.isoTime(sample.time, session.startZoneOffset),
                        bpm = sample.beatsPerMinute.toInt(),
                    )
                }
            }.distinctBy { it.recordId }

            WorkoutImport(
                recordId = session.metadata.id,
                origin = origin,
                activity = mapped.first,
                environment = mapped.second,
                startTime = Mapping.isoTime(session.startTime, session.startZoneOffset),
                endTime = Mapping.isoTime(session.endTime, session.endZoneOffset),
                distanceM = distance?.takeIf { it.isFinite() && it > 0 }?.let(::decimal),
                heartRateSamples = hr,
            )
        }

        val measurements = buildList {
            records.weights.forEach { record -> add(measurement(record.metadata.id, record.metadata.dataOrigin.packageName, "weight", record.time, record.zoneOffset, "weight", decimal(record.weight.inKilograms), "kg")) }
            records.bodyFats.forEach { record -> add(measurement(record.metadata.id, record.metadata.dataOrigin.packageName, "body_composition", record.time, record.zoneOffset, "body_fat", decimal(record.percentage.value), "%")) }
            records.leanMasses.forEach { record -> add(measurement(record.metadata.id, record.metadata.dataOrigin.packageName, "body_composition", record.time, record.zoneOffset, "lean_body_mass", decimal(record.mass.inKilograms), "kg")) }
            records.bloodPressures.forEach { record ->
                add(MeasurementImport(record.metadata.id, record.metadata.dataOrigin.packageName, "blood_pressure", Mapping.isoTime(record.time, record.zoneOffset), mapOf(
                    "systolic" to MeasurementValue(decimal(record.systolic.inMillimetersOfMercury), "mmHg"),
                    "diastolic" to MeasurementValue(decimal(record.diastolic.inMillimetersOfMercury), "mmHg"),
                )))
            }
        }
        return ImportBatch(batchId = java.util.UUID.randomUUID().toString(), workouts = supported, measurements = measurements)
    }

    internal fun activity(type: Int): Pair<String, String?>? = when (type) {
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> "pool_swimming" to null
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "open_water_swimming" to null
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "running" to null
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "running_treadmill" to "indoor"
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "walking" to null
        else -> null
    }

    private fun measurement(id: String, origin: String, category: String, time: java.time.Instant,
                           offset: java.time.ZoneOffset?, name: String, value: String, unit: String) =
        MeasurementImport(id, origin, category, Mapping.isoTime(time, offset), mapOf(name to MeasurementValue(value, unit)))

    private fun decimal(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
}
