package com.ki.companion

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

internal class PermissionAlertWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val preferences = applicationContext.getSharedPreferences("ki_companion", Context.MODE_PRIVATE)
        if (!preferences.getBoolean(KEY_BACKGROUND_ALERTS, false)) return@withContext Result.success()
        val host = preferences.getString("host", "").orEmpty()
        val token = preferences.getString("token", "").orEmpty()
        if (host.isBlank() || token.isBlank()) {
            Log.e(TAG, "Background permission alerts are enabled but the PC pairing is missing.")
            return@withContext Result.retry()
        }

        try {
            val connection = openConnection("${normalizeHost(host)}/api/agents", token, "GET")
            val response = try {
                val code = connection.responseCode
                val body = readResponse(connection, code)
                if (code !in 200..299) {
                    Log.e(TAG, "OpenCode permission check failed (HTTP $code): $body")
                    return@withContext if (code >= 500) Result.retry() else Result.success()
                }
                JSONObject(body)
            } finally {
                connection.disconnect()
            }
            val permissions = response.optJSONArray("permissions")
            val currentPermissions = linkedMapOf<String, String>()
            if (permissions != null) {
                for (index in 0 until permissions.length()) {
                    val permission = permissions.optJSONObject(index) ?: continue
                    val permissionId = permission.optString("id")
                    val sessionId = permission.optString("session_id")
                    if (permissionId.isBlank() || sessionId.isBlank()) continue
                    currentPermissions[permissionId] = sessionId
                }
            }
            val previousIds = preferences.getStringSet(KEY_KNOWN_PERMISSIONS, emptySet()).orEmpty()
            val handledIds = previousIds.intersect(currentPermissions.keys).toMutableSet()
            for ((permissionId, sessionId) in currentPermissions) {
                if (permissionId !in previousIds && postPermissionNotification(permissionId, sessionId)) {
                    handledIds.add(permissionId)
                }
            }
            preferences.edit().putStringSet(KEY_KNOWN_PERMISSIONS, handledIds).apply()
            Result.success()
        } catch (error: IOException) {
            Log.e(TAG, "Could not check for OpenCode permission requests.", error)
            Result.retry()
        } catch (error: JSONException) {
            Log.e(TAG, "Ki returned an unreadable OpenCode permission response.", error)
            Result.retry()
        } catch (error: SecurityException) {
            Log.e(TAG, "Android blocked an OpenCode permission notification.", error)
            Result.success()
        }
    }

    private fun postPermissionNotification(permissionId: String, sessionId: String): Boolean {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "OpenCode permission requests",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "Alerts Tot when OpenCode needs a permission decision."
                },
            )
        }
        if (!NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()) {
            Log.e(TAG, "A new OpenCode permission request is waiting, but Tot notifications are disabled.")
            return false
        }
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_ALERT_SESSION, sessionId)
            putExtra(MainActivity.EXTRA_ALERT_PERMISSION, permissionId)
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            applicationContext,
            permissionId.hashCode(),
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("OpenCode needs your review")
            .setContentText("Tap to review the request in Tot. Nothing is approved automatically.")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        NotificationManagerCompat.from(applicationContext).notify(permissionId.hashCode(), notification)
        return true
    }

    private companion object {
        const val TAG = "TotPermissionAlerts"
        const val CHANNEL_ID = "opencode_permission_requests"
        const val KEY_BACKGROUND_ALERTS = "background_permission_alerts"
        const val KEY_KNOWN_PERMISSIONS = "known_opencode_permissions"
    }
}

internal fun setBackgroundPermissionAlerts(context: Context, enabled: Boolean) {
    val preferences = context.getSharedPreferences("ki_companion", Context.MODE_PRIVATE)
    preferences.edit().putBoolean("background_permission_alerts", enabled).apply()
    val workManager = WorkManager.getInstance(context)
    if (!enabled) {
        workManager.cancelUniqueWork("tot_opencode_permission_alerts")
        return
    }
    val request = PeriodicWorkRequestBuilder<PermissionAlertWorker>(15, TimeUnit.MINUTES)
        .setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build(),
        )
        .build()
    workManager.enqueueUniquePeriodicWork(
        "tot_opencode_permission_alerts",
        ExistingPeriodicWorkPolicy.KEEP,
        request,
    )
}
