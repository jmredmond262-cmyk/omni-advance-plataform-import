package com.example.data.geo

import android.content.Context
import android.location.Location
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.example.data.api.RealLocationHelper
import com.example.data.api.RealPlacesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Comparte presencia geográfica solo con autorización expresa.
 * Comparte coordenadas GPS precisas únicamente con autorización expresa y permiso de ubicación precisa.
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
    private fun consentVersionKey(userId: String) = "consent_version_$userId"

    fun hasDecision(context: Context): Boolean {
        val userId = uid()
        return userId.isNotBlank() && prefs(context).getInt(consentVersionKey(userId), 0) >= 2
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
            .putInt(consentVersionKey(userId), 2)
            .putBoolean(enabledKey(userId), enabled)
            .apply()
        if (!enabled) {
            sendToSecureApi(JSONObject().put("enabled", false))
        }
    }

    fun publishPreciseLocation(
        context: Context,
        location: Location?,
        locality: String,
        province: String,
        country: String
    ) {
        val userId = uid()
        if (userId.isBlank() || !isSharingEnabled(context) || location == null) return
        val fineGranted = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!fineGranted) {
            Log.i(TAG, "No se comparte ubicación: falta permiso GPS preciso.")
            return
        }
        if (!location.latitude.isFinite() || !location.longitude.isFinite() ||
            !location.accuracy.isFinite() || location.accuracy <= 0f) return
        val payload = JSONObject()
            .put("enabled", true)
            .put("consent", true)
            .put("latitude", location.latitude)
            .put("longitude", location.longitude)
            .put("accuracyMeters", location.accuracy.toDouble())
            .put("locationTimeMillis", location.time)
            .put("locality", locality.trim().take(80))
            .put("province", province.trim().take(80))
            .put("country", country.trim().take(80))
        sendToSecureApi(payload)
    }

    /**
     * Refresca la presencia mientras OMNI está visible. El ciclo se detiene al pasar la app
     * a segundo plano y vuelve a exigir consentimiento y permiso GPS.
     */
    fun refreshPresenceWhileActive(context: Context) {
        if (!isSharingEnabled(context) || !RealLocationHelper.hasLocationPermission(context)) return
        ioScope.launch {
            try {
                val location = RealLocationHelper.getCurrentLocation(context) ?: return@launch
                val details = RealPlacesApi.reverseGeocodeDetails(location.latitude, location.longitude)
                publishPreciseLocation(
                    context = context,
                    location = location,
                    locality = details.city,
                    province = details.province,
                    country = details.country
                )
            } catch (error: Exception) {
                Log.w(TAG, "No se pudo refrescar la presencia activa: ${error.message}")
            }
        }
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
