package com.example.data.wallet

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions

/**
 * Pasarela sandbox. No recopila números, CVV, vencimientos ni tokens de tarjetas reales.
 * La validación del pedido y el registro de la simulación ocurren en Cloud Functions.
 */
class DemoCardGatewayRepository(
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance("us-central1")
) {
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
        functions.getHttpsCallable("simulateDemoCardPayment")
            .call(mapOf(
                "orderId" to orderId,
                "amount" to amount,
                "cardBrand" to cardBrand,
                "approved" to approved
            ))
            .addOnSuccessListener { result ->
                @Suppress("UNCHECKED_CAST")
                val response = result.data as? Map<String, Any?> ?: emptyMap()
                val message = response["message"] as? String
                if (message.isNullOrBlank()) done(Result.failure(IllegalStateException("La pasarela no devolvió un resultado válido.")))
                else done(Result.success(message))
            }
            .addOnFailureListener { done(Result.failure(it)) }
    }
}
