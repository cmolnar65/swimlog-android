package com.cmolnar65.swimlog.sync

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.io.IOException
import java.util.UUID

data class SyncSummary(val sent: Int, val added: Int, val duplicates: Int, val conflicts: Int, val rejected: Int, val retryable: Boolean)

class ImportClient(private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }) {
    suspend fun send(endpoint: String, token: String, batch: ImportBatch): SyncSummary {
        require(endpoint.equals("https://cmolnar.com", ignoreCase = true)) { "Use the official https://cmolnar.com site" }
        require(batch.workouts.size <= 50 && batch.measurements.size <= 500) { "Batch count limit exceeded" }
        require(batch.workouts.all { it.heartRateSamples.size <= 2000 }) { "Heart-rate sample limit exceeded" }
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
        return SyncSummary(batch.workouts.size + batch.measurements.size, added, duplicates, conflicts, rejected, retryable)
    }

    private fun buildRequests(batch: ImportBatch): List<ImportBatch> {
        val pieces = mutableListOf<ImportBatch>()
        batch.workouts.forEach { pieces += ImportBatch(batchId = UUID.randomUUID().toString(), workouts = listOf(it)) }
        batch.measurements.chunked(100).forEach { pieces += ImportBatch(batchId = UUID.randomUUID().toString(), measurements = it) }
        return pieces.map { piece ->
            require(json.encodeToString(piece).toByteArray(Charsets.UTF_8).size <= 512 * 1024) { "One record exceeds receiver request limit" }
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
