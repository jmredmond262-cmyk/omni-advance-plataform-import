package com.example.data.wallet

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import java.util.UUID

/**
 * Pasarela sandbox. No recopila números, CVV, vencimientos ni tokens de tarjetas reales.
 * La validación del pedido y el registro de la simulación ocurren en Cloud Functions.
 */
class DemoCardGatewayRepository(
    context: Context,
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance("us-central1")
) {
    // Persistencia local: el mismo intento se recupera incluso si Android mata el proceso.
    private val preferences = context.applicationContext.getSharedPreferences("omni_demo_card_attempts", Context.MODE_PRIVATE)
    private val pendingAttemptIds = mutableMapOf<String, String>()

    private fun persistedAttemptId(key: String): String = synchronized(pendingAttemptIds) {
        pendingAttemptIds.getOrPut(key) {
            preferences.getString(key, null) ?: UUID.randomUUID().toString().replace("-", "").also { generated ->
                preferences.edit().putString(key, generated).apply()
            }
        }
    }

    private fun clearAttemptId(key: String) {
        synchronized(pendingAttemptIds) { pendingAttemptIds.remove(key) }
        preferences.edit().remove(key).apply()
    }
    fun simulateOrderPayment(
        orderId: String,
        merchantUid: String,
        amount: Double,
        cardBrand: String,
        approved: Boolean,
        done: (Result<String>) -> Unit
    ) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid.isNullOrBlank() || orderId.isBlank() || merchantUid.isBlank() ||
            !amount.isFinite() || amount <= 0.0 || amount > 50000.0 ||
            cardBrand !in listOf("VISA", "MASTERCARD")
        ) {
            done(Result.failure(IllegalArgumentException("No se pudo validar el intento de tarjeta DEMO.")))
            return
        }
        val attemptKey = "$orderId|$cardBrand|$approved"
        val attemptId = persistedAttemptId(attemptKey)
        functions.getHttpsCallable("simulateDemoCardPayment")
            .call(mapOf(
                "orderId" to orderId,
                "attemptId" to attemptId,
                "amount" to amount,
                "cardBrand" to cardBrand,
                "approved" to approved
            ))
            .addOnSuccessListener { result ->
                @Suppress("UNCHECKED_CAST")
                val response = result.data as? Map<String, Any?> ?: emptyMap()
                val message = response["message"] as? String
                if (response["expired"] == true) {
                    clearAttemptId(attemptKey)
                    done(Result.failure(IllegalStateException(message ?: "El pedido DEMO venció a las 24 horas y se canceló sin descontar stock.")))
                } else if (message.isNullOrBlank()) done(Result.failure(IllegalStateException("La pasarela no devolvió un resultado válido.")))
                else {
                    clearAttemptId(attemptKey)
                    done(Result.success(message))
                }
            }
            // Si la respuesta se pierde, se conserva attemptId: el próximo toque recupera
            // el resultado original sin crear un segundo registro de pago.
            .addOnFailureListener { done(Result.failure(it)) }
    }
}
