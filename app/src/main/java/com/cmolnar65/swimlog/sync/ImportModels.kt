package com.cmolnar65.swimlog.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ImportBatch(
    @SerialName("contract_version") val contractVersion: Int = 1,
    @SerialName("batch_id") val batchId: String,
    val workouts: List<WorkoutImport> = emptyList(),
    val measurements: List<MeasurementImport> = emptyList(),
    @kotlinx.serialization.Transient val mirroredWorkoutsSuppressed: Int = 0,
)

@Serializable
data class WorkoutImport(
    @SerialName("record_id") val recordId: String,
    val origin: String,
    val activity: String,
    val environment: String? = null,
    @SerialName("start_time") val startTime: String,
    @SerialName("end_time") val endTime: String,
    @SerialName("distance_m") val distanceM: String? = null,
    @SerialName("heart_rate_samples") val heartRateSamples: List<HeartRateSample> = emptyList(),
)

@Serializable
data class HeartRateSample(
    @SerialName("record_id") val recordId: String,
    val origin: String,
    val time: String,
    val bpm: Int,
)

@Serializable
data class MeasurementImport(
    @SerialName("record_id") val recordId: String,
    val origin: String,
    val category: String,
    val time: String,
    val values: Map<String, MeasurementValue>,
)

@Serializable
data class MeasurementValue(val value: String, val unit: String)

@Serializable
data class ImportResponse(val results: ImportOutcomes = ImportOutcomes())

@Serializable
data class ImportOutcomes(
    val workouts: List<ImportResult> = emptyList(),
    val measurements: List<ImportResult> = emptyList(),
)

@Serializable
data class ImportResult(val status: String, val code: String? = null)
