package com.cmolnar65.swimlog.sync

import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.PowerRecord
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

data class HealthRecords(
    val sessions: List<ExerciseSessionRecord>,
    val distances: List<DistanceRecord>,
    val heartRates: List<HeartRateRecord>,
    val weights: List<WeightRecord>,
    val bodyFats: List<BodyFatRecord>,
    val leanMasses: List<LeanBodyMassRecord>,
    val bloodPressures: List<BloodPressureRecord>,
    val speeds: List<SpeedRecord> = emptyList(),
    val calories: List<TotalCaloriesBurnedRecord> = emptyList(),
    val activeCalories: List<ActiveCaloriesBurnedRecord> = emptyList(),
    val elevation: List<ElevationGainedRecord> = emptyList(),
    val floors: List<FloorsClimbedRecord> = emptyList(),
    val stepsCadence: List<StepsCadenceRecord> = emptyList(),
    val cyclingCadence: List<CyclingPedalingCadenceRecord> = emptyList(),
    val power: List<PowerRecord> = emptyList(),
)

/** A source-tagged interval used by the conservative distance matcher. */
internal data class DistanceInterval(
    val origin: String,
    val start: Instant,
    val end: Instant,
    val meters: Double,
)

object HealthMapper {
    fun map(records: HealthRecords, routeOverrides: Map<String, ExerciseRoute> = emptyMap()): ImportBatch {
        val mappedWorkouts = records.sessions.mapNotNull { session ->
            val mapped = activity(session.exerciseType) ?: return@mapNotNull null
            val origin = session.metadata.dataOrigin.packageName
            fun ownsSample(time: Instant) = records.sessions.count {
                time >= it.startTime && time <= it.endTime && it.metadata.dataOrigin.packageName == origin && activity(it.exerciseType) != null
            } == 1 && time >= session.startTime && time <= session.endTime
            val distance = resolveDistanceMeters(
                session.startTime,
                session.endTime,
                records.distances.map {
                    DistanceInterval(
                        origin = it.metadata.dataOrigin.packageName,
                        start = it.startTime,
                        end = it.endTime,
                        meters = it.distance.inMeters,
                    )
                },
            )
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
                        time = Mapping.isoTime(sample.time, null),
                        bpm = sample.beatsPerMinute.toInt(),
                    )
                }
            }.distinctBy { it.recordId }

            val speedSamples = records.speeds
                .asSequence()
                .filter { it.metadata.dataOrigin.packageName == origin }
                .flatMap { it.samples.asSequence() }
                .filter { ownsSample(it.time) }
                .map { SpeedSample(Mapping.isoTime(it.time, null), it.speed.inMetersPerSecond) }
                .filter { it.metersPerSecond.isFinite() && it.metersPerSecond >= 0 }
                .distinctBy { it.time to it.metersPerSecond }
                .sortedBy { it.time }
                .take(2000)
                .toList()
            val calorieRecords = records.calories.filter {
                it.metadata.dataOrigin.packageName == origin &&
                    !it.startTime.isBefore(session.startTime) && !it.endTime.isAfter(session.endTime) &&
                    it.startTime.isBefore(it.endTime)
            }.sortedBy { it.startTime }
            fun intervalTotal(intervals: List<Triple<Instant, Instant, Double>>) =
                sumContainedNonOverlappingIntervals(session.startTime, session.endTime, intervals)
            val totalCalories = intervalTotal(records.calories.filter { it.metadata.dataOrigin.packageName == origin }.map { Triple(it.startTime, it.endTime, it.energy.inKilocalories) })
            val activeCalories = intervalTotal(records.activeCalories.filter { it.metadata.dataOrigin.packageName == origin }.map { Triple(it.startTime, it.endTime, it.energy.inKilocalories) })
            val elevationGain = intervalTotal(records.elevation.filter { it.metadata.dataOrigin.packageName == origin }.map { Triple(it.startTime, it.endTime, it.elevation.inMeters) })
            val floorsClimbed = intervalTotal(records.floors.filter { it.metadata.dataOrigin.packageName == origin }.map { Triple(it.startTime, it.endTime, it.floors) })
            fun metricSamples(values: List<Pair<Instant, Double>>, max: Int = 2000) = values
                .filter { ownsSample(it.first) && it.second.isFinite() && it.second >= 0 }
                .distinct().sortedBy { it.first }.take(max)
                .map { WorkoutMetricSample(Mapping.isoTime(it.first, null), it.second) }
            val stepCadence = if (mapped.first in setOf("running", "running_treadmill", "walking")) metricSamples(
                records.stepsCadence.filter { it.metadata.dataOrigin.packageName == origin }.flatMap { r -> r.samples.map { it.time to it.rate } }
            ) else emptyList()
            val cyclingCadence = if (mapped.first == "cycling") metricSamples(
                records.cyclingCadence.filter { it.metadata.dataOrigin.packageName == origin }.flatMap { r -> r.samples.map { it.time to it.revolutionsPerMinute } }
            ) else emptyList()
            val powerSamples = metricSamples(
                records.power.filter { it.metadata.dataOrigin.packageName == origin }.flatMap { r -> r.samples.map { it.time to it.power.inWatts } }
            )
            val details = WorkoutDetails(
                laps = session.laps.mapNotNull { lap ->
                    if (!lap.startTime.isBefore(lap.endTime) || lap.startTime.isBefore(session.startTime) || lap.endTime.isAfter(session.endTime)) null
                    else WorkoutLap(
                        startTime = Mapping.isoTime(lap.startTime, session.startZoneOffset),
                        endTime = Mapping.isoTime(lap.endTime, session.endZoneOffset),
                        distanceM = lap.length?.inMeters?.takeIf { it.isFinite() && it > 0 }?.let(::decimal),
                    )
                },
                segments = session.segments.mapNotNull { segment ->
                    if (!segment.startTime.isBefore(segment.endTime) || segment.startTime.isBefore(session.startTime) || segment.endTime.isAfter(session.endTime)) null
                    else WorkoutSegment(
                        startTime = Mapping.isoTime(segment.startTime, session.startZoneOffset),
                        endTime = Mapping.isoTime(segment.endTime, session.endZoneOffset),
                        segmentType = segment.segmentType,
                        segmentLabel = segmentLabel(segment.segmentType),
                        repetitions = segment.repetitions,
                    )
                },
                route = (routeOverrides[session.metadata.id] ?: (session.exerciseRouteResult as? ExerciseRouteResult.Data)?.exerciseRoute)?.locations.orEmpty().mapNotNull { point ->
                    if (point.time.isBefore(session.startTime) || point.time.isAfter(session.endTime) ||
                        !point.latitude.isFinite() || !point.longitude.isFinite() ||
                        point.latitude !in -90.0..90.0 || point.longitude !in -180.0..180.0) null
                    else RoutePoint(
                        time = Mapping.isoTime(point.time, null),
                        latitude = point.latitude,
                        longitude = point.longitude,
                        altitudeM = point.altitude?.inMeters?.takeIf { it.isFinite() },
                        horizontalAccuracyM = point.horizontalAccuracy?.inMeters?.takeIf { it.isFinite() && it >= 0 },
                    )
                }.take(5000),
                totalCaloriesKcal = totalCalories?.let(::decimal),
                averageSpeedMps = null,
                maximumSpeedMps = speedSamples.maxOfOrNull { it.metersPerSecond }?.takeIf { it > 0 }?.let(::decimal),
                speedSamples = speedSamples,
                activeCaloriesKcal = activeCalories?.let(::decimal),
                elevationGainM = elevationGain?.let(::decimal),
                floorsClimbed = floorsClimbed?.let(::decimal),
                stepCadenceSamples = stepCadence,
                cyclingCadenceSamples = cyclingCadence,
                powerSamples = powerSamples,
            ).takeIf { it.hasContent() }

            WorkoutImport(
                recordId = session.metadata.id,
                origin = origin,
                activity = mapped.first,
                environment = mapped.second,
                startTime = Mapping.isoTime(session.startTime, session.startZoneOffset),
                endTime = Mapping.isoTime(session.endTime, session.endZoneOffset),
                distanceM = distance?.takeIf { it.isFinite() && it > 0 }?.let(::decimal),
                heartRateSamples = hr,
                details = details,
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
        val uniqueWorkouts = collapseMirroredWorkoutCopies(mappedWorkouts)
        return ImportBatch(
            batchId = java.util.UUID.randomUUID().toString(),
            workouts = uniqueWorkouts,
            measurements = measurements,
            mirroredWorkoutsSuppressed = mappedWorkouts.size - uniqueWorkouts.size,
        )
    }

    /**
     * Suppress strong cross-source mirror candidates. Measured fields must agree;
     * interval boundaries may differ by small source rounding. When both sources
     * lack distance, require especially close interval boundaries.
     */
    internal fun collapseMirroredWorkoutCopies(workouts: List<WorkoutImport>): List<WorkoutImport> {
        val retained = mutableListOf<WorkoutImport>()
        workouts.forEach { workout ->
            val matchIndex = retained.indexOfFirst { existing -> isMirroredWorkoutCopy(existing, workout) }
            if (matchIndex < 0) {
                retained += workout
            } else if (preferCanonical(workout, retained[matchIndex])) {
                retained[matchIndex] = workout
            }
        }
        return retained
    }

    private fun preferCanonical(candidate: WorkoutImport, current: WorkoutImport): Boolean {
        // Keep a mapped distance when only one source supplied one.
        if ((candidate.distanceM != null) != (current.distanceM != null)) return candidate.distanceM != null
        // Prefer the richer copy only when the other source omitted details.
        if ((candidate.details != null) != (current.details != null)) return candidate.details != null
        return compareValuesBy(candidate, current, WorkoutImport::origin, WorkoutImport::recordId) < 0
    }

    private fun isMirroredWorkoutCopy(first: WorkoutImport, second: WorkoutImport): Boolean {
        if (first.origin == second.origin) return false
        if (first.activity != second.activity || first.environment != second.environment) return false
        if (!distancesWithinTenPercent(first.distanceM, second.distanceM)) return false
        if (first.details != null && second.details != null && first.details != second.details) return false

        val firstHeartRate = first.heartRateSamples.map { it.time to it.bpm }.sortedWith(compareBy({ it.first }, { it.second }))
        val secondHeartRate = second.heartRateSamples.map { it.time to it.bpm }.sortedWith(compareBy({ it.first }, { it.second }))
        if (firstHeartRate != secondHeartRate) return false

        val firstStart = Instant.parse(first.startTime)
        val secondStart = Instant.parse(second.startTime)
        val firstEnd = Instant.parse(first.endTime)
        val secondEnd = Instant.parse(second.endTime)
        val overlapStart = maxOf(firstStart, secondStart)
        val overlapEnd = minOf(firstEnd, secondEnd)
        if (!overlapStart.isBefore(overlapEnd)) return false

        val firstDuration = Duration.between(firstStart, firstEnd).toMillis()
        val secondDuration = Duration.between(secondStart, secondEnd).toMillis()
        val shorterDuration = minOf(firstDuration, secondDuration)
        val longerDuration = maxOf(firstDuration, secondDuration)
        if (shorterDuration <= 0 || longerDuration <= 0) return false
        val overlapDuration = Duration.between(overlapStart, overlapEnd).toMillis()
        val overlapRatio = overlapDuration.toDouble() / shorterDuration.toDouble()
        val durationRatio = shorterDuration.toDouble() / longerDuration.toDouble()
        return overlapRatio >= 0.90 && durationRatio >= 0.80
    }

    private fun distancesWithinTenPercent(first: String?, second: String?): Boolean {
        // Missing distance is unknown, not evidence that two otherwise likely
        // overlapping sessions differ. A one-sided value wins canonical selection.
        if (first == null || second == null) return true
        val firstValue = first.toBigDecimalOrNull() ?: return false
        val secondValue = second.toBigDecimalOrNull() ?: return false
        if (firstValue.signum() <= 0 || secondValue.signum() <= 0) return false
        val larger = maxOf(firstValue, secondValue)
        val difference = firstValue.subtract(secondValue).abs()
        // Compare with the larger value so the threshold is symmetric regardless of source order.
        return difference.multiply(BigDecimal.TEN) <= larger
    }

    /**
     * Match distance intervals to a session even when their Health Connect source
     * differs. A source contributes a candidate total only when its non-overlapping
     * intervals cover at least 90% of the session. Conflicting source totals over
     * 15% apart, overlapping records from one source, or weak time coverage leave
     * distance blank. When source totals agree, choose one stable source total;
     * never average or add totals from different apps.
     */
    internal fun resolveDistanceMeters(
        sessionStart: Instant,
        sessionEnd: Instant,
        intervals: List<DistanceInterval>,
    ): Double? {
        if (!sessionStart.isBefore(sessionEnd)) return null
        val sessionMillis = Duration.between(sessionStart, sessionEnd).toMillis()
        if (sessionMillis <= 0) return null

        val candidates = intervals
            .filter { it.meters.isFinite() && it.meters > 0.0 && it.start.isBefore(it.end) }
            .filter { interval ->
                val fullyContained = !interval.start.isBefore(sessionStart) && !interval.end.isAfter(sessionEnd)
                if (fullyContained) return@filter true
                val overlapStart = maxOf(sessionStart, interval.start)
                val overlapEnd = minOf(sessionEnd, interval.end)
                if (!overlapStart.isBefore(overlapEnd)) return@filter false
                val overlapMillis = Duration.between(overlapStart, overlapEnd).toMillis()
                val intervalMillis = Duration.between(interval.start, interval.end).toMillis()
                val shorterMillis = minOf(sessionMillis, intervalMillis)
                val longerMillis = maxOf(sessionMillis, intervalMillis)
                shorterMillis > 0 &&
                    overlapMillis.toDouble() / shorterMillis >= 0.90 &&
                    shorterMillis.toDouble() / longerMillis >= 0.80
            }
            .groupBy { it.origin }

        val sourceTotals = mutableListOf<Pair<String, Double>>()
        for ((origin, records) in candidates) {
            val ordered = records.sortedBy { it.start }
            if (ordered.zipWithNext().any { (a, b) -> b.start.isBefore(a.end) }) return null

            var coveredMillis = 0L
            var totalMeters = 0.0
            for (record in ordered) {
                val overlapStart = maxOf(sessionStart, record.start)
                val overlapEnd = minOf(sessionEnd, record.end)
                coveredMillis += Duration.between(overlapStart, overlapEnd).toMillis()
                totalMeters += record.meters
            }
            if (coveredMillis.toDouble() / sessionMillis >= 0.90 && totalMeters.isFinite() && totalMeters > 0.0) {
                sourceTotals += origin to totalMeters
            }
        }

        if (sourceTotals.isEmpty()) return null
        val distances = sourceTotals.map { BigDecimal.valueOf(it.second) }
        val smallest = distances.minOrNull() ?: return null
        val largest = distances.maxOrNull() ?: return null
        if (largest.signum() <= 0) return null
        if (largest.subtract(smallest).multiply(BigDecimal.valueOf(100)) > largest.multiply(BigDecimal.valueOf(15))) {
            return null
        }

        // Both mirrored workouts see the same total candidates, so the choice is
        // stable across session origins and cannot create a second copy by itself.
        return sourceTotals.minByOrNull { it.first }?.second
    }

    internal fun activity(type: Int): Pair<String, String?>? = when (type) {
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> "pool_swimming" to null
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "open_water_swimming" to null
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "running" to null
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "running_treadmill" to "indoor"
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "walking" to null
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> "cycling" to null
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "cycling" to "indoor"
        else -> null
    }

    private fun segmentLabel(type: Int): String? = when (type) {
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_UNKNOWN -> "unknown"
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_BIKING -> "biking"
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_BIKING_STATIONARY -> "biking_stationary"
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_RUNNING -> "running"
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_RUNNING_TREADMILL -> "running_treadmill"
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_SWIMMING_BACKSTROKE -> "swimming_backstroke"
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_SWIMMING_BREASTSTROKE -> "swimming_breaststroke"
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_SWIMMING_BUTTERFLY -> "swimming_butterfly"
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_SWIMMING_FREESTYLE -> "swimming_freestyle"
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_SWIMMING_MIXED -> "swimming_mixed"
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_SWIMMING_OPEN_WATER -> "swimming_open_water"
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_SWIMMING_OTHER -> "swimming_other"
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_SWIMMING_POOL -> "swimming_pool"
        ExerciseSegment.EXERCISE_SEGMENT_TYPE_WALKING -> "walking"
        else -> null
    }

    private fun measurement(id: String, origin: String, category: String, time: java.time.Instant,
                           offset: java.time.ZoneOffset?, name: String, value: String, unit: String) =
        MeasurementImport(id, origin, category, Mapping.isoTime(time, offset), mapOf(name to MeasurementValue(value, unit)))

    private fun decimal(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
}

internal fun sumContainedNonOverlappingIntervals(
    sessionStart: Instant,
    sessionEnd: Instant,
    intervals: List<Triple<Instant, Instant, Double>>,
): Double? {
    val matches = intervals.filter { (start, end, value) ->
        !start.isBefore(sessionStart) && !end.isAfter(sessionEnd) && start.isBefore(end) && value.isFinite() && value >= 0
    }.sortedBy { it.first }
    if (matches.isEmpty() || matches.zipWithNext().any { (a, b) -> b.first.isBefore(a.second) }) return null
    val coveredMillis = matches.sumOf { Duration.between(it.first, it.second).toMillis() }
    val sessionMillis = Duration.between(sessionStart, sessionEnd).toMillis()
    if (sessionMillis <= 0 || coveredMillis.toDouble() / sessionMillis < 0.90) return null
    return matches.sumOf { it.third }.takeIf { it.isFinite() && it > 0 }
}

private fun WorkoutDetails.hasContent() = laps.isNotEmpty() || segments.isNotEmpty() || route.isNotEmpty() ||
    totalCaloriesKcal != null || averageSpeedMps != null || maximumSpeedMps != null || speedSamples.isNotEmpty() ||
    activeCaloriesKcal != null || elevationGainM != null || floorsClimbed != null || stepCadenceSamples.isNotEmpty() ||
    cyclingCadenceSamples.isNotEmpty() || powerSamples.isNotEmpty()
