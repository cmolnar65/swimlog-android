package com.cmolnar65.swimlog.sync

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.permission.HealthPermission
import java.time.Instant
import java.time.temporal.ChronoUnit

object HealthConnectPermissions {
    val reads = setOf(
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(BodyFatRecord::class),
        HealthPermission.getReadPermission(LeanBodyMassRecord::class),
        HealthPermission.getReadPermission(BloodPressureRecord::class),
    )
}

class HealthReader(private val client: HealthConnectClient) {
    suspend fun readLast30Days(granted: Set<String>): HealthRecords {
        val end = Instant.now()
        val start = end.minus(30, ChronoUnit.DAYS)
        suspend fun <T : androidx.health.connect.client.records.Record> read(type: kotlin.reflect.KClass<T>): List<T> =
            client.readRecords(ReadRecordsRequest(type, timeRangeFilter = TimeRangeFilter.between(start, end))).records
        suspend fun <T : androidx.health.connect.client.records.Record> readIf(type: kotlin.reflect.KClass<T>): List<T> =
            if (HealthPermission.getReadPermission(type) in granted) read(type) else emptyList()
        return HealthRecords(
            sessions = readIf(ExerciseSessionRecord::class),
            distances = readIf(DistanceRecord::class),
            heartRates = readIf(HeartRateRecord::class),
            weights = readIf(WeightRecord::class),
            bodyFats = readIf(BodyFatRecord::class),
            leanMasses = readIf(LeanBodyMassRecord::class),
            bloodPressures = readIf(BloodPressureRecord::class),
        )
    }
}
