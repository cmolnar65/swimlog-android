package com.cmolnar65.swimlog.sync

import androidx.health.connect.client.HealthConnectClient
import com.cmolnar65.swimlog.security.SecretStore
import java.time.Instant

class SyncEngine(private val store: SecretStore) {
    suspend fun sync(client: HealthConnectClient): SyncSummary {
        val endpoint = store.endpoint() ?: error("Save the Swim Log site first")
        val token = store.token() ?: error("Save the owner-scoped token first")
        val granted = client.permissionController.getGrantedPermissions()
        if (granted.intersect(HealthConnectPermissions.reads).isEmpty()) error("No Health Connect read permissions are granted")
        val records = HealthReader(client).readLast30Days(granted)
        val payload = HealthMapper.map(records)
        if (payload.workouts.isEmpty() && payload.measurements.isEmpty()) {
            return SyncSummary(0, 0, 0, 0, 0, false, payload.mirroredWorkoutsSuppressed)
        }
        return ImportClient().send(endpoint, token, payload)
    }

    companion object {
        fun persistSummary(context: android.content.Context, value: String) {
            context.getSharedPreferences("sync_summary", android.content.Context.MODE_PRIVATE)
                .edit().putString("last", "$value • ${Instant.now()}").apply()
        }
    }
}
