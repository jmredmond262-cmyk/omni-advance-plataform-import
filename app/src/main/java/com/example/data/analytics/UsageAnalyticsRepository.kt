package com.example.data.analytics

import android.util.Log
import com.example.BuildConfig
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.util.UUID
import java.util.Calendar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Universal first-party analytics. Firestore's Android SDK persists pending writes
 * locally and retries them when connectivity returns. Stable document IDs prevent
 * duplicate records; no shared secret or second analytics server is embedded in the APK.
 *
 * Only allowlisted, non-sensitive metadata is recorded. Never pass message/search text,
 * names, phone numbers, precise locations, photo URIs, credentials, health details,
 * or exact payment amounts.
 */
object UsageAnalyticsRepository {
    private const val TAG = "OmniUsageAnalytics"
    private val firestore by lazy { FirebaseFirestore.getInstance() }
    private val allowedMetadataKeys = setOf(
        "category", "source", "resultCount", "durationMs", "success",
        "itemType", "method", "flow", "surface", "destinationType",
        "screenName", "radiusBucket", "status", "operationId", "correlationId", "stage"
    )

    @Volatile private var sessionId: String = ""
    @Volatile private var activeUid: String = ""
    @Volatile private var lastScreen: String = ""
    @Volatile private var screenStartedAt: Long = 0L
    @Volatile private var sessionStartedAt: Long = 0L
    @Volatile private var sessionActive: Boolean = false

    @Synchronized fun startSession(uid: String) {
        if (uid.isBlank() || (sessionActive && activeUid == uid)) return
        beginSession(uid)
    }

    @Synchronized fun resumeSession(uid: String) {
        if (uid.isBlank()) return
        if (!sessionActive || activeUid != uid) beginSession(uid)
        else {
            record(uid, "app", "APP_FOREGROUND")
            if (lastScreen.isNotBlank()) trackScreen(uid, lastScreen)
        }
    }

    @Synchronized private fun beginSession(uid: String) {
        activeUid = uid
        sessionId = UUID.randomUUID().toString()
        sessionActive = true
        screenStartedAt = 0L
        sessionStartedAt = System.currentTimeMillis()
        record(uid, "app", "SESSION_START")
        record(uid, "diagnostics", "DIAGNOSTIC_TEST")
        if (lastScreen.isNotBlank()) trackScreen(uid, lastScreen)
    }

    @Synchronized fun endSession() {
        if (!sessionActive || activeUid.isBlank()) return
        val uid = activeUid
        closeCurrentScreen(uid)
        record(uid, "app", "APP_BACKGROUND")
        val durationSeconds = if (sessionStartedAt > 0L) ((System.currentTimeMillis() - sessionStartedAt) / 1000L).coerceAtLeast(0L) else 0L
        record(uid, "app", "SESSION_END", if (durationSeconds > 0L) mapOf("sessionDurationSeconds" to durationSeconds) else emptyMap())
        sessionActive = false
        screenStartedAt = 0L
        sessionStartedAt = 0L
    }

    @Synchronized fun trackScreen(uid: String, screen: String) {
        if (uid.isBlank() || screen.isBlank()) return
        if (!sessionActive || activeUid != uid) beginSession(uid)
        val clean = screen.trim().take(80)
        if (lastScreen == clean && screenStartedAt > 0L) return
        closeCurrentScreen(uid)
        lastScreen = clean
        screenStartedAt = System.currentTimeMillis()
        record(uid, clean, "SCREEN_VIEW", mapOf("screenName" to clean))
    }

    @Synchronized private fun closeCurrentScreen(uid: String) {
        if (lastScreen.isNotBlank() && screenStartedAt > 0L) {
            val durationSeconds = ((System.currentTimeMillis() - screenStartedAt) / 1000L).coerceAtLeast(0L)
            val metadata = mutableMapOf<String, Any>("screenName" to lastScreen)
            if (durationSeconds > 0L) metadata["screenDurationSeconds"] = durationSeconds
            record(uid, lastScreen, "SCREEN_EXIT", metadata)
        }
        screenStartedAt = 0L
    }

    fun trackAction(uid: String, action: String, module: String) = trackEvent(uid, module, action)

    fun trackEvent(uid: String, module: String, action: String, metadata: Map<String, Any> = emptyMap()) {
        if (uid.isBlank() || action.isBlank() || module.isBlank()) return
        record(uid, module, action, metadata)
    }

    fun trackCurrentAction(module: String, action: String, metadata: Map<String, Any> = emptyMap()) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        if (uid.isNotBlank()) trackEvent(uid, module, action, metadata)
    }

    fun trackWalletOperation(uid: String, operation: String) {
        trackEvent(uid, "wallet", operation, mapOf("flow" to "demo_wallet"))
    }

    private fun safeMetadata(metadata: Map<String, Any>): Map<String, Any> =
        metadata.entries.asSequence().filter { it.key in allowedMetadataKeys }.take(10)
            .mapNotNull { (key, value) ->
                when (value) {
                    is String -> key to value.trim().take(80)
                    is Boolean -> key to value
                    is Int -> key to value
                    is Long -> if (value >= 0L) key to value else null
                    is Double -> if (value.isFinite() && value >= 0.0) key to value else null
                    is Float -> if (value.isFinite() && value >= 0f) key to value else null
                    else -> null
                }
            }.toMap()

    private fun record(requestedUid: String, module: String, eventName: String, metadata: Map<String, Any> = emptyMap()) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid?.takeIf { it.isNotBlank() }
            ?: requestedUid.takeIf { it.isNotBlank() } ?: return
        val eventId = UUID.randomUUID().toString()
        val event = hashMapOf<String, Any>(
            "eventId" to eventId, "uid" to uid, "module" to module.trim().take(80),
            "event" to eventName.trim().uppercase().take(80),
            "sessionId" to sessionId.ifBlank { UUID.randomUUID().toString() },
            "appVersion" to BuildConfig.VERSION_NAME, "platform" to "android",
            "schemaVersion" to 3, "createdAt" to FieldValue.serverTimestamp()
        )
        val now = System.currentTimeMillis()
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = now
        event["localHour"] = calendar.get(Calendar.HOUR_OF_DAY)
        event["localDayOfWeek"] = calendar.get(Calendar.DAY_OF_WEEK)
        event["timezoneOffsetMinutes"] = calendar.timeZone.getOffset(now) / 60000
        event["localDate"] = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(now))
        val safe = safeMetadata(metadata)
        if (safe.isNotEmpty()) event["data"] = safe
        firestore.collection("usageEvents").document(eventId).set(event)
            .addOnSuccessListener { Log.d(TAG, "Evento registrado: $eventName / $module / $eventId") }
            .addOnFailureListener { error ->
                Log.e(TAG, "No se pudo registrar $eventName / $module: ${error.message}", error)
            }
    }
}
