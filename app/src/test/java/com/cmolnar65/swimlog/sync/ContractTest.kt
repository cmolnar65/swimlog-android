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
}
