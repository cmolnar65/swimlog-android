package com.cmolnar65.swimlog.sync

import androidx.health.connect.client.records.ExerciseSessionRecord
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContractTest {
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    private val distanceSessionStart = Instant.parse("2026-11-08T16:00:00Z")
    private val distanceSessionEnd = Instant.parse("2026-11-08T17:00:00Z")

    @Test fun classifiesOnlyApprovedExerciseTypes() {
        assertEquals("pool_swimming", HealthMapper.activity(ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL)?.first)
        assertEquals("open_water_swimming", HealthMapper.activity(ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER)?.first)
        assertEquals("running", HealthMapper.activity(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING)?.first)
        assertEquals("running_treadmill", HealthMapper.activity(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL)?.first)
        assertEquals("walking", HealthMapper.activity(ExerciseSessionRecord.EXERCISE_TYPE_WALKING)?.first)
        assertEquals("cycling", HealthMapper.activity(ExerciseSessionRecord.EXERCISE_TYPE_BIKING)?.first)
        assertEquals("cycling", HealthMapper.activity(ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY)?.first)
        assertEquals("indoor", HealthMapper.activity(ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY)?.second)
        assertEquals(null, HealthMapper.activity(Int.MAX_VALUE))
    }

    @Test fun serializesV1FieldsAndNeverIncludesOwnerIdentity() {
        val payload = ImportBatch(
            batchId = "synthetic-batch",
            workouts = listOf(WorkoutImport("hc-1", "sample.app", "pool_swimming", null,
                "2026-11-01T09:00:00-04:00", "2026-11-01T09:30:00-04:00")),
            measurements = listOf(MeasurementImport("hc-2", "scale.app", "weight",
                "2026-11-01T08:00:00Z", mapOf("weight" to MeasurementValue("72.5", "kg")))),
        )
        val encoded = json.encodeToString(payload)
        assertTrue(encoded.contains("\"contract_version\":1"))
        assertTrue(encoded.contains("\"record_id\":\"hc-1\""))
        assertTrue(encoded.contains("\"weight\":{\"value\":\"72.5\",\"unit\":\"kg\"}"))
        assertFalse(encoded.contains("owner_id"))
        assertFalse(encoded.contains("mirroredWorkoutsSuppressed"))
        assertFalse(encoded.contains("pool_length"))
        assertFalse(encoded.contains("route"))
    }

    @Test fun preservesExplicitLocalOffsetAndUsesUtcWhenAbsent() {
        val instant = Instant.parse("2026-11-01T13:00:00Z")
        assertEquals("2026-11-01T09:00:00-04:00", Mapping.isoTime(instant, ZoneOffset.ofHours(-4)))
        assertEquals("2026-11-01T13:00:00Z", Mapping.isoTime(instant, null))
    }

    @Test fun workoutSummaryIntervalsMustCoverSessionWithoutOverlap() {
        val midpoint = distanceSessionStart.plusSeconds(30 * 60)
        val complete = listOf(
            Triple(distanceSessionStart, midpoint, 125.0),
            Triple(midpoint, distanceSessionEnd, 75.0),
        )
        assertEquals(200.0, sumContainedNonOverlappingIntervals(distanceSessionStart, distanceSessionEnd, complete)!!, 0.0)
        assertEquals(null, sumContainedNonOverlappingIntervals(distanceSessionStart, distanceSessionEnd, listOf(
            Triple(distanceSessionStart, distanceSessionStart.plusSeconds(30 * 60), 10.0),
            Triple(distanceSessionStart.plusSeconds(20 * 60), distanceSessionEnd, 20.0),
        )))
        assertEquals(null, sumContainedNonOverlappingIntervals(distanceSessionStart, distanceSessionEnd, listOf(
            Triple(distanceSessionStart, distanceSessionStart.plusSeconds(50 * 60), 10.0),
        )))
    }

    @Test fun serializesOptionalWorkoutDetailsWithoutChangingContractVersionOrAddingOwnerData() {
        val payload = ImportBatch(
            batchId = "detail-batch",
            workouts = listOf(WorkoutImport(
                recordId = "hc-run-1",
                origin = "watch.app",
                activity = "cycling",
                environment = "indoor",
                startTime = "2026-11-01T09:00:00-06:00",
                endTime = "2026-11-01T10:00:00-06:00",
                details = WorkoutDetails(
                    laps = listOf(WorkoutLap("2026-11-01T09:00:00-06:00", "2026-11-01T09:15:00-06:00", "4200")),
                    segments = listOf(WorkoutSegment("2026-11-01T09:00:00-06:00", "2026-11-01T09:15:00-06:00", 7)),
                    route = listOf(RoutePoint("2026-11-01T09:05:00-06:00", 39.7, -105.1, 1600.0, 4.0)),
                    totalCaloriesKcal = "350",
                    activeCaloriesKcal = "320",
                    elevationGainM = "125",
                    floorsClimbed = "3",
                    averageSpeedMps = "4.2",
                    maximumSpeedMps = "6.1",
                    speedSamples = listOf(SpeedSample("2026-11-01T09:05:00-06:00", 4.2)),
                    stepCadenceSamples = listOf(WorkoutMetricSample("2026-11-01T09:05:00-06:00", 160.0)),
                    cyclingCadenceSamples = listOf(WorkoutMetricSample("2026-11-01T09:05:00-06:00", 82.0)),
                    powerSamples = listOf(WorkoutMetricSample("2026-11-01T09:05:00-06:00", 210.0)),
                ),
            )),
        )
        val encoded = json.encodeToString(payload)
        assertTrue(encoded.contains("\"activity\":\"cycling\""))
        assertTrue(encoded.contains("\"laps\":"))
        assertTrue(encoded.contains("\"segments\":"))
        assertTrue(encoded.contains("\"latitude\":39.7"))
        assertTrue(encoded.contains("\"total_calories_kcal\":\"350\""))
        assertTrue(encoded.contains("\"active_calories_kcal\":\"320\""))
        assertTrue(encoded.contains("\"elevation_gain_m\":\"125\""))
        assertTrue(encoded.contains("\"floors_climbed\":\"3\""))
        assertTrue(encoded.contains("\"step_cadence_samples\":"))
        assertTrue(encoded.contains("\"cycling_cadence_samples\":"))
        assertTrue(encoded.contains("\"power_samples\":"))
        assertTrue(encoded.contains("\"meters_per_second\":4.2"))
        assertFalse(encoded.contains("owner_id"))
    }

    @Test fun matchesDistanceFromDifferentSourceWhenItsIntervalCoversWorkout() {
        val distance = DistanceInterval(
            origin = "watch.app",
            start = distanceSessionStart,
            end = distanceSessionEnd,
            meters = 1500.0,
        )

        assertEquals(
            1500.0,
            HealthMapper.resolveDistanceMeters(distanceSessionStart, distanceSessionEnd, listOf(distance))!!,
            0.0,
        )
    }

    @Test fun sumsContainedNonOverlappingDistanceSegmentsWithNearCompleteTimeCoverage() {
        val middle = distanceSessionStart.plusSeconds(30 * 60)
        val distances = listOf(
            DistanceInterval("watch.app", distanceSessionStart, middle, 1200.0),
            DistanceInterval("watch.app", middle, distanceSessionEnd, 1300.0),
        )

        assertEquals(
            2500.0,
            HealthMapper.resolveDistanceMeters(distanceSessionStart, distanceSessionEnd, distances)!!,
            0.0,
        )
    }

    @Test fun leavesDistanceBlankForWeakIntervalAssociation() {
        val weakMatch = DistanceInterval(
            origin = "phone.app",
            start = distanceSessionStart.minusSeconds(30 * 60),
            end = distanceSessionStart.plusSeconds(30 * 60),
            meters = 900.0,
        )

        assertEquals(null, HealthMapper.resolveDistanceMeters(distanceSessionStart, distanceSessionEnd, listOf(weakMatch)))
    }

    @Test fun leavesDistanceBlankWhenSupportedSourceTotalsDifferByMoreThanFifteenPercent() {
        val distances = listOf(
            DistanceInterval("source.a", distanceSessionStart, distanceSessionEnd, 5000.0),
            DistanceInterval("source.b", distanceSessionStart, distanceSessionEnd, 5900.0),
        )

        assertEquals(null, HealthMapper.resolveDistanceMeters(distanceSessionStart, distanceSessionEnd, distances))
    }

    @Test fun choosesOneStableSourceTotalWhenSupportedTotalsAreWithinFifteenPercent() {
        val distances = listOf(
            DistanceInterval("z.watch", distanceSessionStart, distanceSessionEnd, 5000.0),
            DistanceInterval("a.phone", distanceSessionStart, distanceSessionEnd, 5600.0),
        )

        assertEquals(
            5600.0,
            HealthMapper.resolveDistanceMeters(distanceSessionStart, distanceSessionEnd, distances)!!,
            0.0,
        )
    }

    @Test fun leavesDistanceBlankForOverlappingRecordsFromOneSource() {
        val distances = listOf(
            DistanceInterval("watch.app", distanceSessionStart, distanceSessionStart.plusSeconds(45 * 60), 3500.0),
            DistanceInterval("watch.app", distanceSessionStart.plusSeconds(15 * 60), distanceSessionEnd, 3400.0),
        )

        assertEquals(null, HealthMapper.resolveDistanceMeters(distanceSessionStart, distanceSessionEnd, distances))
    }

    @Test fun retriesOnlyExplicitTransientStatus() {
        assertTrue(ImportOutcomePolicy.shouldRetry("retry"))
        assertFalse(ImportOutcomePolicy.shouldRetry("conflict"))
        assertFalse(ImportOutcomePolicy.shouldRetry("duplicate"))
        assertFalse(ImportOutcomePolicy.shouldRetry("rejected"))
    }

    @Test fun collapsesNearIdenticalCrossSourceIntervalsAndPreservesConflictingCopies() {
        val first = WorkoutImport(
            recordId = "session-a",
            origin = "source.a",
            activity = "walking",
            startTime = "2026-11-08T09:00:00-07:00",
            endTime = "2026-11-08T09:45:00-07:00",
            distanceM = "3000",
        )
        val nearMirror = first.copy(
            recordId = "session-b",
            origin = "source.b",
            endTime = "2026-11-08T09:45:00.260-07:00",
        )
        val differingDistance = first.copy(recordId = "session-c", origin = "source.c", distanceM = "3500")

        val result = HealthMapper.collapseMirroredWorkoutCopies(listOf(first, nearMirror, differingDistance))

        assertEquals(2, result.size)
        assertTrue(result.any { it.recordId == "session-a" })
        assertTrue(result.any { it.recordId == "session-c" })
    }

    @Test fun collapsesHighOverlapSessionsWhenDistanceIsUnavailable() {
        // Synthetic sessions with a ten-minute start offset and about 94% overlap.
        val earlier = WorkoutImport(
            recordId = "session-early",
            origin = "source.a",
            activity = "walking",
            startTime = "2026-01-01T08:57:00Z",
            endTime = "2026-01-01T10:03:05Z",
        )
        val later = WorkoutImport(
            recordId = "session-later",
            origin = "source.b",
            activity = "walking",
            startTime = "2026-01-01T09:07:00Z",
            endTime = "2026-01-01T10:06:48Z",
        )

        val result = HealthMapper.collapseMirroredWorkoutCopies(listOf(earlier, later))

        assertEquals(1, result.size)
    }

    @Test fun distanceMatchedOverlappingSessionsFromDifferentSourcesAreMirrors() {
        val first = WorkoutImport(
            recordId = "session-a",
            origin = "source.a",
            activity = "walking",
            startTime = "2026-11-08T09:00:00Z",
            endTime = "2026-11-08T10:00:00Z",
            distanceM = "5200",
        )
        val mirror = first.copy(
            recordId = "session-b",
            origin = "source.b",
            startTime = "2026-11-08T09:00:20Z",
            endTime = "2026-11-08T10:00:20Z",
        )

        assertEquals(1, HealthMapper.collapseMirroredWorkoutCopies(listOf(first, mirror)).size)
    }

    @Test fun acceptsDistanceDifferencesUpToTenPercentButPreservesLargerDifferences() {
        val first = WorkoutImport(
            recordId = "session-a",
            origin = "source.a",
            activity = "walking",
            startTime = "2026-11-08T09:00:00Z",
            endTime = "2026-11-08T10:00:00Z",
            distanceM = "5200",
        )
        val closeDistanceMirror = first.copy(
            recordId = "session-b",
            origin = "source.b",
            startTime = "2026-11-08T09:00:20Z",
            endTime = "2026-11-08T10:00:20Z",
            distanceM = "4680",
        )
        val distantCopy = closeDistanceMirror.copy(recordId = "session-c", origin = "source.c", distanceM = "4600")

        assertEquals(1, HealthMapper.collapseMirroredWorkoutCopies(listOf(first, closeDistanceMirror)).size)
        assertEquals(2, HealthMapper.collapseMirroredWorkoutCopies(listOf(first, distantCopy)).size)
    }

    @Test fun preservesSameSourceAndMateriallyDifferentOrNonOverlappingSessions() {
        val first = WorkoutImport(
            recordId = "session-a",
            origin = "source.a",
            activity = "walking",
            startTime = "2026-11-08T09:00:00Z",
            endTime = "2026-11-08T09:00:20Z",
            distanceM = "100",
        )
        val sameOrigin = first.copy(recordId = "session-b")
        val differentDistance = first.copy(recordId = "session-c", origin = "source.c", distanceM = "6000")
        val nonOverlapping = first.copy(
            recordId = "session-d",
            origin = "source.d",
            startTime = "2026-11-08T09:00:21Z",
            endTime = "2026-11-08T09:00:41Z",
        )

        assertEquals(4, HealthMapper.collapseMirroredWorkoutCopies(listOf(first, sameOrigin, differentDistance, nonOverlapping)).size)
    }

    @Test fun preservesShortContainedSessionThatDiffersSubstantiallyInDuration() {
        val long = WorkoutImport(
            recordId = "session-long",
            origin = "source.a",
            activity = "walking",
            startTime = "2026-11-08T09:00:00Z",
            endTime = "2026-11-08T10:00:00Z",
            distanceM = "5200",
        )
        val shorter = long.copy(
            recordId = "session-short",
            origin = "source.b",
            startTime = "2026-11-08T09:10:00Z",
            endTime = "2026-11-08T09:50:00Z",
        )

        assertEquals(2, HealthMapper.collapseMirroredWorkoutCopies(listOf(long, shorter)).size)
    }

    @Test fun mirroredWorkoutDeduplicationComparesHeartRateContentButNotSourceIds() {
        val first = WorkoutImport(
            recordId = "session-a",
            origin = "source.a",
            activity = "pool_swimming",
            startTime = "2026-11-08T09:00:00Z",
            endTime = "2026-11-08T09:30:00Z",
            heartRateSamples = listOf(HeartRateSample("hr-a", "source.a", "2026-11-08T09:10:00Z", 120)),
        )
        val mirror = first.copy(
            recordId = "session-b",
            origin = "source.b",
            heartRateSamples = listOf(HeartRateSample("hr-b", "source.b", "2026-11-08T09:10:00Z", 120)),
        )

        assertEquals(1, HealthMapper.collapseMirroredWorkoutCopies(listOf(first, mirror)).size)
        val differingHeartRate = mirror.copy(heartRateSamples = listOf(HeartRateSample("hr-c", "source.b", "2026-11-08T09:10:00Z", 121)))
        assertEquals(2, HealthMapper.collapseMirroredWorkoutCopies(listOf(first, differingHeartRate)).size)
    }
}
