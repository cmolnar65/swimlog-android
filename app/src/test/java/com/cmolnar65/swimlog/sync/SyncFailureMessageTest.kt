package com.cmolnar65.swimlog.sync

import org.junit.Assert.assertTrue
import org.junit.Test

class SyncFailureMessageTest {
    @Test
    fun unauthorizedResponseSuggestsCheckingTokenWithoutExposingIt() {
        val message = SyncFailureMessage.from(PermanentImportException(401))

        assertTrue(message.contains("HTTP 401"))
        assertTrue(message.contains("still active"))
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
}
