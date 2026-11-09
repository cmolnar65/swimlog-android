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
    val details: WorkoutDetails? = null,
)

@Serializable
data class WorkoutDetails(
    val laps: List<WorkoutLap> = emptyList(),
    val segments: List<WorkoutSegment> = emptyList(),
    val route: List<RoutePoint> = emptyList(),
    @SerialName("total_calories_kcal") val totalCaloriesKcal: String? = null,
    @SerialName("average_speed_mps") val averageSpeedMps: String? = null,
    @SerialName("maximum_speed_mps") val maximumSpeedMps: String? = null,
    @SerialName("speed_samples") val speedSamples: List<SpeedSample> = emptyList(),
    @SerialName("active_calories_kcal") val activeCaloriesKcal: String? = null,
    @SerialName("elevation_gain_m") val elevationGainM: String? = null,
    @SerialName("floors_climbed") val floorsClimbed: String? = null,
    @SerialName("step_cadence_samples") val stepCadenceSamples: List<WorkoutMetricSample> = emptyList(),
    @SerialName("cycling_cadence_samples") val cyclingCadenceSamples: List<WorkoutMetricSample> = emptyList(),
    @SerialName("power_samples") val powerSamples: List<WorkoutMetricSample> = emptyList(),
)

@Serializable
data class SpeedSample(
    val time: String,
    @SerialName("meters_per_second") val metersPerSecond: Double,
)

@Serializable
data class WorkoutMetricSample(
    val time: String,
    val value: Double,
)

@Serializable
data class WorkoutLap(
    @SerialName("start_time") val startTime: String,
    @SerialName("end_time") val endTime: String,
    @SerialName("distance_m") val distanceM: String? = null,
)

@Serializable
data class WorkoutSegment(
    @SerialName("start_time") val startTime: String,
    @SerialName("end_time") val endTime: String,
    @SerialName("segment_type") val segmentType: Int,
    @SerialName("segment_label") val segmentLabel: String? = null,
    val repetitions: Int? = null,
)

@Serializable
data class RoutePoint(
    val time: String,
    val latitude: Double,
    val longitude: Double,
    @SerialName("altitude_m") val altitudeM: Double? = null,
    @SerialName("horizontal_accuracy_m") val horizontalAccuracyM: Double? = null,
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
