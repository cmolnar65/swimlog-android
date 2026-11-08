package com.cmolnar65.swimlog.sync

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object Mapping {
    fun isoTime(instant: Instant, offset: ZoneOffset?): String =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(instant.atOffset(offset ?: ZoneOffset.UTC))
}

object ImportOutcomePolicy {
    /** Only the receiver's explicit transient result is retryable; conflict is never auto-corrected. */
    fun shouldRetry(status: String): Boolean = status == "retry"
}
