package com.cmolnar65.swimlog.sync

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.io.IOException
import java.util.UUID

data class SyncSummary(
    val sent: Int,
    val added: Int,
    val duplicates: Int,
    val conflicts: Int,
    val rejected: Int,
    val retryable: Boolean,
    val mirroredWorkoutsSuppressed: Int = 0,
)

class ImportClient(private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }) {
    suspend fun send(endpoint: String, token: String, batch: ImportBatch): SyncSummary {
        if (!endpoint.equals("https://cmolnar.com", ignoreCase = true)) {
            throw SyncRequestValidationException(SyncRequestIssue.INVALID_SITE)
        }
        if (batch.workouts.any { it.heartRateSamples.size > 2000 }) {
            throw SyncRequestValidationException(SyncRequestIssue.TOO_MANY_HEART_RATE_SAMPLES)
        }
        val requests = buildRequests(batch)
        var added = 0; var duplicates = 0; var conflicts = 0; var rejected = 0; var retryable = false
        requests.forEach { request ->
            val result = post(endpoint, token, request)
            (result.results.workouts + result.results.measurements).forEach { outcome -> when (outcome.status) {
                "added" -> added++
                "duplicate" -> duplicates++
                "conflict" -> conflicts++
                "retry" -> retryable = ImportOutcomePolicy.shouldRetry(outcome.status)
                "rejected" -> rejected++
            } }
        }
        return SyncSummary(batch.workouts.size + batch.measurements.size, added, duplicates, conflicts, rejected, retryable, batch.mirroredWorkoutsSuppressed)
    }

    internal fun buildRequests(batch: ImportBatch): List<ImportBatch> {
        val pieces = mutableListOf<ImportBatch>()
        batch.workouts.forEach { pieces += ImportBatch(batchId = UUID.randomUUID().toString(), workouts = listOf(it)) }
        batch.measurements.chunked(100).forEach { pieces += ImportBatch(batchId = UUID.randomUUID().toString(), measurements = it) }
        return pieces.map { piece ->
            if (json.encodeToString(piece).toByteArray(Charsets.UTF_8).size > 2 * 1024 * 1024) {
                throw SyncRequestValidationException(SyncRequestIssue.RECORD_TOO_LARGE)
            }
            piece
        }
    }

    private suspend fun post(endpoint: String, token: String, batch: ImportBatch): ImportResponse =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val connection = (URL(endpoint.trimEnd('/') + "/wp-json/swimlog/v1/health-connect/import-batch").openConnection() as HttpURLConnection)
            try {
                connection.requestMethod = "POST"
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.doOutput = true
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("Accept", "application/json")
                val body = json.encodeToString(batch).toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
                val code = connection.responseCode
                if (code in 500..599 || code == 429) throw TransientImportException("Temporary server error")
                if (code !in 200..299) throw PermanentImportException(code)
                val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                json.decodeFromString<ImportResponse>(text)
            } catch (failure: IOException) {
                throw TransientImportException("Network request failed")
            } finally { connection.disconnect() }
        }
}

class TransientImportException(message: String) : Exception(message)
class PermanentImportException(val httpStatus: Int) : Exception("Swim Log returned HTTP $httpStatus")
enum class SyncRequestIssue { INVALID_SITE, TOO_MANY_HEART_RATE_SAMPLES, RECORD_TOO_LARGE }
class SyncRequestValidationException(val issue: SyncRequestIssue) : Exception()
