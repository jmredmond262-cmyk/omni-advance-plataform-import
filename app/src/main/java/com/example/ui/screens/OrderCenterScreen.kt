package com.example.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.content.ClipData
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.Store
import com.example.data.wallet.DemoWalletRepository
import com.example.model.StoreProduct
import com.example.model.ProductCategory
import com.example.ui.MainViewModel
import com.example.data.analytics.UsageAnalyticsRepository
import com.example.data.analytics.OmniTracker
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.util.UUID
import org.json.JSONObject

private data class OrderLine(val productId: String, val name: String, val price: Double, val quantity: Int)
private data class OmniStoreOrder(
    val id: String,
    val storeId: String,
    val storeName: String,
    val merchantUid: String,
    val customerUid: String,
    val customerName: String,
    val customerPhone: String,
    val items: List<OrderLine>,
    val total: Double,
    val status: String,
    val paymentStatus: String,
    val fulfillmentType: String,
    val deliveryAddress: String,
    val qrNonce: String,
    val isLocalDemo: Boolean = false,
    val paymentRequested: Boolean = false,
    val proposedItems: List<OrderLine> = emptyList(),
    val proposedTotal: Double? = null,
    val availabilityMessage: String = "",
    val availabilityReviewed: Boolean = false,
    val storeAddress: String = "",
    val storePhone: String = ""
)

@Composable
fun OrderCenterScreen(
    viewModel: MainViewModel,
    merchantMode: Boolean,
    onBack: () -> Unit,
    demoMode: Boolean = false
) {
    val context = LocalContext.current
    val uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
    val profile by viewModel.userProfile.collectAsState()
    val selectedStore by viewModel.selectedStore.collectAsState()
    val availableStores by viewModel.stores.collectAsState()
    val managedStore by viewModel.managedStore.collectAsState()
    val store = if (merchantMode) managedStore else (selectedStore ?: managedStore)
    val db = remember { FirebaseFirestore.getInstance() }
    val walletRepository = remember { DemoWalletRepository() }

    var merchantReady by remember { mutableStateOf(false) }
    var merchantError by remember { mutableStateOf<String?>(null) }
    var orders by remember { mutableStateOf<List<OmniStoreOrder>>(emptyList()) }
    var paidOrderIds by remember(uid, merchantMode) { mutableStateOf<Set<String>>(emptySet()) }
    var paymentListenerInitialized by remember(uid, merchantMode) { mutableStateOf(false) }
    val cartPreferences = remember { context.getSharedPreferences("omni_order_cart_drafts", 0) }
    fun readSavedCart(storeId: String): Map<String, Int> {
        val json = runCatching { JSONObject(cartPreferences.getString(storeId, "{}") ?: "{}") }.getOrNull() ?: JSONObject()
        return json.keys().asSequence().mapNotNull { key ->
            val quantity = json.optInt(key, 0)
            if (quantity > 0) key to quantity else null
        }.toMap()
    }
    var cart by remember(store.id) { mutableStateOf(readSavedCart(store.id)) }
    fun updateCart(next: Map<String, Int>) {
        cart = next
        val json = JSONObject()
        next.filterValues { it > 0 }.forEach { (id, quantity) -> json.put(id, quantity) }
        cartPreferences.edit().putString(store.id, json.toString()).apply()
    }
    var productSearch by remember { mutableStateOf("") }
    var selectedProductCategory by remember { mutableStateOf<ProductCategory?>(null) }
    var fulfillmentType by remember { mutableStateOf("RETIRO") }
    var deliveryAddress by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var createdOrder by remember { mutableStateOf<OmniStoreOrder?>(null) }
    var showOrderReview by remember { mutableStateOf(false) }
    var selectedScannedOrder by remember { mutableStateOf<OmniStoreOrder?>(null) }
    var payingOrderId by remember { mutableStateOf<String?>(null) }
    var reviewOrder by remember { mutableStateOf<OmniStoreOrder?>(null) }
    var missingProductIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var availabilityNote by remember { mutableStateOf("") }

    fun decodeOrder(doc: com.google.firebase.firestore.DocumentSnapshot): OmniStoreOrder? = runCatching {
        val lines = (doc.get("items") as? List<*>)?.mapNotNull { raw ->
            val item = raw as? Map<*, *> ?: return@mapNotNull null
            OrderLine(
                productId = item["productId"] as? String ?: "",
                name = item["name"] as? String ?: "",
                price = (item["price"] as? Number)?.toDouble() ?: 0.0,
                quantity = (item["quantity"] as? Number)?.toInt() ?: 0
            )
        }.orEmpty()
        OmniStoreOrder(
            id = doc.id,
            storeId = doc.getString("storeId").orEmpty(),
            storeName = doc.getString("storeName").orEmpty(),
            merchantUid = doc.getString("merchantUid").orEmpty(),
            customerUid = doc.getString("customerUid").orEmpty(),
            customerName = doc.getString("customerName").orEmpty(),
            customerPhone = doc.getString("customerPhone").orEmpty(),
            items = lines,
            total = (doc.get("total") as? Number)?.toDouble() ?: 0.0,
            status = doc.getString("status").orEmpty(),
            paymentStatus = doc.getString("paymentStatus").orEmpty(),
            fulfillmentType = doc.getString("fulfillmentType").orEmpty(),
            deliveryAddress = doc.getString("deliveryAddress").orEmpty(),
            qrNonce = doc.getString("qrNonce").orEmpty(),
            paymentRequested = doc.getBoolean("paymentRequested") == true || doc.getTimestamp("paymentRequestedAt") != null,
            proposedItems = (doc.get("proposedItems") as? List<*>)?.mapNotNull { raw ->
                val item = raw as? Map<*, *> ?: return@mapNotNull null
                OrderLine(item["productId"] as? String ?: "", item["name"] as? String ?: "",
                    (item["price"] as? Number)?.toDouble() ?: 0.0, (item["quantity"] as? Number)?.toInt() ?: 0)
            }.orEmpty(),
            proposedTotal = (doc.get("proposedTotal") as? Number)?.toDouble(),
            availabilityMessage = doc.getString("availabilityMessage").orEmpty(),
            availabilityReviewed = doc.getBoolean("availabilityReviewed") == true,
            storeAddress = doc.getString("storeAddress").orEmpty(),
            storePhone = doc.getString("storePhone").orEmpty()
        )
    }.getOrNull()

    LaunchedEffect(store.id) {
        // El carrito pertenece a un único comercio: nunca mezclar artículos de locales distintos.
        productSearch = ""
        selectedProductCategory = null
        deliveryAddress = ""
        fulfillmentType = "RETIRO"
        createdOrder = null
        showOrderReview = false
    }

    LaunchedEffect(merchantMode, merchantReady, uid) {
        if (merchantMode && merchantReady && uid.isNotBlank()) {
            walletRepository.ensureWallet { result ->
                result.onFailure { feedback = "No se pudo preparar OMNI Pay DEMO para cobrar: ${it.localizedMessage ?: "error"}" }
            }
        }
    }

    LaunchedEffect(merchantMode, store.id, uid) {
        if (merchantMode && uid.isNotBlank()) {
            merchantReady = false
            merchantError = null
            val profileRef = db.collection("merchantProfiles").document(store.id)
            profileRef.get().addOnSuccessListener { doc ->
                if (doc.exists()) {
                    if (doc.getString("ownerUid") == uid) {
                        merchantReady = true
                    } else {
                        merchantError = "Este comercio ya está asociado a otra cuenta. No podés administrar sus pedidos."
                    }
                } else {
                    profileRef.set(mapOf(
                        "storeId" to store.id,
                        "storeName" to store.name,
                        "address" to store.address,
                        "contactPhone" to store.phone,
                        "ownerUid" to uid,
                        "createdAt" to FieldValue.serverTimestamp()
                    )).addOnSuccessListener { merchantReady = true }
                        .addOnFailureListener { merchantError = it.localizedMessage ?: "No se pudo habilitar el panel de pedidos." }
                }
            }.addOnFailureListener { merchantError = it.localizedMessage ?: "No se pudo verificar el comercio." }
        } else if (!merchantMode) {
            merchantReady = true
        }
    }

    DisposableEffect(merchantMode, merchantReady, store.id, uid, demoMode, selectedScannedOrder) {
        var listener: ListenerRegistration? = null
        var paymentListener: ListenerRegistration? = null
        if (!demoMode && uid.isNotBlank() && (!merchantMode || merchantReady)) {
            val query = if (merchantMode) {
                db.collection("storeOrders").whereEqualTo("merchantUid", uid).limit(100)
            } else {
                db.collection("storeOrders").whereEqualTo("customerUid", uid).limit(100)
            }
            listener = query.addSnapshotListener { snapshot, error ->
                if (error != null) {
                    feedback = error.localizedMessage ?: "No se pudieron sincronizar los pedidos."
                } else {
                    val loadedOrders = snapshot?.documents.orEmpty().mapNotNull(::decodeOrder)
                        .filter { !merchantMode || it.storeId == store.id }
                        .sortedByDescending { it.id }
                    val previousOrders = orders.associateBy { it.id }
                    val changedOrder = loadedOrders.firstOrNull { current ->
                        val previous = previousOrders[current.id]
                        previous != null && (previous.status != current.status || previous.paymentRequested != current.paymentRequested)
                    }
                    if (changedOrder != null) {
                        feedback = when {
                            !merchantMode && changedOrder.status == "ESPERA_CONFIRMACION_CLIENTE" ->
                                "El comercio informó faltantes en tu pedido " + changedOrder.id + ". Revisá la propuesta y confirmá el nuevo total antes de pagar."
                            !merchantMode && changedOrder.paymentRequested ->
                                "Tu pedido " + changedOrder.id + " está listo. El comercio habilitó el pago en OMNI Pay DEMO."
                            merchantMode && changedOrder.status == "CONFIRMADO_CLIENTE" ->
                                "El cliente aceptó la propuesta del pedido " + changedOrder.id + ". Ya podés comenzar la preparación."
                            merchantMode && changedOrder.status == "PAGADO" ->
                                "El pedido " + changedOrder.id + " fue pagado en OMNI Pay DEMO."
                            else -> "El pedido " + changedOrder.id + " cambió de estado a " + changedOrder.status + "."
                        }
                    }
                    orders = loadedOrders.map { current ->
                        if (current.id in paidOrderIds) current.copy(paymentStatus = "PAGADA · DEMO") else current
                    }
                }
            }
            paymentListener = db.collection("walletOperations")
                    .whereEqualTo(if (merchantMode) "recipientUid" else "senderUid", uid)
                    .limit(200)
                    .addSnapshotListener { snapshot, error ->
                        if (error != null) {
                            feedback = error.localizedMessage ?: "No se pudo actualizar la confirmación de cobros DEMO."
                        } else {
                            val confirmed = snapshot?.documents.orEmpty()
                                .filter { it.getString("kind") in listOf("QR_PAYMENT", "SELF_CONSUMPTION_DEMO") }
                                .mapNotNull { it.getString("orderId") }
                                .toSet()
                            if (merchantMode && paymentListenerInitialized) {
                                (confirmed - paidOrderIds).firstOrNull()?.let { incomingOrderId ->
                                    db.collection("storeOrders").document(incomingOrderId).get()
                                        .addOnSuccessListener { incomingDoc ->
                                            decodeOrder(incomingDoc)?.let { incoming ->
                                                val detailLines = incoming.items.joinToString("\\n") { line ->
                                                    "${line.quantity} × ${line.name} · OM$ ${String.format("%,.2f", line.price * line.quantity)}"
                                                }
                                                feedback = "¡COBRO RECIBIDO · OMNI PAY DEMO!\\nComercio: ${incoming.storeName}\\nCliente: ${incoming.customerName}\\n${detailLines}\\nTotal acreditado: OM$ ${String.format("%,.2f", incoming.total)}\\nPedido: ${incoming.id}\\nLa operación quedó registrada en tu billetera ficticia."
                                            }
                                        }
                                }
                            }
                            paymentListenerInitialized = true
                            paidOrderIds = confirmed
                            orders = orders.map { current ->
                                if (current.id in confirmed) current.copy(paymentStatus = "PAGADA · DEMO") else current
                            }
                            selectedScannedOrder?.let { scanned ->
                                if (scanned.id in confirmed && !scanned.paymentStatus.startsWith("PAGADA")) {
                                    selectedScannedOrder = scanned.copy(paymentStatus = "PAGADA · DEMO")
                                }
                            }
                        }
                    }
        }
        onDispose { listener?.remove(); paymentListener?.remove() }
    }

    val visibleProducts = store.inventory.filter { product ->
        product.inStock &&
            (selectedProductCategory == null || product.category == selectedProductCategory) &&
            (productSearch.isBlank() || product.name.contains(productSearch.trim(), ignoreCase = true) || product.category.displayName.contains(productSearch.trim(), ignoreCase = true))
    }
    val cartLines = store.inventory.filter { (cart[it.id] ?: 0) > 0 && it.inStock }.map {
        OrderLine(it.id, it.name, it.price, cart[it.id] ?: 0)
    }
    val total = cartLines.sumOf { it.price * it.quantity }

    fun submitOrder() {
        if ((!demoMode && uid.isBlank()) || cartLines.isEmpty() || cartLines.size > 30 || total <= 0.0 || total > 50000.0 || (fulfillmentType == "DELIVERY" && deliveryAddress.isBlank())) {
            feedback = "Revisá el pedido: máximo 30 productos distintos, total hasta OM$ 50.000 para OMNI Pay DEMO y dirección obligatoria para delivery."
            return
        }
        sending = true
        if (demoMode) {
            val demoId = "DEMO-" + UUID.randomUUID().toString().replace("-", "").take(12).uppercase()
            val nonce = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().take(8)
            createdOrder = OmniStoreOrder(
                id = demoId,
                storeId = store.id,
                storeName = store.name,
                merchantUid = "",
                customerUid = uid,
                customerName = profile.fullName,
                customerPhone = profile.phone,
                items = cartLines,
                total = total,
                status = "SIMULACIÓN LOCAL",
                paymentStatus = "NO APLICA · DEMO",
                fulfillmentType = fulfillmentType,
                deliveryAddress = deliveryAddress.trim(),
                qrNonce = nonce,
                isLocalDemo = true
            )
            updateCart(emptyMap())
            sending = false
            return
        }
        db.collection("merchantProfiles").document(store.id).get()
            .addOnSuccessListener { merchant ->
                val merchantUid = merchant.getString("ownerUid").orEmpty()
                if (!merchant.exists() || merchantUid.isBlank()) {
                    sending = false
                    feedback = "No se encontró un perfil comercial activo para este catálogo. El comercio debe ingresar a su Panel de Comercio con la cuenta titular para vincularlo antes de recibir pedidos."
                } else {
                    val ref = db.collection("storeOrders").document()
                    val nonce = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().take(8)
                    val payloadItems = cartLines.map { mapOf(
                        "productId" to it.productId, "name" to it.name,
                        "price" to it.price, "quantity" to it.quantity
                    ) }
                    ref.set(mapOf(
                        "storeId" to store.id, "storeName" to store.name,
                        "merchantUid" to merchantUid, "customerUid" to uid,
                        "customerName" to profile.fullName, "customerPhone" to profile.phone,
                        "items" to payloadItems, "total" to total,
                        "storeAddress" to store.address, "storePhone" to store.phone,
                        "status" to "RECIBIDO", "paymentStatus" to "NO_SOLICITADO",
                        "paymentRequested" to false, "availabilityReviewed" to false,
                        "availabilityStatus" to "PENDIENTE",
                        "paymentMethod" to "PAGO_AL_RETIRAR",
                    "paymentFlow" to "OMNI_PAY_DEMO",
                        "fulfillmentType" to fulfillmentType, "deliveryAddress" to deliveryAddress.trim(),
                        "qrNonce" to nonce,
                        "operationId" to ref.id,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )).addOnSuccessListener {
                        UsageAnalyticsRepository.trackEvent(uid, "orders", "STORE_ORDER_CREATED", mapOf("operationId" to ref.id, "correlationId" to ref.id, "stage" to "FIRESTORE_WRITE_CONFIRMED", "success" to true))
                        UsageAnalyticsRepository.trackEvent(uid, "orders", "ORDER_QR_GENERATED", mapOf("operationId" to ref.id, "correlationId" to ref.id, "stage" to "QR_PAYLOAD_CREATED", "success" to true))
                        sending = false
                        createdOrder = OmniStoreOrder(
                            ref.id, store.id, store.name, merchantUid, uid,
                            profile.fullName, profile.phone, cartLines, total,
                            "RECIBIDO", "NO_SOLICITADO", fulfillmentType, deliveryAddress.trim(), nonce
                        )
                        updateCart(emptyMap())
                    }.addOnFailureListener { e ->
                        sending = false
                        feedback = e.localizedMessage ?: "No se pudo registrar el pedido. Revisá las reglas de Firestore."
                    }
                }
            }.addOnFailureListener { e ->
                sending = false
                feedback = e.localizedMessage ?: "No se pudo verificar el comercio."
            }
    }

    fun handleScannedOrder(payload: OrderQRPayload) {
        OmniTracker.track("orders", "ORDER_QR_SCAN_STARTED", mapOf("correlationId" to payload.orderId, "stage" to "MERCHANT_CAMERA_SCAN"))
        db.collection("storeOrders").document(payload.orderId).get()
            .addOnSuccessListener { doc ->
                val order = decodeOrder(doc)
                val createdAt = doc.getTimestamp("createdAt")?.toDate()?.time ?: 0L
                val expired = createdAt == 0L || System.currentTimeMillis() - createdAt > 24L * 60L * 60L * 1000L
                val itemsMatch = order != null && (
                    if (payload.items.isEmpty()) {
                        payload.itemCount == order.items.sumOf { it.quantity }
                    } else {
                        order.items.size == payload.items.size &&
                            order.items.all { line ->
                                payload.items.any { qrItem ->
                                    qrItem.productId == line.productId && qrItem.name == line.name &&
                                        kotlin.math.abs(qrItem.price - line.price) < 0.005 &&
                                        qrItem.quantity == line.quantity
                                }
                            }
                    }
                )
                if (order == null || order.storeId != store.id || order.merchantUid != uid ||
                    order.qrNonce != payload.qrNonce || order.storeName != payload.merchantName ||
                    kotlin.math.abs(order.total - payload.totalAmount) >= 0.005 || !itemsMatch ||
                    expired || order.status in listOf("ENTREGADO", "RECHAZADO", "CANCELADO")) {
                    feedback = "QR inválido, vencido, modificado o no corresponde a este comercio."
                } else {
                    OmniTracker.track("orders", "ORDER_QR_VERIFIED", mapOf("operationId" to order.id, "correlationId" to order.id, "stage" to "QR_VALIDATION", "success" to true))
                    db.collection("walletOperations")
                        .whereEqualTo("recipientUid", uid)
                        .whereEqualTo("orderId", order.id)
                        .limit(1).get()
                        .addOnSuccessListener { payment ->
                            selectedScannedOrder = order.copy(
                                paymentStatus = if (payment.isEmpty) "PENDIENTE" else "PAGADA · DEMO"
                            )
                        }
                        .addOnFailureListener { selectedScannedOrder = order }
                }
            }
            .addOnFailureListener { feedback = "No se pudo verificar el pedido en Firestore." }
    }

    fun payOrderDemo(order: OmniStoreOrder) {
        if (payingOrderId != null) return
        if (order.customerUid != uid || order.status != "LISTO" || !order.paymentRequested || order.paymentStatus != "PENDIENTE") {
            feedback = "El pago todavía no está habilitado: el comercio debe marcar el pedido como listo y solicitar el pago."
            return
        }
        payingOrderId = order.id
        walletRepository.ensureWallet { walletResult ->
            walletResult.fold(
                onSuccess = {
                    walletRepository.payStoreOrderDemo(order.id, order.merchantUid, order.total) { result ->
                        payingOrderId = null
                        result.fold(
                            onSuccess = {
                                orders = orders.map { current ->
                                    if (current.id == order.id) current.copy(paymentStatus = "PAGADA · DEMO", paymentRequested = true) else current
                                }
                                feedback = "$it. Se registró el movimiento ficticio en las dos billeteras. No se movió dinero real."
                            },
                            onFailure = { error ->
                                feedback = error.localizedMessage ?: "No se pudo completar el pago OMNI Pay DEMO."
                            }
                        )
                    }
                },
                onFailure = { error ->
                    payingOrderId = null
                    feedback = error.localizedMessage ?: "No se pudo preparar tu billetera OMNI Pay DEMO."
                }
            )
        }
    }

    fun submitAvailabilityReview(order: OmniStoreOrder) {
        if (order.merchantUid != uid || order.status != "EN_REVISION") {
            feedback = "Solo el comercio titular puede revisar la disponibilidad de este pedido."
            return
        }
        val available = order.items.filterNot { it.productId in missingProductIds }
        val proposedTotal = available.sumOf { it.price * it.quantity }
        val hasMissing = missingProductIds.isNotEmpty()
        if (hasMissing && availabilityNote.isBlank()) {
            feedback = "Indicá qué productos faltan antes de enviar la revisión."
            return
        }
        val proposedPayload = available.map { line -> mapOf(
            "productId" to line.productId, "name" to line.name, "price" to line.price, "quantity" to line.quantity
        ) }
        val nextStatus = if (hasMissing) "ESPERA_CONFIRMACION_CLIENTE" else "DISPONIBILIDAD_CONFIRMADA"
        val message = availabilityNote.trim().ifBlank { "El comercio confirmó la disponibilidad de todos los productos." }
        db.collection("storeOrders").document(order.id).update(mapOf(
            "status" to nextStatus, "proposedItems" to proposedPayload, "proposedTotal" to proposedTotal,
            "availabilityMessage" to message, "availabilityReviewed" to true,
            "availabilityReviewedBy" to uid, "availabilityReviewedAt" to FieldValue.serverTimestamp(),
            "availabilityStatus" to if (hasMissing) "FALTANTES_INFORMADOS" else "TODO_DISPONIBLE",
            "updatedAt" to FieldValue.serverTimestamp()
        )).addOnSuccessListener {
            UsageAnalyticsRepository.trackEvent(uid, "orders",
                if (hasMissing) "ORDER_AVAILABILITY_ISSUE_REPORTED" else "ORDER_AVAILABILITY_CONFIRMED",
                mapOf("operationId" to order.id, "correlationId" to order.id, "stage" to nextStatus,
                    "resultCount" to available.size, "status" to nextStatus, "success" to true))
            val updated = order.copy(status = nextStatus, proposedItems = available,
                proposedTotal = proposedTotal, availabilityMessage = message, availabilityReviewed = true)
            orders = orders.map { if (it.id == order.id) updated else it }
            selectedScannedOrder = updated
            reviewOrder = null; missingProductIds = emptySet(); availabilityNote = ""
            feedback = if (hasMissing) "Se informó al cliente qué productos faltan. El pago sigue bloqueado hasta que acepte."
                else "Disponibilidad confirmada. El comercio ya puede preparar el pedido."
        }.addOnFailureListener { feedback = it.localizedMessage ?: "No se pudo guardar la revisión de disponibilidad." }
    }

    fun acceptAvailabilityProposal(order: OmniStoreOrder) {
        if (order.customerUid != uid || order.status != "ESPERA_CONFIRMACION_CLIENTE" ||
            order.proposedItems.isEmpty() || (order.proposedTotal ?: 0.0) <= 0.0) {
            feedback = "La propuesta no contiene productos disponibles para confirmar. Podés cancelar el pedido."
            return
        }
        val finalItems = order.proposedItems.map { line -> mapOf(
            "productId" to line.productId, "name" to line.name, "price" to line.price, "quantity" to line.quantity
        ) }
        val finalTotal = order.proposedTotal ?: 0.0
        db.collection("storeOrders").document(order.id).update(mapOf(
            "items" to finalItems, "total" to finalTotal, "status" to "CONFIRMADO_CLIENTE",
            "customerAcceptedBy" to uid, "customerAcceptedAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )).addOnSuccessListener {
            UsageAnalyticsRepository.trackEvent(uid, "orders", "ORDER_AVAILABILITY_PROPOSAL_ACCEPTED",
                mapOf("operationId" to order.id, "correlationId" to order.id,
                    "stage" to "CUSTOMER_CONFIRMED_FINAL_TOTAL", "resultCount" to order.proposedItems.size, "status" to "CONFIRMADO_CLIENTE", "success" to true))
            val updated = order.copy(items = order.proposedItems, total = finalTotal, status = "CONFIRMADO_CLIENTE")
            orders = orders.map { if (it.id == order.id) updated else it }
            feedback = "Aceptaste la disponibilidad y el total definitivo. El comercio ya puede preparar tu pedido."
        }.addOnFailureListener { feedback = it.localizedMessage ?: "No se pudo confirmar la propuesta del comercio." }
    }

    fun advanceOrder(order: OmniStoreOrder, next: String) {
        if (next == "RECHAZADO" && order.paymentStatus.startsWith("PAGADA")) {
            feedback = "El pedido ya está pagado. No se puede rechazar desde el comercio; requiere una gestión de devolución DEMO."
            return
        }
        if (next in listOf("EN_CAMINO", "ENTREGADO") && !order.paymentStatus.startsWith("PAGADA")) {
            feedback = "No se puede despachar ni entregar: el cliente debe autorizar primero el pago desde su OMNI Pay DEMO."
            return
        }
        if (next == "LISTO" && (!order.availabilityReviewed || order.status != "EN_PREPARACION")) {
            feedback = "Antes de marcar listo, el comercio debe revisar y confirmar la disponibilidad y preparar el pedido."
            return
        }
        db.collection("storeOrders").document(order.id).update(
            mapOf("status" to next, "updatedAt" to FieldValue.serverTimestamp())
        ).addOnSuccessListener {
            UsageAnalyticsRepository.trackEvent(uid, "orders", "ORDER_STATUS_$next",
                mapOf("operationId" to order.id, "correlationId" to order.id, "stage" to "STATUS_UPDATE_CONFIRMED", "success" to true))
            val updated = order.copy(status = next)
            orders = orders.map { current -> if (current.id == order.id) updated else current }
            if (selectedScannedOrder?.id == order.id) selectedScannedOrder = updated
        }.addOnFailureListener { feedback = it.localizedMessage ?: "No se pudo actualizar el estado." }
    }

    fun requestOrderPayment(order: OmniStoreOrder) {
        if (order.merchantUid != uid || order.status != "LISTO" || !order.availabilityReviewed ||
            order.paymentRequested || order.paymentStatus.startsWith("PAGADA")) {
            feedback = "El pago solo puede solicitarse cuando el pedido fue revisado, confirmado y marcado como listo."
            return
        }
        db.collection("storeOrders").document(order.id).update(
            mapOf(
                "paymentStatus" to "PENDIENTE",
                "paymentRequested" to true,
                "paymentRequestedAt" to FieldValue.serverTimestamp(),
                "paymentRequestedBy" to uid,
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).addOnSuccessListener {
            UsageAnalyticsRepository.trackEvent(uid, "orders", "ORDER_PAYMENT_REQUESTED", mapOf(
                "operationId" to order.id, "correlationId" to order.id,
                "stage" to "MERCHANT_REQUESTED_PAYMENT", "success" to true
            ))
            orders = orders.map { current ->
                if (current.id == order.id) current.copy(paymentStatus = "PENDIENTE", paymentRequested = true) else current
            }
            selectedScannedOrder = selectedScannedOrder?.takeIf { it.id == order.id }
                ?.copy(paymentStatus = "PENDIENTE", paymentRequested = true)
            feedback = "Solicitud de pago enviada al cliente. OMNI Pay quedó habilitado."
        }.addOnFailureListener { feedback = it.localizedMessage ?: "No se pudo solicitar el pago al cliente." }
    }

    fun cancelCustomerOrder(order: OmniStoreOrder) {
        if (order.customerUid != uid || order.status !in listOf("RECIBIDO", "ESPERA_CONFIRMACION_CLIENTE") || order.paymentStatus.startsWith("PAGADA")) {
            feedback = "Solo podés cancelar un pedido propio sin pagar que todavía no fue cerrado."
            return
        }
        db.collection("storeOrders").document(order.id).update(
            mapOf("status" to "CANCELADO", "updatedAt" to FieldValue.serverTimestamp())
        ).addOnSuccessListener {
            UsageAnalyticsRepository.trackEvent(uid, "orders", "ORDER_CANCELLED_BY_CUSTOMER", mapOf("operationId" to order.id, "stage" to "CUSTOMER_CANCELLATION_CONFIRMED", "success" to true))
            feedback = "El pedido fue cancelado."
        }.addOnFailureListener { feedback = it.localizedMessage ?: "No se pudo cancelar el pedido." }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF070A14)).verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Volver", tint = Color.White) }
            Column(Modifier.weight(1f)) {
                Text(if (merchantMode) "PANEL DE PEDIDOS" else "PEDIDOS OMNI", color = Color(0xFF57DDF5), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                Text(if (merchantMode) store.name else "Pedido QR · ${store.name}", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }

        if (merchantMode) {
            Text("Acceso rápido para leer el QR del cliente, revisar el pedido y gestionar cobro DEMO, preparación y entrega.", color = Color(0xFF9AAAC2), fontSize = 11.sp)
            if (merchantReady && merchantError == null) {
                OrderQRScanner(
                    onOrderScanned = { handleScannedOrder(it) },
                    onLegacyWalletQr = { feedback = "Este es un QR de cuenta, no un QR de pedido." },
                    onDemoOrderScanned = {
                        feedback = "QR DEMO local detectado. Es solo una simulación: no existe un pedido registrado y no se puede aceptar, cobrar ni entregar."
                    },
                    onError = { feedback = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    buttonLabel = "Escanear QR · Pedidos y cobros",
                    buttonColor = Color(0xFF087EAB)
                )
            }
            if (merchantError != null) Text(merchantError.orEmpty(), color = Color(0xFFFF9B8F), fontSize = 12.sp, modifier = Modifier.padding(8.dp))
            else if (!merchantReady) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color(0xFF57DDF5)) }
        } else {
            Text(
                if (demoMode) "Elegí un comercio de prueba, armá tu carrito y generá un QR de demostración sin conexión."
                else "Elegí un comercio, seleccioná sus productos y enviá tu pedido. El QR identifica el pedido; no acredita un pago.",
                color = Color(0xFF9AAAC2), fontSize = 11.sp
            )
            if (demoMode) {
                Surface(
                    color = Color(0xFF201B35),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                ) {
                    Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("MODO DEMOSTRACIÓN LOCAL", color = Color(0xFFB69CFF), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                        Text("No consulta ni guarda datos en Firebase. El QR generado es ilustrativo: no envía el pedido al comercio y no sirve para retirar, cobrar ni acreditar pagos.", color = Color(0xFFE0D9F5), fontSize = 10.sp)
                    }
                }
            } else {
                Text("CATÁLOGO DE PRUEBA · Los precios y la disponibilidad deben confirmarse con el comercio. OMNI Pay no procesa dinero real en este flujo.", color = Color(0xFFFFC978), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 5.dp))
            }
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                items(availableStores.filter { it.id != managedStore.id }, key = { it.id }) { candidate ->
                    FilterChip(
                        selected = candidate.id == store.id,
                        onClick = { if (candidate.id != store.id) viewModel.selectStoreForOrders(candidate) },
                        label = { Text(candidate.name.substringBefore(" - ").take(22), maxLines = 1) }
                    )
                }
            }
            if (store.inventory.none { it.inStock }) Text("Este comercio todavía no publicó productos para pedidos.", color = Color(0xFFFFC978), fontSize = 12.sp)
            else {
                OutlinedTextField(
                    value = productSearch,
                    onValueChange = { productSearch = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    singleLine = true,
                    label = { Text("Buscar producto o categoría") },
                    placeholder = { Text("Ej.: leche, bebidas, limpieza") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF00E5FF),
                        unfocusedBorderColor = Color(0xFF29415F),
                        focusedLabelColor = Color(0xFF00E5FF),
                        cursorColor = Color(0xFF00E5FF)
                    )
                )
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    item {
                        FilterChip(
                            selected = selectedProductCategory == null,
                            onClick = { selectedProductCategory = null },
                            label = { Text("Todas") }
                        )
                    }
                    items(ProductCategory.values().toList()) { category ->
                        FilterChip(
                            selected = selectedProductCategory == category,
                            onClick = { selectedProductCategory = if (selectedProductCategory == category) null else category },
                            label = { Text(category.displayName) }
                        )
                    }
                }
                if (visibleProducts.isEmpty()) {
                    Text("No hay productos que coincidan con la búsqueda en este comercio.", color = Color(0xFFFFC978), fontSize = 12.sp)
                }
                Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                visibleProducts.forEach { product ->
                    OrderProductRow(product, cart[product.id] ?: 0,
                        onAdd = {
                            if (!cart.containsKey(product.id) && cart.size >= 30) {
                                feedback = "El pedido admite hasta 30 productos distintos."
                            } else {
                                updateCart(cart + (product.id to ((cart[product.id] ?: 0) + 1)))
                            }
                        },
                        onRemove = {
                            val next = (cart[product.id] ?: 0) - 1
                            updateCart(if (next <= 0) cart - product.id else cart + (product.id to next))
                        }
                    )
                }
                }
            }
            if (cartLines.isNotEmpty()) {
                Surface(color = Color(0xFF101B30), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("${cartLines.sumOf { it.quantity }} productos · Total estimado OM$ ${String.format("%,.2f", total)}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(selected = fulfillmentType == "RETIRO", onClick = { fulfillmentType = "RETIRO" }, label = { Text("Retirar") })
                            FilterChip(selected = fulfillmentType == "DELIVERY", onClick = { fulfillmentType = "DELIVERY" }, label = { Text("Delivery") })
                        }
                        if (fulfillmentType == "DELIVERY") {
                            OutlinedTextField(deliveryAddress, { deliveryAddress = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Dirección de entrega") }, singleLine = true)
                        }
                        Text(
                            if (demoMode) "La confirmación genera un QR local de prueba; no se envía ninguna orden."
                            else "Pedido y QR conectados al comercio. El pago se autoriza después desde OMNI Pay DEMO (máximo OM$ 50.000).",
                            color = Color(0xFF9AAAC2), fontSize = 10.sp
                        )
                        Button(onClick = { showOrderReview = true }, enabled = !sending, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF087EAB))) {
                            Text(if (demoMode) "Revisar compra DEMO" else "Revisar carrito")
                        }
                    }
                }
            }
        }

        if (merchantMode && merchantReady && merchantError == null) {
            if (orders.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(vertical = 18.dp), contentAlignment = Alignment.Center) { Text("Todavía no recibiste pedidos.", color = Color(0xFF9AAAC2), fontSize = 12.sp) }
            } else {
                Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 90.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    orders.forEach { order ->
                        OrderStatusCard(
                            order, merchantMode = true,
                            onAdvance = { next -> advanceOrder(order, next) },
                            onRequestPayment = { requestOrderPayment(order) },
                            onReviewAvailability = { reviewOrder = order; missingProductIds = emptySet(); availabilityNote = "" }
                        )
                    }
                }
            }
        } else if (!merchantMode) {
            val ownOrders = orders.filter { it.customerUid == uid }
            if (ownOrders.isNotEmpty()) {
                Text("MIS PEDIDOS", color = Color(0xFF57DDF5), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 80.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ownOrders.forEach { order ->
                        OrderStatusCard(
                            order, merchantMode = false,
                            onAdvance = { next -> if (next == "CANCELADO") cancelCustomerOrder(order) },
                            onAcceptProposal = { acceptAvailabilityProposal(order) },
                            onPay = { payOrderDemo(order) },
                            paying = payingOrderId == order.id
                        )
                    }
                }
            }
        }
    }

    selectedScannedOrder?.let { scanned ->
        AlertDialog(
            onDismissRequest = { selectedScannedOrder = null },
            containerColor = Color(0xFF101B30),
            title = { Text("Pedido OMNI verificado", color = Color.White) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("Cliente: ${scanned.customerName.ifBlank { "Cliente OMNI" }}", color = Color(0xFF57DDF5), fontWeight = FontWeight.Bold)
                    if (scanned.customerPhone.isNotBlank()) Text("Teléfono: ${scanned.customerPhone}", color = Color.White, fontSize = 11.sp)
                    Divider(color = Color(0xFF29415F))
                    scanned.items.forEach { line ->
                        Text("${line.quantity} × ${line.name} · OM$ ${String.format("%,.2f", line.price * line.quantity)}", color = Color.White, fontSize = 11.sp)
                    }
                    Text("Total: OM$ ${String.format("%,.2f", scanned.total)}", color = Color.White, fontWeight = FontWeight.ExtraBold)
                    Text(if (scanned.fulfillmentType == "DELIVERY") "Delivery: ${scanned.deliveryAddress}" else "Modalidad: Retiro en comercio", color = Color(0xFFB8C8D9), fontSize = 11.sp)
                    Text("Estado: ${scanned.status.replace("_", " ")}", color = Color(0xFF57DDF5), fontSize = 11.sp)
                    Text("Pago: ${scanned.paymentStatus}", color = if (scanned.paymentStatus.startsWith("PAGADA")) Color(0xFF65E6A5) else Color(0xFFFFC978), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    if (scanned.paymentRequested && scanned.paymentStatus == "PENDIENTE") Text("Solicitud enviada. El cliente ya puede autorizar el pago con OMNI Pay DEMO.", color = Color(0xFFFFC978), fontSize = 10.sp)
                    else if (scanned.status == "LISTO") Text("Pedido listo. Solicitá el pago para habilitar OMNI Pay en el cliente.", color = Color(0xFFFFC978), fontSize = 10.sp)
                    else Text("El pago permanece bloqueado hasta que el comercio marque el pedido como listo y solicite el cobro.", color = Color(0xFFFFC978), fontSize = 10.sp)
                }
            },
            confirmButton = {
                val next = when (scanned.status) {
                    "RECIBIDO" -> "ACEPTADO"
                    "ACEPTADO" -> "EN_REVISION"
                    "DISPONIBILIDAD_CONFIRMADA", "CONFIRMADO_CLIENTE" -> "EN_PREPARACION"
                    "EN_PREPARACION" -> "LISTO"
                    "LISTO" -> if (scanned.fulfillmentType == "DELIVERY") "EN_CAMINO" else "ENTREGADO"
                    "EN_CAMINO" -> "ENTREGADO"
                    else -> ""
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (scanned.status == "EN_REVISION") {
                        Button(onClick = { reviewOrder = scanned; missingProductIds = emptySet(); availabilityNote = "" }, modifier = Modifier.fillMaxWidth()) { Text("Revisar disponibilidad") }
                    }
                    if (scanned.status == "LISTO" && !scanned.paymentRequested && !scanned.paymentStatus.startsWith("PAGADA")) {
                        Button(onClick = { requestOrderPayment(scanned) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Solicitar pago al cliente")
                        }
                    }
                    if (next.isNotBlank()) {
                        val paymentBlocked = next in listOf("EN_CAMINO", "ENTREGADO") && !scanned.paymentStatus.startsWith("PAGADA")
                        Button(onClick = { advanceOrder(scanned, next) }, enabled = !paymentBlocked, modifier = Modifier.fillMaxWidth()) {
                            Text(when(next) {
                                "ACEPTADO" -> "Aceptar pedido"
                                "EN_REVISION" -> "Revisar disponibilidad"
                                "EN_PREPARACION" -> "Preparar pedido"
                                "LISTO" -> "Marcar listo"
                                "EN_CAMINO" -> "Enviar delivery"
                                else -> "Marcar entregado"
                            })
                        }
                    } else {
                        TextButton(onClick = { selectedScannedOrder = null }) { Text("Cerrar") }
                    }
                }
            },
            dismissButton = {
                Row {
                    if (scanned.status in listOf("RECIBIDO", "ESPERA_CONFIRMACION_CLIENTE")) TextButton(onClick = {
                        advanceOrder(scanned, "RECHAZADO")
                        selectedScannedOrder = null
                    }) { Text("Rechazar") }
                    TextButton(onClick = { selectedScannedOrder = null }) { Text("Cerrar") }
                }
            }
        )
    }

    reviewOrder?.let { reviewing ->
        AlertDialog(
            onDismissRequest = { reviewOrder = null }, containerColor = Color(0xFF101B30),
            title = { Text("Revisión de disponibilidad", color = Color.White) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("Desmarcá los productos que NO están disponibles. El cliente recibirá el detalle y total actualizado antes de pagar.", color = Color(0xFFB8C8D9), fontSize = 11.sp)
                    reviewing.items.forEach { line ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = line.productId !in missingProductIds, onCheckedChange = { available ->
                                missingProductIds = if (available) missingProductIds - line.productId else missingProductIds + line.productId
                            })
                            Text(line.quantity.toString() + " × " + line.name, color = Color.White, fontSize = 11.sp)
                        }
                    }
                    OutlinedTextField(value = availabilityNote, onValueChange = { availabilityNote = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Mensaje al cliente (obligatorio si falta algo)") }, minLines = 2)
                    val availableTotal = reviewing.items.filterNot { it.productId in missingProductIds }.sumOf { it.price * it.quantity }
                    Text("Total propuesto: OM$ " + String.format("%,.2f", availableTotal), color = Color(0xFF57DDF5), fontWeight = FontWeight.Bold)
                }
            },
            confirmButton = { Button(onClick = { submitAvailabilityReview(reviewing) }, modifier = Modifier.fillMaxWidth()) { Text(if (missingProductIds.isEmpty()) "Confirmar disponibilidad" else "Enviar propuesta al cliente") } },
            dismissButton = { TextButton(onClick = { reviewOrder = null }) { Text("Volver") } }
        )
    }

    if (showOrderReview) {
        AlertDialog(
            onDismissRequest = { if (!sending) showOrderReview = false },
            containerColor = Color(0xFF101B30),
            title = { Text(if (demoMode) "Confirmar compra DEMO" else "Confirmar pedido", color = Color.White) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(store.name, color = Color(0xFF57DDF5), fontWeight = FontWeight.Bold)
                    cartLines.forEach { line ->
                        Text("${line.quantity} × ${line.name} · OM$ ${String.format("%,.2f", line.price * line.quantity)}", color = Color.White, fontSize = 11.sp)
                    }
                    Text("Total: OM$ ${String.format("%,.2f", total)}", color = Color.White, fontWeight = FontWeight.Bold)
                    Text(if (fulfillmentType == "DELIVERY") "Entrega: ${deliveryAddress.trim()}" else "Modalidad: Retiro en el comercio", color = Color(0xFFB8C8D9), fontSize = 11.sp)
                    Text(
                        if (demoMode) "El QR será solo una muestra visual. No habrá pedido enviado ni pago."
                        else "El QR identifica el pedido. El cliente autoriza el pago ficticio desde su OMNI Pay DEMO; el comercio no puede cobrar sin esa autorización.",
                        color = Color(0xFFFFC978), fontSize = 10.sp
                    )
                }
            },
            confirmButton = { Button(onClick = { showOrderReview = false; submitOrder() }, enabled = !sending) { Text(if (demoMode) "Generar QR DEMO" else "Confirmar y generar QR") } },
            dismissButton = { TextButton(onClick = { showOrderReview = false }, enabled = !sending) { Text("Volver al carrito") } }
        )
    }
    createdOrder?.let { order ->
        val payload = OrderQRCodec.encode(
            order.id, order.storeName, order.total,
            order.items.map { OrderQRItem(it.productId, it.name, it.price, it.quantity) },
            order.qrNonce,
            isLocalDemo = order.isLocalDemo
        )
        val bitmap = remember(payload) {
            runCatching {
                val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 480, 480)
                Bitmap.createBitmap(480, 480, Bitmap.Config.RGB_565).also { image ->
                    for (x in 0 until 480) for (y in 0 until 480) {
                        image.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                    }
                }
            }.getOrNull()
        }
        AlertDialog(
            onDismissRequest = { createdOrder = null },
            containerColor = Color(0xFF101B30),
            title = { Text(if (order.isLocalDemo) "QR DEMO generado" else "Pedido enviado", color = Color.White) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (order.isLocalDemo) "Este QR es una simulación local. No se guardó ni envió ningún pedido al comercio."
                        else "Mostrá este QR en el comercio para identificar tu pedido.",
                        color = Color(0xFFB8C8D9), fontSize = 11.sp
                    )
                    bitmap?.let { qrBitmap ->
                        Image(qrBitmap.asImageBitmap(), "QR del pedido", modifier = Modifier.size(190.dp))
                        OutlinedButton(onClick = {
                            runCatching {
                                val folder = File(context.cacheDir, "shared_qr").apply { mkdirs() }
                                val qrFile = File(folder, if (order.isLocalDemo) "omni-pedido-demo-${order.id.take(10)}.png" else "omni-pedido-${order.id.take(10)}.png")
                                FileOutputStream(qrFile).use { output -> check(qrBitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) }
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", qrFile)
                                if (!order.isLocalDemo) {
                                    UsageAnalyticsRepository.trackCurrentAction("orders", "ORDER_SHARED", mapOf("method" to "share_sheet"))
                                }
                                val share = Intent(Intent.ACTION_SEND).apply {
                                    type = "image/png"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    putExtra(
                                        Intent.EXTRA_TEXT,
                                        if (order.isLocalDemo) "QR DEMO LOCAL OMNI\nNo es un pedido real. No sirve para retirar, cobrar ni acreditar pagos.\nComercio de muestra: ${order.storeName}\nTotal ilustrativo: OM$ ${String.format("%,.2f", order.total)}"
                                        else "Pedido OMNI ${order.id}\nComercio: ${order.storeName}\nTotal: OM$ ${String.format("%,.2f", order.total)}\nPago pendiente al retirar o recibir."
                                    )
                                    clipData = ClipData.newUri(context.contentResolver, "QR Pedido OMNI", uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(share, if (order.isLocalDemo) "Compartir QR DEMO" else "Compartir QR del pedido"))
                            }.onFailure { feedback = it.localizedMessage ?: "No se pudo compartir el QR." }
                        }) { Text("Compartir QR") }
                    } ?: Text("No se pudo generar la imagen QR.", color = Color(0xFFFF9B8F), fontSize = 10.sp)
                    Text(
                        if (order.isLocalDemo) "Código ${order.id} · SOLO DEMOSTRACIÓN"
                        else "Pedido ${order.id.take(10)} · ${order.status}",
                        color = Color(0xFF57DDF5), fontWeight = FontWeight.Bold, fontSize = 11.sp
                    )
                    Text(
                        if (order.isLocalDemo) "No genera retiro, cobro, pago ni registro comercial."
                        else "Pago pendiente · se abona al retirar o recibir. Este QR no es un comprobante de pago.",
                        color = Color(0xFFFFC978), fontSize = 10.sp
                    )
                }
            },
            confirmButton = {
                Button(onClick = { createdOrder = null }) {
                    Text(if (order.isLocalDemo) "Cerrar demostración" else "Ver pedido y opciones de pago")
                }
            }
        )
    }

    feedback?.let { message ->
        AlertDialog(onDismissRequest = { feedback = null }, title = { Text("Pedidos OMNI") }, text = { Text(message) }, confirmButton = { TextButton(onClick = { feedback = null }) { Text("Aceptar") } })
    }
}

@Composable
private fun OrderProductRow(product: StoreProduct, quantity: Int, onAdd: () -> Unit, onRemove: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF101B30)), shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(product.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text("OM$ ${String.format("%,.2f", product.price)} · ${product.unitLabel}", color = Color(0xFF57DDF5), fontSize = 10.sp)
            }
            IconButton(onClick = onRemove, enabled = quantity > 0) { Icon(Icons.Default.Remove, "Quitar", tint = Color(0xFFB8C8D9)) }
            Text(quantity.toString(), color = Color.White, fontWeight = FontWeight.Bold)
            IconButton(onClick = onAdd) { Icon(Icons.Default.Add, "Agregar", tint = Color(0xFF57DDF5)) }
        }
    }
}

@Composable
private fun OrderStatusCard(
    order: OmniStoreOrder,
    merchantMode: Boolean,
    onAdvance: (String) -> Unit,
    onPay: (() -> Unit)? = null,
    onRequestPayment: (() -> Unit)? = null,
    onReviewAvailability: (() -> Unit)? = null,
    onAcceptProposal: (() -> Unit)? = null,
    paying: Boolean = false
) {
    val context = LocalContext.current
    val next = when (order.status) {
        "RECIBIDO" -> "ACEPTADO"
        "ACEPTADO" -> "EN_REVISION"
        "DISPONIBILIDAD_CONFIRMADA", "CONFIRMADO_CLIENTE" -> "EN_PREPARACION"
        "EN_PREPARACION" -> "LISTO"
        "LISTO" -> if (order.fulfillmentType == "DELIVERY") "EN_CAMINO" else "ENTREGADO"
        "EN_CAMINO" -> "ENTREGADO"
        else -> ""
    }
    val label = when (order.status) {
        "RECIBIDO" -> "Recibido"
        "ACEPTADO" -> "Aceptado · pendiente de revisión"
        "EN_REVISION" -> "Revisión de disponibilidad"
        "ESPERA_CONFIRMACION_CLIENTE" -> "Esperando tu confirmación"
        "DISPONIBILIDAD_CONFIRMADA" -> "Disponibilidad confirmada"
        "CONFIRMADO_CLIENTE" -> "Propuesta aceptada"
        "EN_PREPARACION" -> "En preparación"
        "LISTO" -> "Listo para retirar"
        "EN_CAMINO" -> "En camino"
        "ENTREGADO" -> "Entregado"
        "RECHAZADO" -> "Rechazado"
        "CANCELADO" -> "Cancelado"
        else -> order.status
    }
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF101B30)), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (merchantMode) order.customerName else order.storeName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text("Pedido ${order.id.take(9)} · ${label}", color = Color(0xFF57DDF5), fontSize = 10.sp)
                }
                Text("OM$ ${String.format("%,.2f", order.total)}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            }
            order.items.forEach { Text("${it.quantity} × ${it.name}", color = Color(0xFFB8C8D9), fontSize = 10.sp) }
            if (!merchantMode && order.status == "ESPERA_CONFIRMACION_CLIENTE") {
                Text("El comercio informó: " + order.availabilityMessage, color = Color(0xFFFFC978), fontSize = 10.sp)
                order.proposedItems.forEach { proposed ->
                    Text(proposed.quantity.toString() + " × " + proposed.name, color = Color(0xFFB8C8D9), fontSize = 10.sp)
                }
                Text("Nuevo total: OM$ " + String.format("%,.2f", order.proposedTotal ?: 0.0), color = Color(0xFF57DDF5), fontWeight = FontWeight.Bold, fontSize = 11.sp)
            }
            Text(if (order.fulfillmentType == "DELIVERY") "Delivery · ${order.deliveryAddress}" else "Retiro en el comercio", color = Color(0xFFB8C8D9), fontSize = 9.sp)
            Text(
                when {
                    order.paymentStatus.startsWith("PAGADA") -> "Pago: acreditado · DEMO"
                    order.paymentRequested && order.paymentStatus == "PENDIENTE" -> "Pago solicitado por el comercio · esperando al cliente"
                    order.status == "LISTO" -> "Pedido listo · esperando que el comercio solicite el pago"
                    order.status == "ESPERA_CONFIRMACION_CLIENTE" -> "Pago bloqueado · falta tu confirmación de disponibilidad"
                    else -> "Pago bloqueado · se habilita cuando el comercio termina y solicita el cobro"
                },
                color = if (order.paymentStatus.startsWith("PAGADA")) Color(0xFF65E6A5) else Color(0xFFFFC978),
                fontSize = 9.sp
            )
            if (!merchantMode && onPay != null && order.paymentRequested && order.paymentStatus == "PENDIENTE" &&
                order.status == "LISTO") {
                if (order.total <= 50000.0) {
                    Button(onClick = onPay, enabled = !paying, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF087EAB))) {
                        Text(if (paying) "Procesando pago DEMO…" else "Pagar con OMNI Pay DEMO", fontSize = 10.sp)
                    }
                } else {
                    Text("El total supera el límite por operación de OMNI Pay DEMO (OM$ 50.000).", color = Color(0xFFFFC978), fontSize = 9.sp)
                }
            }
            if (merchantMode && order.customerPhone.isNotBlank()) {
                TextButton(onClick = {
                    val phoneNumber = order.customerPhone.filter { it.isDigit() }
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$phoneNumber"))) }
                }) { Text("Contactar cliente por WhatsApp", color = Color(0xFF57DDF5), fontSize = 10.sp) }
            }
            if (!merchantMode && order.status == "ESPERA_CONFIRMACION_CLIENTE" && order.proposedItems.isNotEmpty()) {
                Button(onClick = { onAcceptProposal?.invoke() }, enabled = onAcceptProposal != null, modifier = Modifier.fillMaxWidth()) { Text("Aceptar propuesta y nuevo total") }
            }
            if (!merchantMode && order.status in listOf("RECIBIDO", "ESPERA_CONFIRMACION_CLIENTE")) {
                OutlinedButton(onClick = { onAdvance("CANCELADO") }, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancelar pedido", fontSize = 10.sp)
                }
            }
            if (merchantMode && order.status == "EN_REVISION") {
                Button(onClick = { onReviewAvailability?.invoke() }, enabled = onReviewAvailability != null, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF087EAB))) { Text("Revisar disponibilidad") }
            }
            if (merchantMode && order.status == "LISTO" && !order.paymentRequested && !order.paymentStatus.startsWith("PAGADA")) {
                Button(onClick = { onRequestPayment?.invoke() }, enabled = onRequestPayment != null, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF087EAB))) {
                    Text("Solicitar pago al cliente")
                }
            }
            if (merchantMode && order.paymentRequested && order.paymentStatus == "PENDIENTE") {
                Text("Solicitud de pago enviada · esperando autorización del cliente", color = Color(0xFFFFC978), fontSize = 9.sp)
            }
            if (merchantMode && next.isNotBlank()) {
                val paymentRequired = next in listOf("EN_CAMINO", "ENTREGADO") && !order.paymentStatus.startsWith("PAGADA")
                if (paymentRequired) Text("Para despachar o entregar, el cliente debe autorizar el pago OMNI Pay DEMO.", color = Color(0xFFFFC978), fontSize = 9.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Button(onClick = { onAdvance(next) }, enabled = !paymentRequired, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF087EAB))) {
                        Text(when (next) {
                            "ACEPTADO" -> "Aceptar pedido"
                            "EN_PREPARACION" -> "Comenzar preparación"
                            "LISTO" -> "Marcar listo"
                            "EN_CAMINO" -> "Enviar delivery"
                            else -> "Marcar entregado"
                        }, fontSize = 10.sp)
                    }
                    if (order.status == "RECIBIDO" && !order.paymentStatus.startsWith("PAGADA")) {
                        OutlinedButton(onClick = { onAdvance("RECHAZADO") }) {
                            Text("Rechazar", fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}
