package com.example.data.wallet

import com.example.model.TransactionType
import com.example.model.WalletTransaction
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.example.data.analytics.UsageAnalyticsRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class DemoWalletAccount(
    val uid: String,
    val alias: String,
    val cvu: String,
    val balance: Double
)

data class DemoWalletSnapshot(
    val balance: Double,
    val cashbackTotal: Double,
    val alias: String,
    val cvu: String,
    val transactions: List<WalletTransaction>
)

/**
 * OMNI Pay sandbox on Firebase Spark.
 * All credits are fictitious. Firestore transactions keep both sides in sync.
 * This is a test wallet, never use it to store or move real money.
 */
class DemoWalletRepository(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    private val walletAliasService = WalletAliasService(firestore)

    fun ensureWallet(done: (Result<DemoWalletAccount>) -> Unit) {
        val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
        if (uid.isNullOrBlank()) {
            done(Result.failure(IllegalStateException("Iniciá sesión para crear tu billetera DEMO.")))
            return
        }
        val accountRef = firestore.collection("walletAccounts").document(uid)
        val alias = "omni-" + uid.takeLast(8).lowercase(Locale.ROOT)
        val cvu = UUID.nameUUIDFromBytes(uid.toByteArray())
            .toString().filter { it.isDigit() }.padEnd(22, '0').take(22)
        firestore.runTransaction { transaction ->
            val existing = transaction.get(accountRef)
            if (existing.exists()) {
                DemoWalletAccount(
                    uid, existing.getString("alias").orEmpty(),
                    existing.getString("cvu").orEmpty(),
                    (existing.get("balance") as? Number)?.toDouble() ?: 0.0
                )
            } else {
                val directoryRef = firestore.collection("walletDirectory").document(alias)
                transaction.set(accountRef, mapOf(
                    "uid" to uid, "alias" to alias, "cvu" to cvu,
                    "balance" to 50000.0, "cashbackTotal" to 0.0,
                    "depositedTotal" to 0.0, "lastOperationId" to "welcome",
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                ))
                transaction.set(directoryRef, mapOf("uid" to uid, "alias" to alias, "cvu" to cvu))
                transaction.set(accountRef.collection("transactions").document("welcome"), mapOf(
                    "operationId" to "welcome", "type" to TransactionType.CARGA_SALDO.name,
                    "title" to "Crédito inicial de prueba", "peerName" to "OMNI Pay DEMO",
                    "amount" to 50000.0, "status" to "APROBADA · DEMO",
                    "createdAt" to FieldValue.serverTimestamp()
                ))
                DemoWalletAccount(uid, alias, cvu, 50000.0)
            }
        }.addOnSuccessListener { done(Result.success(it)) }
            .addOnFailureListener { done(Result.failure(it)) }
    }

    fun observeWallet(uid: String, onUpdate: (Result<DemoWalletSnapshot>) -> Unit): List<ListenerRegistration> {
        val accountRef = firestore.collection("walletAccounts").document(uid)
        var balance = 0.0
        var cashback = 0.0
        var alias = ""
        var cvu = ""
        var transactions = emptyList<WalletTransaction>()
        fun publish() = onUpdate(Result.success(DemoWalletSnapshot(balance, cashback, alias, cvu, transactions)))

        val accountListener = accountRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                onUpdate(Result.failure(error))
                return@addSnapshotListener
            }
            if (snapshot == null || !snapshot.exists()) return@addSnapshotListener
            balance = (snapshot.get("balance") as? Number)?.toDouble() ?: 0.0
            cashback = (snapshot.get("cashbackTotal") as? Number)?.toDouble() ?: 0.0
            alias = snapshot.getString("alias").orEmpty()
            cvu = snapshot.getString("cvu").orEmpty()
            publish()
        }
        val transactionsListener = accountRef.collection("transactions")
            .orderBy("createdAt", Query.Direction.DESCENDING).limit(50)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    onUpdate(Result.failure(error))
                    return@addSnapshotListener
                }
                transactions = snapshot?.documents.orEmpty().map { document ->
                    val type = runCatching {
                        TransactionType.valueOf(document.getString("type").orEmpty())
                    }.getOrDefault(TransactionType.CARGA_SALDO)
                    val dateText = document.getTimestamp("createdAt")?.toDate()?.let {
                        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(it)
                    } ?: "Procesando…"
                    val itemDetails = (document.get("items") as? List<*>)?.mapNotNull { raw ->
                        val item = raw as? Map<*, *> ?: return@mapNotNull null
                        val quantity = (item["quantity"] as? Number)?.toInt() ?: 0
                        val name = item["name"] as? String ?: return@mapNotNull null
                        val price = (item["price"] as? Number)?.toDouble() ?: 0.0
                        if (quantity > 0) "${quantity} × ${name} · OM$ ${String.format("%,.2f", price * quantity)}" else null
                    }.orEmpty()
                    WalletTransaction(
                        id = document.getString("operationId") ?: document.id,
                        title = document.getString("title").orEmpty(),
                        storeOrEntityName = document.getString("storeName")
                            ?: document.getString("peerName").orEmpty(),
                        amount = (document.get("amount") as? Number)?.toDouble() ?: 0.0,
                        type = type, dateText = dateText,
                        status = document.getString("status") ?: "APROBADA · DEMO",
                        orderId = document.getString("orderId"),
                        storeName = document.getString("storeName").orEmpty(),
                        customerName = document.getString("customerName").orEmpty(),
                        itemDetails = itemDetails,
                        fulfillmentDetails = if (document.getString("fulfillmentType") == "DELIVERY")
                            "Delivery · ${document.getString("deliveryAddress").orEmpty()}" else if (document.getString("fulfillmentType") == "RETIRO") "Retiro en comercio" else "",
                        environment = document.getString("paymentEnvironment") ?: "DEMO"
                    )
                }
                publish()
            }
        return listOf(accountListener, transactionsListener)
    }

    fun transferDemoFunds(identifier: String, amount: Double, done: (Result<String>) -> Unit) {
        if (!validAmount(amount)) {
            done(Result.failure(IllegalArgumentException("El importe debe ser mayor que cero y no superar OM$ 50.000.")))
            return
        }
        walletAliasService.resolveRecipientUid(identifier) { resolved ->
            resolved.fold(
                onSuccess = { recipientUid -> executeTransfer(recipientUid, amount, false, done) },
                onFailure = { error -> done(Result.failure(error)) }
            )
        }
    }

    fun payDemoQr(payload: String, amount: Double, done: (Result<String>) -> Unit) {
        val parts = payload.split("|")
        if (parts.size < 3 || parts[0] != "OMNI-DEMO") {
            done(Result.failure(IllegalArgumentException("El QR no pertenece a OMNI Pay DEMO.")))
            return
        }
        if (!validAmount(amount)) {
            done(Result.failure(IllegalArgumentException("El importe no es válido.")))
            return
        }
        executeTransfer(parts[1], amount, true, done)
    }

    /**
     * Cobra un pedido usando únicamente saldo ficticio OMNI Pay.
     * El identificador estable evita un segundo débito si el usuario reintenta.
     */
    fun payStoreOrderDemo(orderId: String, merchantUid: String, amount: Double, done: (Result<String>) -> Unit) {
        if (orderId.isBlank() || merchantUid.isBlank() || !validAmount(amount)) {
            done(Result.failure(IllegalArgumentException("No se pudo validar el pedido o el importe DEMO.")))
            return
        }
        executeTransfer(merchantUid, amount, true, done, orderId)
    }

    fun topUpDemo(amount: Double, done: (Result<String>) -> Unit) {
        val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
        if (uid.isNullOrBlank() || !validAmount(amount)) {
            done(Result.failure(IllegalArgumentException("Iniciá sesión e ingresá un importe válido (máximo OM$ 50.000).")))
            return
        }
        val accountRef = firestore.collection("walletAccounts").document(uid)
        val operationId = UUID.randomUUID().toString()
        val operationRef = firestore.collection("walletOperations").document(operationId)
        firestore.runTransaction { tx ->
            val account = tx.get(accountRef)
            check(account.exists()) { "No existe tu billetera DEMO." }
            val deposited = (account.get("depositedTotal") as? Number)?.toDouble() ?: 0.0
            check(deposited + amount <= 150000.0) { "Alcanzaste el límite de cargas DEMO de OM$ 150.000." }
            val before = (account.get("balance") as? Number)?.toDouble() ?: 0.0
            tx.update(accountRef, mapOf(
                "balance" to before + amount, "depositedTotal" to deposited + amount,
                "lastOperationId" to operationId, "updatedAt" to FieldValue.serverTimestamp()
            ))
            tx.set(operationRef, mapOf(
                "operationId" to operationId, "kind" to "DEPOSIT",
                "senderUid" to uid, "recipientUid" to uid, "amount" to amount,
                "createdAt" to FieldValue.serverTimestamp()
            ))
            tx.set(accountRef.collection("transactions").document(operationId), transactionData(
                operationId, TransactionType.CARGA_SALDO, "Carga virtual de prueba",
                "OMNI Pay DEMO", amount
            ))
            "CARGA ACREDITADA · DEMO"
        }.addOnSuccessListener {
            UsageAnalyticsRepository.trackWalletOperation(uid, "DEMO_DEPOSIT")
            done(Result.success(it))
        }.addOnFailureListener { done(Result.failure(it)) }
    }

    private fun executeTransfer(
        recipientUid: String,
        amount: Double,
        isQr: Boolean,
        done: (Result<String>) -> Unit,
        orderId: String? = null
    ) {
        val senderUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
        if (senderUid.isNullOrBlank()) {
            done(Result.failure(IllegalStateException("Iniciá sesión para operar.")))
            return
        }
        // Las transferencias comunes a uno mismo siguen bloqueadas. Para pedidos de
        // comercio DEMO se permite autoconsumo: registra el circuito completo sin mover saldo.
        val selfConsumption = senderUid == recipientUid && orderId != null
        if (senderUid == recipientUid && !selfConsumption) {
            done(Result.failure(IllegalArgumentException("No podés transferirte a tu propia cuenta.")))
            return
        }
        val senderRef = firestore.collection("walletAccounts").document(senderUid)
        val recipientRef = firestore.collection("walletAccounts").document(recipientUid)
        val operationId = if (orderId != null) "orderpay_$orderId" else UUID.randomUUID().toString()
        val operationRef = firestore.collection("walletOperations").document(operationId)
        val orderRef = orderId?.let { firestore.collection("storeOrders").document(it) }
        val executeAtomicTransfer: () -> Unit = {
            firestore.runTransaction { tx ->
            // El documento walletOperations usa un ID estable para pedidos. Las reglas
            // actuales permiten CREARLO pero prohíben actualizarlo: si dos intentos compiten,
            // la transacción que llega segunda no puede sobrescribir la operación existente,
            // y Firestore revierte también sus cambios de saldo. La consulta previa solo mejora
            // el mensaje de reintento; la protección real es la escritura atómica create-only.
            val orderSnapshot = orderRef?.let { tx.get(it) }
            val sender = tx.get(senderRef)

            if (orderId != null) {
                check(orderSnapshot?.exists() == true) { "El pedido ya no está disponible." }
                check(orderSnapshot?.getString("customerUid") == senderUid) { "Solo quien creó el pedido puede pagarlo." }
                check(orderSnapshot?.getString("merchantUid") == recipientUid) { "El comercio del pedido no coincide." }
                val orderTotal = (orderSnapshot?.get("total") as? Number)?.toDouble() ?: 0.0
                check(kotlin.math.abs(orderTotal - amount) < 0.005) { "El importe del pedido cambió. Volvé a revisar el total." }
                check(orderSnapshot?.getString("paymentStatus") == "PENDIENTE") { "El pedido todavía no tiene un pago solicitado o ya fue pagado." }
                check(orderSnapshot?.getString("status") == "LISTO") { "El comercio todavía no marcó el pedido como listo." }
                check(orderSnapshot?.getBoolean("paymentRequested") == true || orderSnapshot?.getTimestamp("paymentRequestedAt") != null) {
                    "El comercio todavía no solicitó el pago."
                }
                check(orderSnapshot?.getString("status") !in listOf("ENTREGADO", "RECHAZADO", "CANCELADO")) {
                    "El pedido está cerrado y no admite pagos."
                }
            }
            check(sender.exists()) { "Tu billetera DEMO no está disponible." }
            val senderBalance = (sender.get("balance") as? Number)?.toDouble() ?: 0.0
            check(senderBalance >= amount) { "Saldo insuficiente para completar la operación." }
            val recipientAlias = "Cuenta OMNI Pay"
            val senderAlias = sender.getString("alias").orEmpty()
            val senderType = if (isQr) TransactionType.QR_PAGO else TransactionType.TRANSFERENCIA_ENVIADA
            val recipientType = if (isQr) TransactionType.QR_COBRO else TransactionType.TRANSFERENCIA_RECIBIDA
            if (!selfConsumption) {
                tx.update(senderRef, mapOf(
                    "balance" to senderBalance - amount, "lastOperationId" to operationId,
                    "updatedAt" to FieldValue.serverTimestamp()
                ))
                tx.update(recipientRef, mapOf(
                    "balance" to FieldValue.increment(amount), "lastOperationId" to operationId,
                    "updatedAt" to FieldValue.serverTimestamp()
                ))
            } else {
                // Autoconsumo de prueba: el titular simula el pago de su propio comercio.
                // No se debita ni acredita saldo porque ambas partes son la misma billetera.
                tx.update(senderRef, mapOf(
                    "lastOperationId" to operationId,
                    "updatedAt" to FieldValue.serverTimestamp()
                ))
                tx.update(orderRef!!, mapOf(
                    "paymentStatus" to "PAGADO",
                    "paymentMethod" to "AUTOCONSUMO_OMNI_PAY_DEMO",
                    "paymentEnvironment" to "DEMO",
                    "paidAt" to FieldValue.serverTimestamp()
                ))
            }
            val orderDetails = if (orderId != null && orderSnapshot != null) mapOf(
                "orderId" to orderId,
                "storeId" to (orderSnapshot.getString("storeId") ?: ""),
                "storeName" to (orderSnapshot.getString("storeName") ?: "Comercio OMNI"),
                "customerName" to (orderSnapshot.getString("customerName") ?: "Cliente OMNI"),
                "items" to (orderSnapshot.get("items") as? List<*> ?: emptyList<Any>()),
                "fulfillmentType" to (orderSnapshot.getString("fulfillmentType") ?: "RETIRO"),
                "deliveryAddress" to (orderSnapshot.getString("deliveryAddress") ?: ""),
                "paymentEnvironment" to "DEMO"
            ) else emptyMap()
            val operationData = mutableMapOf<String, Any>(
                "operationId" to operationId,
                "kind" to if (selfConsumption) "SELF_CONSUMPTION_DEMO" else if (isQr) "QR_PAYMENT" else "TRANSFER",
                "senderUid" to senderUid, "recipientUid" to recipientUid,
                "amount" to amount, "createdAt" to FieldValue.serverTimestamp()
            )
            if (orderId != null) {
                operationData["orderId"] = orderId
                operationData["correlationId"] = orderId
                operationData["environment"] = "DEMO"
                operationData["status"] = if (selfConsumption) "AUTOCONSUMO REGISTRADO · DEMO" else "APROBADA · DEMO"
                operationData["title"] = if (selfConsumption) "Autoconsumo de pedido OMNI" else "Pago de pedido OMNI"
                operationData["selfConsumption"] = selfConsumption
                operationData["balanceImpact"] = if (selfConsumption) 0.0 else amount
                operationData.putAll(orderDetails)
            }
            tx.set(operationRef, operationData)
            tx.set(senderRef.collection("transactions").document(operationId), transactionData(
                operationId,
                if (selfConsumption) TransactionType.QR_PAGO else senderType,
                if (selfConsumption) "Autoconsumo de pedido OMNI · sin movimiento de saldo" else if (orderId != null) "Pago de pedido OMNI" else if (isQr) "Pago con QR" else "Transferencia enviada",
                if (selfConsumption) "Tu propio comercio · DEMO" else recipientAlias,
                amount,
                orderDetails + if (selfConsumption) mapOf("selfConsumption" to true, "balanceImpact" to 0.0) else emptyMap()
            ))
            if (!selfConsumption) {
                tx.set(recipientRef.collection("transactions").document(operationId), transactionData(
                    operationId, recipientType, if (orderId != null) "Cobro de pedido OMNI" else if (isQr) "Cobro con QR" else "Transferencia recibida",
                    senderAlias, amount, orderDetails
                ))
            }
            if (selfConsumption) "AUTOCONSUMO REGISTRADO · DEMO · SALDO SIN CAMBIOS"
            else if (orderId != null) "PEDIDO PAGADO · OMNI PAY DEMO" else if (isQr) "PAGO QR APROBADO · DEMO" else "TRANSFERENCIA APROBADA · DEMO"
            }.addOnSuccessListener {
                UsageAnalyticsRepository.trackWalletOperation(senderUid, if (selfConsumption) "DEMO_ORDER_SELF_CONSUMPTION" else if (orderId != null) "DEMO_ORDER_PAYMENT" else if (isQr) "DEMO_QR_PAYMENT" else "DEMO_TRANSFER")
                if (orderId != null) {
                    UsageAnalyticsRepository.trackEvent(senderUid, "orders", "ORDER_PAYMENT_COMPLETED", mapOf(
                        "operationId" to operationId, "correlationId" to orderId,
                        "stage" to if (selfConsumption) "DEMO_SELF_CONSUMPTION_CONFIRMED" else "DEMO_WALLET_TRANSFER_CONFIRMED",
                        "selfConsumption" to selfConsumption, "balanceImpact" to if (selfConsumption) 0.0 else amount,
                        "success" to true
                    ))
                }
                done(Result.success(it))
            }.addOnFailureListener { done(Result.failure(it)) }
        }
        if (orderId != null) {
            firestore.collection("walletOperations")
                .whereEqualTo("senderUid", senderUid)
                .whereEqualTo("orderId", orderId)
                .limit(1).get()
                .addOnSuccessListener { existing ->
                    if (!existing.isEmpty) {
                        val operation = existing.documents.first()
                        val samePayment = operation.getString("recipientUid") == recipientUid &&
                            kotlin.math.abs((operation.get("amount") as? Number)?.toDouble()?.minus(amount) ?: Double.POSITIVE_INFINITY) < 0.005
                        if (samePayment) done(Result.success("PEDIDO YA PAGADO · DEMO"))
                        else done(Result.failure(IllegalStateException("El identificador del pedido ya está asociado a otro pago.")))
                    } else executeAtomicTransfer()
                }
                .addOnFailureListener { done(Result.failure(it)) }
        } else {
            executeAtomicTransfer()
        }
    }

    private fun transactionData(
        operationId: String, type: TransactionType, title: String, peerName: String,
        amount: Double, details: Map<String, Any> = emptyMap()
    ) = mapOf(
        "operationId" to operationId, "type" to type.name, "title" to title,
        "peerName" to peerName, "amount" to amount,
        "status" to "APROBADA · DEMO", "createdAt" to FieldValue.serverTimestamp()
    ) + details

    private fun validAmount(amount: Double) = amount.isFinite() && amount > 0.0 && amount <= 50000.0

    companion object {
        fun qrPayload(uid: String, alias: String): String = "OMNI-DEMO|$uid|$alias"
    }
}
