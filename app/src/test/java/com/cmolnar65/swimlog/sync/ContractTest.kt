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

    @Test fun classifiesOnlyApprovedExerciseTypes() {
        assertEquals("pool_swimming", HealthMapper.activity(ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL)?.first)
        assertEquals("open_water_swimming", HealthMapper.activity(ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER)?.first)
        assertEquals("running", HealthMapper.activity(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING)?.first)
        assertEquals("running_treadmill", HealthMapper.activity(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL)?.first)
        assertEquals("walking", HealthMapper.activity(ExerciseSessionRecord.EXERCISE_TYPE_WALKING)?.first)
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
