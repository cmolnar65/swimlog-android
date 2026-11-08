package com.cmolnar65.swimlog.sync

import kotlinx.serialization.SerializationException

/** Converts failures to safe, actionable text without including tokens, payloads, or server bodies. */
internal object SyncFailureMessage {
    fun from(failure: Throwable): String = when (failure) {
        is PermanentImportException -> when (failure.httpStatus) {
            401 -> "Swim Log rejected the saved app token (HTTP 401). Check whether it is still active."
            403 -> "Swim Log refused the request (HTTP 403). Check the HTTPS site and account authorization."
            404 -> "The Health Connect import endpoint was not found (HTTP 404). Check the installed Swim Log version."
            413 -> "Swim Log rejected a request that exceeded its import limits (HTTP 413)."
            400, 409 -> "Swim Log rejected the import request (HTTP ${failure.httpStatus}). No conflicting records were overwritten."
            else -> "Swim Log rejected the request (HTTP ${failure.httpStatus})."
        }
        is SecurityException -> "Health Connect denied a read. Recheck Swim Log's allowed data permissions."
        is SerializationException -> "The Swim Log response could not be read. Check the receiver version."
        is IllegalStateException -> "Local Health Connect or saved connection state prevented sync. Reconnect and try again."
        is IllegalArgumentException -> "The saved site or sync request is invalid. Check the Swim Log HTTPS site."
        else -> "Sync failed (${failure.javaClass.simpleName}). Check the network, permissions, and saved connection."
    }
}
