package com.cmolnar65.swimlog.sync

import org.junit.Assert.assertTrue
import org.junit.Test

class SyncFailureMessageTest {
    @Test
    fun unauthorizedResponseSuggestsSigningInWithoutExposingToken() {
        val message = SyncFailureMessage.from(PermanentImportException(401))

        assertTrue(message.contains("HTTP 401"))
        assertTrue(message.contains("Sign in again"))
        assertTrue(!message.contains("slhc_"))
    }

    @Test
    fun missingEndpointIsDistinguishedFromPermissionFailure() {
        val message = SyncFailureMessage.from(PermanentImportException(404))

        assertTrue(message.contains("endpoint was not found"))
        assertTrue(!message.contains("permissions"))
    }

    @Test
    fun permissionFailureDoesNotIncludeExceptionDetails() {
        val message = SyncFailureMessage.from(SecurityException("private detail"))

        assertTrue(message.contains("Health Connect denied a read"))
        assertTrue(!message.contains("private detail"))
    }

    @Test
    fun heartRateSampleLimitIsExplainedWithoutDroppingSamples() {
        val message = SyncFailureMessage.from(
            SyncRequestValidationException(SyncRequestIssue.TOO_MANY_HEART_RATE_SAMPLES)
        )

        assertTrue(message.contains("2,000 heart-rate-sample limit"))
        assertTrue(message.contains("No records were sent"))
        assertTrue(message.contains("not trimmed"))
    }

    @Test
    fun largerOverallBatchIsSplitIntoReceiverSizedRequests() {
        val batch = ImportBatch(
            batchId = "synthetic-large-batch",
            measurements = (1..501).map { index ->
                MeasurementImport(
                    recordId = "record-$index",
                    origin = "synthetic.app",
                    category = "weight",
                    time = "2026-11-01T00:00:00Z",
                    values = mapOf("weight" to MeasurementValue("70", "kg")),
                )
            },
        )

        val requests = ImportClient().buildRequests(batch)

        assertTrue(requests.size == 6)
        assertTrue(requests.all { it.measurements.size <= 100 })
        assertTrue(requests.sumOf { it.measurements.size } == 501)
    }
}
