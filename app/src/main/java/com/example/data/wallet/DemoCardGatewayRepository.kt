package com.example.data.wallet

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.util.UUID

/**
 * Local sandbox gateway simulator. It never collects card numbers, CVV, tokens,
 * contacts a payment processor, or moves wallet funds.
 */
class DemoCardGatewayRepository(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
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

        val attemptId = "carddemo_" + UUID.randomUUID().toString().replace("-", "")
        val orderRef = firestore.collection("storeOrders").document(orderId)
        val paymentRef = firestore.collection("demoCardPayments").document(attemptId)

        firestore.runTransaction { tx ->
            val order = tx.get(orderRef)
            check(order.exists()) { "El pedido ya no está disponible." }
            check(order.getString("environment") == "DEMO") { "Este pedido no pertenece al entorno de pruebas." }
            check(order.getString("customerUid") == uid) { "Solo quien creó el pedido puede pagar." }
            check(order.getString("merchantUid") == merchantUid) { "El comercio del pedido no coincide." }
            check(order.getString("paymentStatus") == "PENDIENTE") { "El pedido ya no está pendiente de pago." }
            check(order.getString("status") in listOf("RECIBIDO", "ACEPTADO", "EN_PREPARACION", "LISTO", "EN_CAMINO")) {
                "El pedido está cerrado y no admite pagos."
            }
            val orderAmount = (order.get("total") as? Number)?.toDouble() ?: 0.0
            check(orderAmount.isFinite() && kotlin.math.abs(orderAmount - amount) < 0.005) {
                "El importe del pedido cambió. Volvé a revisar el total."
            }
            val createdAt = order.getTimestamp("createdAt")?.toDate()?.time ?: 0L
            val now = System.currentTimeMillis()
            check(createdAt > 0L && now >= createdAt && now - createdAt < 24L * 60L * 60L * 1000L) {
                "El pedido venció. Solicitá al comercio que genere uno nuevo."
            }

            val status = if (approved) "APROBADA · DEMO" else "RECHAZADA · DEMO"
            tx.set(paymentRef, mapOf(
                "paymentId" to attemptId,
                "orderId" to orderId,
                "customerUid" to uid,
                "merchantUid" to merchantUid,
                "amount" to amount,
                "cardBrand" to cardBrand,
                "provider" to "OMNI_DEMO_GATEWAY",
                "status" to status,
                "environment" to "DEMO",
                "createdAt" to FieldValue.serverTimestamp()
            ))

            if (approved) {
                tx.update(orderRef, mapOf(
                    "paymentStatus" to "PAGADA · DEMO",
                    "paymentMethod" to "TARJETA_DEMO",
                    "cardPaymentId" to attemptId,
                    "updatedAt" to FieldValue.serverTimestamp()
                ))
                "PAGO DE TARJETA SIMULADO · APROBADO · DEMO"
            } else {
                "PAGO DE TARJETA SIMULADO · RECHAZADO · DEMO"
            }
        }.addOnSuccessListener { done(Result.success(it)) }
            .addOnFailureListener { done(Result.failure(it)) }
    }
}
