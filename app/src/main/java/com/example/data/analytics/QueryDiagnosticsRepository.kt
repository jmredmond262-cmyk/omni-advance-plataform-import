package com.example.data.analytics

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local aggregation of Firestore query observations.
 * One batched document update per user/day avoids one telemetry write per query.
 * Document-read values are estimates based on server snapshots, not billing exports.
 */
object QueryDiagnosticsRepository {
    private const val PREFS = "omni_query_diagnostics_pending"
    private const val PREFIX = "module_"
    private val lock = Any()

    fun recordSnapshot(
        module: String,
        operation: String,
        documentsRead: Int,
        fromCache: Boolean,
        durationMs: Long,
        firstSnapshot: Boolean,
        failed: Boolean
    ) = recordSnapshot(
        FirebaseApp.getInstance().applicationContext,
        module, operation, documentsRead, fromCache, durationMs, firstSnapshot, failed
    )

    fun recordWrite(module: String, failed: Boolean = false) =
        recordWrite(FirebaseApp.getInstance().applicationContext, module, failed)

    fun recordSnapshot(
        context: Context,
        module: String,
        operation: String,
        documentsRead: Int,
        fromCache: Boolean,
        durationMs: Long,
        firstSnapshot: Boolean,
        failed: Boolean
    ) {
        val normalized = module.trim().lowercase(Locale.ROOT).replace(Regex("[^a-z0-9_-]"), "_").take(48)
        if (normalized.isBlank()) return
        synchronized(lock) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val key = PREFIX + normalized
            val stats = runCatching { JSONObject(prefs.getString(key, "{}") ?: "{}") }.getOrDefault(JSONObject())
            if (firstSnapshot) stats.put("queryCount", stats.optLong("queryCount") + 1L)
            if (fromCache) stats.put("cacheSnapshots", stats.optLong("cacheSnapshots") + 1L)
            else {
                stats.put("serverSnapshots", stats.optLong("serverSnapshots") + 1L)
                stats.put("estimatedDocumentReads", stats.optLong("estimatedDocumentReads") + documentsRead.coerceAtLeast(0))
            }
            if (failed) stats.put("errorCount", stats.optLong("errorCount") + 1L)
            stats.put("durationMsTotal", stats.optLong("durationMsTotal") + durationMs.coerceAtLeast(0L))
            stats.put("lastOperation", operation.take(80))
            prefs.edit().putString(key, stats.toString()).apply()
        }
    }

    fun recordWrite(context: Context, module: String, failed: Boolean = false) {
        val normalized = module.trim().lowercase(Locale.ROOT).replace(Regex("[^a-z0-9_-]"), "_").take(48)
        if (normalized.isBlank()) return
        synchronized(lock) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val key = PREFIX + normalized
            val stats = runCatching { JSONObject(prefs.getString(key, "{}") ?: "{}") }.getOrDefault(JSONObject())
            stats.put("writeCount", stats.optLong("writeCount") + 1L)
            if (failed) stats.put("errorCount", stats.optLong("errorCount") + 1L)
            prefs.edit().putString(key, stats.toString()).apply()
        }
    }

    fun flush(context: Context, uid: String) {
        if (uid.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pending = synchronized(lock) {
            prefs.all.filterKeys { it.startsWith(PREFIX) }
                .mapNotNull { (key, value) -> (value as? String)?.let { key to it } }
        }
        if (pending.isEmpty()) return

        val day = SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date())
        val modules = hashMapOf<String, Any>()
        pending.forEach { (key, raw) ->
            val module = key.removePrefix(PREFIX)
            val stats = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
            modules[module] = mapOf(
                "queryCount" to FieldValue.increment(stats.optLong("queryCount")),
                "estimatedDocumentReads" to FieldValue.increment(stats.optLong("estimatedDocumentReads")),
                "serverSnapshots" to FieldValue.increment(stats.optLong("serverSnapshots")),
                "cacheSnapshots" to FieldValue.increment(stats.optLong("cacheSnapshots")),
                "writeCount" to FieldValue.increment(stats.optLong("writeCount")),
                "errorCount" to FieldValue.increment(stats.optLong("errorCount")),
                "durationMsTotal" to FieldValue.increment(stats.optLong("durationMsTotal")),
                "lastOperation" to (stats.optString("lastOperation").ifBlank { "OPERACION_NO_INFORMADA" })
            )
        }
        modules["diagnostics"] = mapOf(
            "writeCount" to FieldValue.increment(1L),
            "flushWrites" to FieldValue.increment(1L),
            "lastOperation" to "BATCH_QUERY_DIAGNOSTICS"
        )
        val documentId = uid + "_" + day
        FirebaseFirestore.getInstance().collection("queryDiagnostics").document(documentId)
            .set(
                mapOf(
                    "uid" to uid,
                    "day" to day,
                    "updatedAt" to FieldValue.serverTimestamp(),
                    "modules" to modules
                ),
                SetOptions.merge()
            )
            .addOnSuccessListener {
                synchronized(lock) {
                    val editor = prefs.edit()
                    pending.forEach { (key, value) ->
                        if (prefs.getString(key, null) == value) editor.remove(key)
                    }
                    editor.apply()
                }
            }
    }

}
