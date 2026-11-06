package com.example.data.geo

import android.content.Context
import android.location.Location
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlin.math.round

/**
 * Comparte presencia geográfica solo con autorización expresa.
 * La ubicación se redondea a una celda aproximada de ~1 km antes de salir del dispositivo.
 * No registra direcciones ni realiza seguimiento en segundo plano.
 */
object GeoPresenceRepository {
    private const val TAG = "OmniGeoPresence"
    private const val PREFS = "omni_geo_privacy"

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
            FirebaseFirestore.getInstance().collection("geoPresence").document(userId)
                .delete()
                .addOnFailureListener { error -> Log.w(TAG, "No se pudo retirar la presencia: ${error.message}") }
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
        val data = hashMapOf<String, Any>(
            "uid" to userId,
            "latitude" to latitude,
            "longitude" to longitude,
            "locality" to locality.trim().take(80),
            "province" to province.trim().take(80),
            "country" to country.trim().take(80),
            "accuracyBand" to accuracyBand,
            "consent" to true,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        FirebaseFirestore.getInstance().collection("geoPresence").document(userId)
            .set(data, SetOptions.merge())
            .addOnFailureListener { error -> Log.w(TAG, "No se pudo publicar presencia aproximada: ${error.message}") }
    }
}
