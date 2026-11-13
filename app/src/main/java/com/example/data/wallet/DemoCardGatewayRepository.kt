package com.example.data.wallet

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import java.util.UUID

/**
 * Pasarela sandbox. No recopila números, CVV, vencimientos ni tokens de tarjetas reales.
 * La validación del pedido y el registro de la simulación ocurren en Cloud Functions.
 */
class DemoCardGatewayRepository(
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance("us-central1")
) {
    // Se conserva ante fallos de red para que el reintento use la misma clave idempotente.
    private val pendingAttemptIds = mutableMapOf<String, String>()
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
        val attemptId = synchronized(pendingAttemptIds) {
            pendingAttemptIds.getOrPut(attemptKey) { UUID.randomUUID().toString().replace("-", "") }
        }
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
                if (message.isNullOrBlank()) done(Result.failure(IllegalStateException("La pasarela no devolvió un resultado válido.")))
                else {
                    synchronized(pendingAttemptIds) { pendingAttemptIds.remove(attemptKey) }
                    done(Result.success(message))
                }
            }
            // Si la respuesta se pierde, se conserva attemptId: el próximo toque recupera
            // el resultado original sin crear un segundo registro de pago.
            .addOnFailureListener { done(Result.failure(it)) }
    }
}
