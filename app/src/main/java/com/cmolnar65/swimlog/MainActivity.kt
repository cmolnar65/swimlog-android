package com.cmolnar65.swimlog

import android.os.Bundle
import android.content.Intent
import android.net.Uri
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
import androidx.health.connect.client.contracts.ExerciseRouteRequestContract
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.permission.HealthPermission
import androidx.lifecycle.lifecycleScope
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.cmolnar65.swimlog.auth.AuthorizationCallback
import com.cmolnar65.swimlog.auth.AuthorizationNetworkException
import com.cmolnar65.swimlog.auth.AuthorizationTokenException
import com.cmolnar65.swimlog.auth.HealthConnectAuthorizationClient
import com.cmolnar65.swimlog.auth.HealthConnectAuthorizationProtocol
import com.cmolnar65.swimlog.security.SecretStore
import com.cmolnar65.swimlog.sync.SyncWorker
import com.cmolnar65.swimlog.sync.HealthConnectPermissions
import com.cmolnar65.swimlog.sync.SyncEngine
import com.cmolnar65.swimlog.sync.SyncFailureMessage
import com.cmolnar65.swimlog.sync.HealthReader
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val secretStore by lazy { SecretStore(this) }
    private val authorizationClient by lazy { HealthConnectAuthorizationClient() }
    private lateinit var status: TextView
    private lateinit var endpoint: EditText
    private var connectAfterRead = false
    private var syncAfterPermission = false
    private var routeConsentQueue: List<String> = emptyList()
    private var routeConsentIndex = 0
    private var routeOverrides: MutableMap<String, ExerciseRoute> = mutableMapOf()
    private var routeSyncClient: HealthConnectClient? = null

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
    private val exerciseRouteLauncher = registerForActivityResult(ExerciseRouteRequestContract()) { route ->
        val sessionId = routeConsentQueue.getOrNull(routeConsentIndex)
        if (sessionId != null && route != null) routeOverrides[sessionId] = route
        routeConsentIndex++
        requestNextRouteOrSync()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 24) }
        status = TextView(this).apply {
            text = if (!secretStore.token().isNullOrBlank()) "Swim Log is connected on this device." else "Sign in to connect Health Connect to your private Swim Log account."
        }
        endpoint = EditText(this).apply { hint = "Swim Log HTTPS site"; setText(secretStore.endpoint() ?: "https://cmolnar.com"); setSingleLine(true) }
        root.addView(TextView(this).apply { text = "Destination" })
        root.addView(endpoint, matchWidth())
        root.addView(button("Save site") { saveSite() })
        root.addView(button("Sign in to Swim Log") { signInToSwimLog() })
        root.addView(TextView(this).apply { text = "Sign-in opens your browser. The app never asks for your WordPress password. A successful new authorization replaces your previous Health Connect app token." })
        root.addView(button("Connect Health Connect") { requestReadPermissions(andBackground = true) })
        root.addView(button("Sync now") { syncNow() })
        root.addView(button("Enable background sync") { requestBackgroundPermission() })
        root.addView(button("Disable background sync") { WorkManager.getInstance(this).cancelUniqueWork(SyncWorker.UNIQUE_NAME); status.text = "Background sync disabled." })
        root.addView(button("Forget saved connection") {
            secretStore.clear()
            endpoint.setText("https://cmolnar.com")
            WorkManager.getInstance(this).cancelUniqueWork(SyncWorker.UNIQUE_NAME)
            status.text = "Saved connection removed from this device. Revoke the app token in Swim Log settings if you also want to invalidate it on the server."
        })
        root.addView(status, matchWidth())
        root.addView(TextView(this).apply { text = "Read-only: swimming, running, walking, and cycling sessions; matched distance and workout heart rate; available laps, exercise segments, speed, active and total calories, elevation, floors, step/cycling cadence, power, and route points; weight, body fat, lean mass, and blood pressure. Routes are included only when Health Connect provides access. Records stay on this device only while being sent over HTTPS. No Health Connect writes or records older than 30 days." })
        setContentView(root)
        handleAuthorizationRedirect(intent?.dataString)
        intent?.let { setIntent(Intent(it).apply { data = null }) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthorizationRedirect(intent.dataString)
        setIntent(Intent(intent).apply { data = null })
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

    private fun saveSite() {
        try {
            secretStore.saveEndpoint(endpoint.text.toString().trim())
            status.text = "Swim Log site saved. Sign in to authorize this device."
        } catch (error: IllegalArgumentException) { status.text = error.message ?: "Check the HTTPS site." }
    }

    private fun signInToSwimLog() {
        try {
            secretStore.saveEndpoint(endpoint.text.toString().trim())
            val request = HealthConnectAuthorizationProtocol.createRequest(secretStore.endpoint() ?: error("Save the Swim Log site first."))
            secretStore.saveAuthorizationAttempt(request.pending.state, request.pending.verifier)
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(request.url)))
            status.text = "Sign in to WordPress in your browser and authorize Swim Log Android."
        } catch (error: IllegalArgumentException) {
            secretStore.clearAuthorizationAttempt()
            status.text = error.message ?: "Check the HTTPS site and try again."
        } catch (_: Exception) {
            secretStore.clearAuthorizationAttempt()
            status.text = "Could not open the browser. Try signing in again."
        }
    }

    private fun handleAuthorizationRedirect(rawUri: String?) {
        if (rawUri.isNullOrBlank()) return
        val pending = secretStore.authorizationAttempt()
        if (pending == null) {
            status.text = "No active Swim Log sign-in was found. Start sign-in again."
            return
        }
        when (val callback = HealthConnectAuthorizationProtocol.parseCallback(rawUri, pending.first)) {
            is AuthorizationCallback.Invalid -> {
                secretStore.clearAuthorizationAttempt()
                status.text = "The sign-in response could not be verified. Start sign-in again."
            }
            is AuthorizationCallback.Cancelled -> {
                secretStore.clearAuthorizationAttempt()
                status.text = "Swim Log sign-in was cancelled."
            }
            is AuthorizationCallback.Code -> {
                val verifier = pending.second
                val site = secretStore.endpoint()
                secretStore.clearAuthorizationAttempt()
                if (site.isNullOrBlank()) {
                    status.text = "The saved Swim Log site is missing. Start sign-in again."
                    return
                }
                lifecycleScope.launch {
                    status.text = "Completing secure Swim Log sign-in…"
                    try {
                        val accessToken = authorizationClient.exchange(site, callback.value, verifier)
                        secretStore.saveToken(accessToken)
                        status.text = "Swim Log is connected on this device. You can sync selected Health Connect records."
                    } catch (failure: AuthorizationTokenException) {
                        status.text = if (failure.httpStatus == 401) {
                            "Swim Log could not verify this sign-in. Start sign-in again."
                        } else {
                            "Swim Log sign-in could not be completed. Check the installed site version and try again."
                        }
                    } catch (_: AuthorizationNetworkException) {
                        status.text = "Could not reach Swim Log securely. Check the network and sign in again."
                    } catch (_: Exception) {
                        status.text = "Swim Log sign-in could not be completed. Start sign-in again."
                    }
                }
            }
        }
    }

    private fun syncNow() {
        if (secretStore.token().isNullOrBlank() || secretStore.endpoint().isNullOrBlank()) {
            status.text = "Sign in to Swim Log before syncing."; return
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
                routeSyncClient = client
                routeOverrides = mutableMapOf()
                val granted = client.permissionController.getGrantedPermissions()
                val records = HealthReader(client).readLast30Days(granted)
                routeConsentQueue = runCatching { HealthReader(client).routesRequiringConsent(records.sessions) }.getOrDefault(emptyList())
                routeConsentIndex = 0
                if (routeConsentQueue.isNotEmpty()) {
                    status.text = "Health Connect may ask for route access for each workout that includes a route. You can deny any request; the workout will still sync without its route."
                    requestNextRouteOrSync()
                    return@launch
                }
                finishForegroundSync(client)
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

    private fun requestNextRouteOrSync() {
        val client = routeSyncClient ?: return
        if (routeConsentIndex < routeConsentQueue.size) {
            exerciseRouteLauncher.launch(routeConsentQueue[routeConsentIndex])
        } else {
            routeConsentQueue = emptyList()
            routeConsentIndex = 0
            routeSyncClient = null
            lifecycleScope.launch { finishForegroundSync(client) }
        }
    }

    private suspend fun finishForegroundSync(client: HealthConnectClient) {
        try {
                val summary = SyncEngine(secretStore).sync(client, routeOverrides.toMap())
                val canRetryInBackground = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in client.permissionController.getGrantedPermissions()
                if (summary.retryable && canRetryInBackground) SyncWorker.enqueueRetry(this@MainActivity)
                val retryText = if (!summary.retryable) "" else if (canRetryInBackground) "; transient items will retry" else "; tap Sync now again to retry transient items"
                val mirrorText = if (summary.mirroredWorkoutsSuppressed == 0) "" else "; ${summary.mirroredWorkoutsSuppressed} mirrored workout copies skipped"
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
        } finally {
            routeOverrides = mutableMapOf()
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
