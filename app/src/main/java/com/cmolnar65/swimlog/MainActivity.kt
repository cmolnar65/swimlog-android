package com.cmolnar65.swimlog

import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.lifecycle.lifecycleScope
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.cmolnar65.swimlog.security.SecretStore
import com.cmolnar65.swimlog.sync.SyncWorker
import com.cmolnar65.swimlog.sync.HealthConnectPermissions
import com.cmolnar65.swimlog.sync.SyncEngine
import com.cmolnar65.swimlog.sync.SyncFailureMessage
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val secretStore by lazy { SecretStore(this) }
    private lateinit var status: TextView
    private lateinit var endpoint: EditText
    private lateinit var token: EditText
    private var connectAfterRead = false
    private var syncAfterPermission = false

    private val healthPermissions = HealthConnectPermissions.reads
    private val readPermissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        status.text = if (granted.containsAll(healthPermissions)) "Health Connect read access granted." else "Some Health Connect permissions were denied. Sync will omit unavailable types."
        if (connectAfterRead && granted.any { it in healthPermissions }) requestBackgroundPermission()
        connectAfterRead = false
        if (syncAfterPermission) runForegroundSync()
        syncAfterPermission = false
    }
    private val backgroundPermissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in granted) enableBackgroundSync()
        status.text = if (HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in granted) "Background sync enabled; Android may defer scheduled work." else "Background access was not granted. Use Sync now while the app is open."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 24) }
        status = TextView(this).apply { text = getSharedPreferences("sync_summary", MODE_PRIVATE).getString("last", "Health Connect sends selected records to your private Swim Log account.") }
        endpoint = EditText(this).apply { hint = "Swim Log HTTPS site"; setText(secretStore.endpoint() ?: "https://cmolnar.com"); setSingleLine(true) }
        token = EditText(this).apply { hint = "Owner-scoped app token"; inputType = 129; setSingleLine(true) }
        root.addView(TextView(this).apply { text = "Destination" })
        root.addView(endpoint, matchWidth())
        root.addView(token, matchWidth())
        root.addView(button("Save connection") { saveConnection() })
        root.addView(button("Connect Health Connect") { requestReadPermissions(andBackground = true) })
        root.addView(button("Sync now") { syncNow() })
        root.addView(button("Enable background sync") { requestBackgroundPermission() })
        root.addView(button("Disable background sync") { WorkManager.getInstance(this).cancelUniqueWork(SyncWorker.UNIQUE_NAME); status.text = "Background sync disabled." })
        root.addView(button("Forget token and settings") { secretStore.clear(); token.text.clear(); endpoint.setText("https://cmolnar.com"); WorkManager.getInstance(this).cancelUniqueWork(SyncWorker.UNIQUE_NAME); status.text = "Saved connection removed. Health Connect permissions can be revoked in Health Connect." })
        root.addView(status, matchWidth())
        root.addView(TextView(this).apply { text = "Read-only: swimming/running/walking sessions, matched distance and workout heart rate, weight, body fat, lean mass, and blood pressure. Records stay on this device only while being sent over HTTPS. No cycling, GPS routes, Health Connect writes, or records older than 30 days." })
        setContentView(root)
    }

    private fun requestReadPermissions(andBackground: Boolean) {
        lifecycleScope.launch {
            val sdk = HealthConnectClient.getSdkStatus(this@MainActivity, PROVIDER_PACKAGE)
            if (sdk != HealthConnectClient.SDK_AVAILABLE) {
                status.text = "Health Connect is unavailable. Install or update Health Connect, then try again."
                return@launch
            }
            val client = HealthConnectClient.getOrCreate(this@MainActivity)
            if (client.permissionController.getGrantedPermissions().containsAll(healthPermissions)) {
                status.text = "Health Connect read access is already granted."
                if (andBackground) requestBackgroundPermission()
            } else {
                connectAfterRead = andBackground
                readPermissionLauncher.launch(healthPermissions)
            }
        }
    }

    private fun requestBackgroundPermission() {
        lifecycleScope.launch {
            val client = runCatching { HealthConnectClient.getOrCreate(this@MainActivity) }.getOrNull()
            if (client == null) { status.text = "Health Connect is unavailable."; return@launch }
            val granted = client.permissionController.getGrantedPermissions()
            if (granted.none { it in healthPermissions }) { status.text = "Grant at least one selected data read permission first."; return@launch }
            val backgroundFeature = client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND)
            if (backgroundFeature != HealthConnectFeatures.FEATURE_STATUS_AVAILABLE) {
                status.text = "This Health Connect version does not support background reads. Use Sync now while the app is open."
                return@launch
            }
            backgroundPermissionLauncher.launch(setOf(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND))
        }
    }

    private fun saveConnection() {
        try {
            secretStore.save(endpoint.text.toString().trim(), token.text.toString())
            token.text.clear()
            status.text = "Connection saved securely on this device."
        } catch (error: IllegalArgumentException) { status.text = error.message ?: "Check the HTTPS site and token." }
    }

    private fun syncNow() {
        if (secretStore.token().isNullOrBlank() || secretStore.endpoint().isNullOrBlank()) {
            status.text = "Save your Swim Log site and owner-scoped token first."; return
        }
        lifecycleScope.launch {
            val sdk = HealthConnectClient.getSdkStatus(this@MainActivity, PROVIDER_PACKAGE)
            if (sdk != HealthConnectClient.SDK_AVAILABLE) { status.text = "Health Connect is unavailable."; return@launch }
            val client = HealthConnectClient.getOrCreate(this@MainActivity)
            if (client.permissionController.getGrantedPermissions().none { it in healthPermissions }) {
                syncAfterPermission = true
                readPermissionLauncher.launch(healthPermissions)
            } else runForegroundSync()
        }
    }

    private fun runForegroundSync() {
        lifecycleScope.launch {
            status.text = "Sync running. Health values are not shown here."
            try {
                val client = HealthConnectClient.getOrCreate(this@MainActivity)
                val summary = SyncEngine(secretStore).sync(client)
                val canRetryInBackground = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in client.permissionController.getGrantedPermissions()
                if (summary.retryable && canRetryInBackground) SyncWorker.enqueueRetry(this@MainActivity)
                val retryText = if (!summary.retryable) "" else if (canRetryInBackground) "; transient items will retry" else "; tap Sync now again to retry transient items"
                val mirrorText = if (summary.mirroredWorkoutsSuppressed == 0) "" else "; ${summary.mirroredWorkoutsSuppressed} identical mirrored workout copies skipped"
                val message = "Sync complete: ${summary.added} added, ${summary.duplicates} already present, ${summary.conflicts} conflicts, ${summary.rejected} rejected$retryText$mirrorText."
                status.text = message
                SyncEngine.persistSummary(this@MainActivity, message)
            } catch (_: com.cmolnar65.swimlog.sync.TransientImportException) {
                val canRetry = runCatching {
                    HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in
                        HealthConnectClient.getOrCreate(this@MainActivity).permissionController.getGrantedPermissions()
                }.getOrDefault(false)
                if (canRetry) SyncWorker.enqueueRetry(this@MainActivity)
                val message = if (canRetry) "Temporary connection problem; Android will retry." else "Temporary connection problem. Tap Sync now again when connected."
                status.text = message
                SyncEngine.persistSummary(this@MainActivity, message)
            } catch (failure: Exception) {
                status.text = SyncFailureMessage.from(failure)
                SyncEngine.persistSummary(this@MainActivity, status.text.toString())
            }
        }
    }

    private fun enableBackgroundSync() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(SyncWorker.UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply { text = label; setOnClickListener { action() } }
    private fun matchWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    companion object { private const val PROVIDER_PACKAGE = "com.google.android.apps.healthdata" }
}
