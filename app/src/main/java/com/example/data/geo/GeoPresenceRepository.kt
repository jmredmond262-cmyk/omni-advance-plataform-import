package com.example.data.geo

import android.content.Context
import android.location.Location
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.round

/**
 * Comparte presencia geográfica solo con autorización expresa.
 * La ubicación se redondea a una celda aproximada de ~1 km antes de salir del dispositivo.
 * No registra direcciones ni realiza seguimiento en segundo plano.
 */
object GeoPresenceRepository {
    private const val TAG = "OmniGeoPresence"
    private const val PREFS = "omni_geo_privacy"
    private const val API_URL = "https://omni-admin-api.jmredmond262.workers.dev/geo-presence"
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun uid() = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun decisionKey(userId: String) = "decision_$userId"
    private fun enabledKey(userId: String) = "enabled_$userId"

    fun hasDecision(context: Context): Boolean {
        val userId = uid()
        return userId.isNotBlank() && prefs(context).contains(decisionKey(userId))
    }

    fun isSharingEnabled(context: Context): Boolean {
        val userId = uid()
        return userId.isNotBlank() && prefs(context).getBoolean(enabledKey(userId), false)
    }

    fun setSharingEnabled(context: Context, enabled: Boolean) {
        val userId = uid()
        if (userId.isBlank()) return
        prefs(context).edit()
            .putBoolean(decisionKey(userId), true)
            .putBoolean(enabledKey(userId), enabled)
            .apply()
        if (!enabled) {
            sendToSecureApi(JSONObject().put("enabled", false))
        }
    }

    fun publishApproximateLocation(
        context: Context,
        location: Location?,
        locality: String,
        province: String,
        country: String
    ) {
        val userId = uid()
        if (userId.isBlank() || !isSharingEnabled(context) || location == null) return
        if (!location.latitude.isFinite() || !location.longitude.isFinite()) return
        val latitude = round(location.latitude * 100.0) / 100.0
        val longitude = round(location.longitude * 100.0) / 100.0
        val accuracyBand = when {
            location.accuracy <= 100f -> "hasta 100 m"
            location.accuracy <= 500f -> "100 a 500 m"
            location.accuracy <= 1000f -> "500 m a 1 km"
            else -> "más de 1 km"
        }
        val payload = JSONObject()
            .put("enabled", true)
            .put("consent", true)
            .put("latitude", latitude)
            .put("longitude", longitude)
            .put("locality", locality.trim().take(80))
            .put("province", province.trim().take(80))
            .put("country", country.trim().take(80))
            .put("accuracyBand", accuracyBand)
        sendToSecureApi(payload)
    }

    private fun sendToSecureApi(payload: JSONObject) {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        user.getIdToken(false)
            .addOnSuccessListener { tokenResult ->
                val idToken = tokenResult.token ?: return@addOnSuccessListener
                ioScope.launch {
                    var connection: HttpURLConnection? = null
                    try {
                        connection = (URL(API_URL).openConnection() as HttpURLConnection).apply {
                            requestMethod = "POST"
                            connectTimeout = 12000
                            readTimeout = 12000
                            doOutput = true
                            setRequestProperty("Authorization", "Bearer $idToken")
                            setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        }
                        connection.outputStream.use { output ->
                            output.write(payload.toString().toByteArray(Charsets.UTF_8))
                        }
                        val status = connection.responseCode
                        if (status !in 200..299) {
                            val message = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                            Log.w(TAG, "La API no aceptó la presencia geográfica ($status): $message")
                        }
                    } catch (error: Exception) {
                        Log.w(TAG, "No se pudo sincronizar la presencia geográfica: ${error.message}")
                    } finally {
                        connection?.disconnect()
                    }
                }
            }
            .addOnFailureListener { error ->
                Log.w(TAG, "No se pudo validar la sesión para compartir ubicación: ${error.message}")
            }
    }
}
