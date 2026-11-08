package com.cmolnar65.swimlog.sync

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cmolnar65.swimlog.security.SecretStore

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            val client = HealthConnectClient.getOrCreate(applicationContext)
            val summary = SyncEngine(SecretStore(applicationContext)).sync(client)
            SyncEngine.persistSummary(applicationContext, "Sync complete: ${summary.added} added, ${summary.duplicates} already present, ${summary.conflicts} conflicts, ${summary.rejected} rejected")
            if (summary.retryable) Result.retry() else Result.success()
        } catch (failure: TransientImportException) {
            SyncEngine.persistSummary(applicationContext, "Temporary connection problem; Android will retry")
            Result.retry()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Avoid putting exception messages in status: they may contain server-supplied details.
            SyncEngine.persistSummary(applicationContext, "Sync needs attention; open the app to check permissions and connection")
            Result.failure()
        }
    }

    companion object {
        const val UNIQUE_NAME = "swimlog-health-connect-sync"
        private const val RETRY_NAME = "swimlog-health-connect-retry"

        fun enqueueOneTime(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
        }

        fun enqueueRetry(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(RETRY_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
