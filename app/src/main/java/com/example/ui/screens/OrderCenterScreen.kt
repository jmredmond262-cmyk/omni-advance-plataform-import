package com.example.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.content.ClipData
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.example.data.api.RealLocationHelper
import com.example.data.wallet.DemoWalletRepository
import com.example.data.merchant.MerchantProfileRepository
import com.example.data.wallet.DemoCardGatewayRepository
import com.example.model.StoreProduct
import com.example.model.ProductCategory
import com.example.ui.MainViewModel
import com.example.data.analytics.UsageAnalyticsRepository
import com.example.data.analytics.OmniTracker
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.functions.FirebaseFunctions
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.util.UUID
import java.util.Locale
import java.text.SimpleDateFormat
import org.json.JSONObject
import kotlinx.coroutines.launch

private data class OrderLine(val productId: String, val name: String, val price: Double, val quantity: Int)
private data class OrderStatusEvent(
    val fromStatus: String,
    val toStatus: String,
    val actorRole: String,
    val reason: String,
    val createdAt: com.google.firebase.Timestamp?
)
private data class CashbackTerms(
    val enabled: Boolean = false,
    val percentage: Double = 0.0,
    val capPerPurchase: Double? = null,
    val monthlyCap: Double? = null
)
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
    val cashbackPolicy: CashbackTerms = CashbackTerms(),
    val paymentMethod: String = "PAGO_AL_RETIRAR",
    val environment: String = "DEMO",
    val isLocalDemo: Boolean = false,
    val rejectionReason: String = "",
    val subtotal: Double = 0.0,
    val deliveryFee: Double = 0.0
)

private fun decodeCashbackTerms(raw: Any?): CashbackTerms {
    val map = raw as? Map<*, *> ?: return CashbackTerms()
    val percentage = (map["percentage"] as? Number)?.toDouble() ?: 0.0
    val purchaseCap = (map["capPerPurchase"] as? Number)?.toDouble()
    val monthlyCap = (map["monthlyCap"] as? Number)?.toDouble()
    val enabled = map["enabled"] == true && percentage.isFinite() && percentage in 0.000001..100.0
    return CashbackTerms(
        enabled = enabled,
        percentage = if (enabled) percentage else 0.0,
        capPerPurchase = purchaseCap?.takeIf { it.isFinite() && it > 0.0 },
        monthlyCap = monthlyCap?.takeIf { it.isFinite() && it > 0.0 }
    )
}

@Composable
fun OrderCenterScreen(viewModel: MainViewModel, merchantMode: Boolean, onBack: () -> Unit, demoMode: Boolean = false) {
    val context = LocalContext.current
    val screenScope = rememberCoroutineScope()
    val uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
    val profile by viewModel.userProfile.collectAsState()
    val selectedStore by viewModel.selectedStore.collectAsState()
    val liveStores by viewModel.stores.collectAsState()
    val managedStore by viewModel.managedStore.collectAsState()
    var demoStores by remember { mutableStateOf<List<Store>>(emptyList()) }
    var demoStoresLoading by remember { mutableStateOf(false) }
    val availableStores = if (demoMode) demoStores else liveStores
    val selectedDemoStore = demoStores.firstOrNull { it.id == selectedStore?.id } ?: demoStores.firstOrNull()
    val store = if (merchantMode) managedStore else if (demoMode) {
        selectedDemoStore ?: (selectedStore ?: managedStore)
    } else (selectedStore ?: managedStore)
    val db = remember { FirebaseFirestore.getInstance() }
    val walletRepository = remember { DemoWalletRepository() }
    val demoCardGateway = remember { DemoCardGatewayRepository() }

    var merchantReady by remember { mutableStateOf(false) }
    var merchantError by remember { mutableStateOf<String?>(null) }
    var requiresClaimCode by remember { mutableStateOf(false) }
    var merchantClaimCode by remember { mutableStateOf("") }
    var claimingMerchant by remember { mutableStateOf(false) }
    var orders by remember { mutableStateOf<List<OmniStoreOrder>>(emptyList()) }
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
    var deliveryLatitude by remember { mutableStateOf<Double?>(null) }
    var deliveryLongitude by remember { mutableStateOf<Double?>(null) }
    var locatingDelivery by remember { mutableStateOf(false) }
    val deliveryLocationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) {
            screenScope.launch {
                locatingDelivery = true
                val location = RealLocationHelper.getCurrentLocation(context)
                deliveryLatitude = location?.takeIf { it.accuracy > 0f && it.accuracy <= 50f }?.latitude
                deliveryLongitude = location?.takeIf { it.accuracy > 0f && it.accuracy <= 50f }?.longitude
                locatingDelivery = false
                feedback = if (deliveryLatitude != null && deliveryLongitude != null) "Ubicación GPS confirmada para este pedido." else "La ubicación no tiene precisión suficiente (máximo 50 m). Activá el GPS o intentá desde un lugar abierto."
            }
        } else feedback = "Para verificar la cobertura necesitás autorizar la ubicación mientras usás OMNI."
    }
    var showRejectReasonDialog by remember { mutableStateOf(false) }
    var rejectReasonInput by remember { mutableStateOf("") }

    LaunchedEffect(demoMode) {
        if (demoMode) {
            demoStoresLoading = true
            MerchantProfileRepository.loadDemoStores { result ->
                demoStoresLoading = false
                result.onSuccess { loaded ->
                    demoStores = loaded
                    if (loaded.isNotEmpty() && loaded.none { it.id == selectedStore?.id }) {
                        viewModel.selectStoreForOrders(loaded.first())
                    }
                }.onFailure { error ->
                    feedback = error.localizedMessage ?: "No se pudieron cargar los comercios DEMO."
                }
            }
        } else {
            demoStores = emptyList()
        }
    }
    var createdOrder by remember { mutableStateOf<OmniStoreOrder?>(null) }
    var showOrderReview by remember { mutableStateOf(false) }
    var reviewCashbackTerms by remember(store.id) {
        mutableStateOf(CashbackTerms(
            store.cashbackEnabled, store.cashbackPercent,
            store.cashbackPerPurchaseCap, store.cashbackMonthlyCap
        ))
    }
    var loadingCashbackReview by remember { mutableStateOf(false) }
    var selectedScannedOrder by remember { mutableStateOf<OmniStoreOrder?>(null) }
    var payingOrderId by remember { mutableStateOf<String?>(null) }
    var selectedCardOrder by remember { mutableStateOf<OmniStoreOrder?>(null) }
    var cardPaymentInProgress by remember { mutableStateOf(false) }
    var demoCardBrand by remember { mutableStateOf("VISA") }
    var demoCardApproved by remember { mutableStateOf(true) }
    var selectedRefundOrder by remember { mutableStateOf<OmniStoreOrder?>(null) }
    var refundReasonInput by remember { mutableStateOf("") }
    var refundInProgress by remember { mutableStateOf(false) }

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
            cashbackPolicy = decodeCashbackTerms(doc.get("cashbackPolicy")),
            paymentMethod = doc.getString("paymentMethod") ?: "PAGO_AL_RETIRAR",
            environment = doc.getString("environment").orEmpty(),
            rejectionReason = doc.getString("rejectionReason").orEmpty(),
            subtotal = (doc.get("subtotal") as? Number)?.toDouble() ?: ((doc.get("total") as? Number)?.toDouble() ?: 0.0),
            deliveryFee = (doc.get("deliveryFee") as? Number)?.toDouble() ?: 0.0
        )
    }.getOrNull()

    LaunchedEffect(store.id) {
        // El carrito pertenece a un único comercio: nunca mezclar artículos de locales distintos.
        productSearch = ""
        selectedProductCategory = null
        deliveryAddress = ""
        deliveryLatitude = null
        deliveryLongitude = null
        fulfillmentType = if (store.pickupEnabled) "RETIRO" else "DELIVERY"
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
            requiresClaimCode = false
            merchantClaimCode = ""
            val profileRef = db.collection("merchantProfiles").document(store.id)
            profileRef.get().addOnSuccessListener { doc ->
                if (doc.exists()) {
                    val ownerUid = doc.getString("ownerUid").orEmpty()
                    val environment = doc.getString("environment").orEmpty()
                    val seedVersion = (doc.get("seedVersion") as? Number)?.toInt()
                    val seededDemo = environment == "DEMO" && seedVersion == 1
                    if (ownerUid == uid && seededDemo && doc.id == store.id) {
                        merchantReady = true
                    } else if (ownerUid.isBlank() && seededDemo) {
                        requiresClaimCode = true
                    } else {
                        merchantError = "Este comercio ya está asociado a otra cuenta o no está habilitado para titularidad DEMO."
                    }
                } else {
                    requiresClaimCode = true
                }
            }.addOnFailureListener { merchantError = it.localizedMessage ?: "No se pudo verificar el comercio." }
        } else if (!merchantMode) {
            merchantReady = true
            requiresClaimCode = false
        }
    }

    DisposableEffect(merchantMode, merchantReady, store.id, uid, demoMode) {
        var listener: ListenerRegistration? = null
        if (!demoMode && uid.isNotBlank() && (!merchantMode || merchantReady)) {
            val query = if (merchantMode) {
                db.collection("storeOrders").whereEqualTo("merchantUid", uid)
            } else {
                db.collection("storeOrders").whereEqualTo("customerUid", uid)
            }
            listener = query.addSnapshotListener { snapshot, error ->
                if (error != null) {
                    feedback = error.localizedMessage ?: "No se pudieron sincronizar los pedidos."
                } else {
                    val loadedOrders = snapshot?.documents.orEmpty().mapNotNull(::decodeOrder)
                        .filter { !merchantMode || it.storeId == store.id }
                        .sortedByDescending { it.id }
                    orders = loadedOrders
                    loadedOrders.filter { it.paymentStatus == "PENDIENTE" }.forEach { pendingOrder ->
                        db.collection("walletOperations")
                            .whereEqualTo(if (merchantMode) "recipientUid" else "senderUid", uid)
                            .whereEqualTo("orderId", pendingOrder.id)
                            .limit(1).get()
                            .addOnSuccessListener { payment ->
                                if (!payment.isEmpty) {
                                    orders = orders.map { current ->
                                        if (current.id == pendingOrder.id) current.copy(paymentStatus = "PAGADA · DEMO") else current
                                    }
                                }
                            }
                    }
                }
            }
        }
        onDispose { listener?.remove() }
    }

    val visibleProducts = store.inventory.filter { product ->
        product.inStock &&
            (selectedProductCategory == null || product.category == selectedProductCategory) &&
            (productSearch.isBlank() || product.name.contains(productSearch.trim(), ignoreCase = true) || product.category.displayName.contains(productSearch.trim(), ignoreCase = true))
    }
    val cartLines = if (demoMode && demoStores.isEmpty()) emptyList() else store.inventory.filter {
        (cart[it.id] ?: 0) > 0 && it.inStock
    }.map {
        OrderLine(it.id, it.name, it.price, cart[it.id] ?: 0)
    }
    val itemsSubtotal = cartLines.sumOf { it.price * it.quantity }
    val deliveryFee = if (fulfillmentType == "DELIVERY" && (store.freeDeliveryFrom == null || itemsSubtotal < store.freeDeliveryFrom)) store.deliveryFee else 0.0
    val total = itemsSubtotal + deliveryFee

    fun submitOrder() {
        if ((!demoMode && uid.isBlank()) || cartLines.isEmpty() || cartLines.size > 30 ||
            total <= 0.0 || total > 50000.0 || itemsSubtotal < store.minimumOrderAmount ||
            (fulfillmentType == "RETIRO" && !store.pickupEnabled) ||
            (fulfillmentType == "DELIVERY" && (!store.deliveryEnabled || deliveryAddress.isBlank() || deliveryLatitude == null || deliveryLongitude == null))) {
            feedback = if (fulfillmentType == "DELIVERY" && (deliveryLatitude == null || deliveryLongitude == null)) "Compartí tu ubicación GPS para comprobar que el domicilio esté dentro del radio del comercio." else "Revisá el pedido: modalidad habilitada, compra mínima OM$ ${store.minimumOrderAmount.toInt()}, máximo 30 productos y dirección obligatoria para delivery."
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
                isLocalDemo = true,
                subtotal = itemsSubtotal,
                deliveryFee = deliveryFee
            )
            updateCart(emptyMap())
            sending = false
            return
        }
        db.collection("merchantProfiles").document(store.id).get()
            .addOnSuccessListener { merchant ->
                val merchantUid = merchant.getString("ownerUid").orEmpty()
                if (!merchant.exists() || merchantUid.isBlank() || merchantUid == uid) {
                    sending = false
                    feedback = "Este comercio todavía no habilitó pedidos QR para clientes."
                } else {
                    val publishedInventory = (merchant.get("inventory") as? List<*>)?.mapNotNull { raw ->
                        raw as? Map<*, *>
                    }.orEmpty()
                    if (publishedInventory.isNotEmpty()) {
                        val staleLine = cartLines.firstOrNull { line ->
                            val current = publishedInventory.firstOrNull { it["id"] == line.productId }
                            val currentPrice = (current?.get("price") as? Number)?.toDouble()
                            current == null || current["inStock"] == false ||
                                currentPrice == null || !currentPrice.isFinite() ||
                                kotlin.math.abs(currentPrice - line.price) >= 0.005
                        }
                        if (staleLine != null) {
                            sending = false
                            feedback = "Cambió el precio o la disponibilidad de ${staleLine.name}. Actualizá el catálogo y revisá el pedido."
                            return@addOnSuccessListener
                        }
                    }
                    val cashbackPolicy = decodeCashbackTerms(merchant.get("cashback"))
                    if (cashbackPolicy != reviewCashbackTerms) {
                        reviewCashbackTerms = cashbackPolicy
                        sending = false
                        feedback = "El comercio actualizó las condiciones de cashback. Revisalas antes de confirmar."
                        showOrderReview = true
                        return@addOnSuccessListener
                    }
                    val cashbackPolicyData = mapOf(
                        "enabled" to cashbackPolicy.enabled,
                        "percentage" to cashbackPolicy.percentage,
                        "capPerPurchase" to cashbackPolicy.capPerPurchase,
                        "monthlyCap" to cashbackPolicy.monthlyCap
                    )
                    val payloadItems = cartLines.map { mapOf(
                        "productId" to it.productId, "name" to it.name,
                        "price" to it.price, "quantity" to it.quantity
                    ) }
                    val requestData = mutableMapOf<String, Any>(
                        "storeId" to store.id, "items" to payloadItems,
                        "fulfillmentType" to fulfillmentType,
                        "deliveryAddress" to deliveryAddress.trim(),
                        "customerPhone" to profile.phone,
                        "expectedTotal" to total,
                        "expectedCashbackPolicy" to cashbackPolicyData
                    )
                    if (fulfillmentType == "DELIVERY") {
                        requestData["deliveryLatitude"] = deliveryLatitude!!
                        requestData["deliveryLongitude"] = deliveryLongitude!!
                    }
                    FirebaseFunctions.getInstance("us-central1").getHttpsCallable("createDemoStoreOrder")
                        .call(requestData)
                        .addOnSuccessListener { result ->
                            @Suppress("UNCHECKED_CAST")
                            val response = result.data as? Map<String, Any?> ?: emptyMap()
                            val orderId = response["orderId"] as? String
                            val nonce = response["qrNonce"] as? String
                            val confirmedSubtotal = (response["subtotal"] as? Number)?.toDouble()
                            val confirmedFee = (response["deliveryFee"] as? Number)?.toDouble()
                            val confirmedTotal = (response["total"] as? Number)?.toDouble()
                            if (orderId.isNullOrBlank() || nonce.isNullOrBlank() || confirmedSubtotal == null || confirmedFee == null || confirmedTotal == null) {
                                sending = false
                                feedback = "El servidor no devolvió una confirmación válida del pedido."
                                return@addOnSuccessListener
                            }
                            UsageAnalyticsRepository.trackEvent(uid, "orders", "STORE_ORDER_CREATED", mapOf("operationId" to orderId, "stage" to "SERVER_VALIDATION_CONFIRMED", "success" to true))
                            sending = false
                            createdOrder = OmniStoreOrder(
                                orderId, store.id, response["storeName"] as? String ?: store.name, merchantUid, uid,
                                profile.fullName, profile.phone, cartLines, confirmedTotal,
                                "RECIBIDO", "PENDIENTE", fulfillmentType, deliveryAddress.trim(), nonce,
                                cashbackPolicy, subtotal = confirmedSubtotal, deliveryFee = confirmedFee
                            )
                            updateCart(emptyMap())
                        }
                        .addOnFailureListener { e ->
                            sending = false
                            feedback = e.localizedMessage ?: "No se pudo validar y registrar el pedido en el servidor."
                        }
                }
            }.addOnFailureListener { e ->
                sending = false
                feedback = e.localizedMessage ?: "No se pudo verificar el comercio."
            }
    }

    fun handleScannedOrder(payload: OrderQRPayload) {
        OmniTracker.track("orders", "ORDER_QR_SCAN_STARTED")
        db.collection("storeOrders").document(payload.orderId).get()
            .addOnSuccessListener { doc ->
                val order = decodeOrder(doc)
                val createdAt = doc.getTimestamp("createdAt")?.toDate()?.time ?: 0L
                val expired = createdAt == 0L || System.currentTimeMillis() - createdAt >= 24L * 60L * 60L * 1000L
                val itemsMatch = order != null &&
                    order.items.size == payload.items.size &&
                    order.items.zip(payload.items).all { (line, qrItem) ->
                        qrItem.productId == line.productId && qrItem.name == line.name &&
                            kotlin.math.abs(qrItem.price - line.price) < 0.005 &&
                            qrItem.quantity == line.quantity
                    }
                if (order == null || order.storeId != store.id || order.merchantUid != uid ||
                    order.qrNonce != payload.qrNonce || order.storeName != payload.merchantName ||
                    kotlin.math.abs(order.total - payload.totalAmount) >= 0.005 || !itemsMatch ||
                    expired || order.status in listOf("ENTREGADO", "RECHAZADO", "CANCELADO")) {
                    feedback = "QR inválido, vencido, modificado o no corresponde a este comercio."
                } else {
                    OmniTracker.track("orders", "ORDER_QR_VERIFIED", mapOf("operationId" to order.id, "stage" to "QR_VALIDATION", "success" to true))
                    if (order.paymentStatus.startsWith("PAGADA")) {
                        // El pago con tarjeta DEMO se registra en demoCardPayments, no en walletOperations.
                        selectedScannedOrder = order
                    } else {
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
            }
            .addOnFailureListener { feedback = "No se pudo verificar el pedido en Firestore." }
    }

    fun payOrderDemo(order: OmniStoreOrder) {
        if (payingOrderId != null) return
        if (order.customerUid != uid || order.paymentStatus != "PENDIENTE") {
            feedback = "Este pedido no está pendiente de pago en tu cuenta."
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
                                    if (current.id == order.id) current.copy(paymentStatus = "PAGADA · DEMO", paymentMethod = "OMNI_PAY_DEMO") else current
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

    fun payOrderWithDemoCard(order: OmniStoreOrder) {
        if (cardPaymentInProgress) return
        if (order.customerUid != uid || order.paymentStatus != "PENDIENTE" || order.environment != "DEMO") {
            feedback = "Este pedido no está habilitado para pago con tarjeta DEMO."
            return
        }
        cardPaymentInProgress = true
        val selectedBrand = demoCardBrand
        val shouldApprove = demoCardApproved
        demoCardGateway.simulateOrderPayment(
            order.id, order.merchantUid, order.total, selectedBrand, shouldApprove
        ) { result ->
            cardPaymentInProgress = false
            result.fold(
                onSuccess = { message ->
                    OmniTracker.track(
                        "orders",
                        if (shouldApprove) "DEMO_CARD_PAYMENT_APPROVED" else "DEMO_CARD_PAYMENT_DECLINED",
                        mapOf("operationId" to order.id, "cardBrand" to selectedBrand, "success" to shouldApprove)
                    )
                    if (shouldApprove) {
                        orders = orders.map { current ->
                            if (current.id == order.id) current.copy(
                                paymentStatus = "PAGADA · DEMO",
                                paymentMethod = "TARJETA_DEMO"
                            ) else current
                        }
                    }
                    selectedCardOrder = null
                    feedback = "$message. No se ingresaron datos reales ni se movió dinero."
                },
                onFailure = { error ->
                    feedback = error.localizedMessage ?: "No se pudo simular el pago con tarjeta."
                }
            )
        }
    }

    fun advanceOrder(order: OmniStoreOrder, next: String, rejectionReason: String = "") {
        if (!merchantMode || !merchantReady || uid.isBlank() ||
            order.storeId != store.id || order.merchantUid != uid ||
            order.environment != "DEMO") {
            feedback = "No tenés autorización para gestionar este pedido desde este comercio."
            return
        }
        if (next !in listOf("ACEPTADO", "EN_PREPARACION", "LISTO", "EN_CAMINO", "ENTREGADO", "RECHAZADO")) {
            feedback = "La transición solicitada no está permitida."
            return
        }
        if (next == "RECHAZADO" && rejectionReason.trim().length !in 10..240) {
            feedback = "Indicá un motivo de rechazo de entre 10 y 240 caracteres."
            return
        }
        if (next in listOf("RECHAZADO", "CANCELADO") && order.paymentStatus != "PENDIENTE") {
            feedback = "No se puede rechazar ni cancelar un pedido ya pagado sin un circuito de devolución DEMO."
            return
        }
        FirebaseFunctions.getInstance("us-central1")
            .getHttpsCallable("updateDemoStoreOrderStatus")
            .call(mapOf(
                "orderId" to order.id,
                "nextStatus" to next,
                "rejectionReason" to rejectionReason.trim()
            ))
            .addOnSuccessListener {
                UsageAnalyticsRepository.trackEvent(uid, "orders", "ORDER_STATUS_$next", mapOf("operationId" to order.id, "stage" to "STATUS_UPDATE_CONFIRMED", "success" to true))
                orders = orders.map { current -> if (current.id == order.id) current.copy(status = next, rejectionReason = rejectionReason.trim()) else current }
                if (selectedScannedOrder?.id == order.id) selectedScannedOrder = selectedScannedOrder?.copy(status = next, rejectionReason = rejectionReason.trim())
                feedback = "Pedido actualizado: $next."
            }
            .addOnFailureListener { feedback = it.localizedMessage ?: "No se pudo actualizar el estado." }
    }

    fun refundPaidOrder(order: OmniStoreOrder, reason: String) {
        if (!merchantMode || !merchantReady || uid.isBlank() || order.storeId != store.id ||
            order.merchantUid != uid || order.environment != "DEMO" ||
            order.paymentStatus != "PAGADA · DEMO" || reason.trim().length !in 10..240) {
            feedback = "La devolución no cumple las condiciones del comercio DEMO."
            return
        }
        refundInProgress = true
        FirebaseFunctions.getInstance("us-central1").getHttpsCallable("refundDemoStoreOrder")
            .call(mapOf("orderId" to order.id, "reason" to reason.trim()))
            .addOnSuccessListener { result ->
                refundInProgress = false
                val data = result.data as? Map<*, *>
                val paymentMethod = data?.get("paymentMethod") as? String ?: order.paymentMethod
                orders = orders.map { current ->
                    if (current.id == order.id) current.copy(status = "CANCELADO", paymentStatus = "REEMBOLSADA · DEMO") else current
                }
                if (selectedScannedOrder?.id == order.id) selectedScannedOrder = selectedScannedOrder?.copy(status = "CANCELADO", paymentStatus = "REEMBOLSADA · DEMO")
                UsageAnalyticsRepository.trackEvent(uid, "orders", "ORDER_REFUNDED", mapOf("operationId" to order.id, "stage" to "REFUND_CONFIRMED", "paymentMethod" to paymentMethod, "success" to true))
                selectedRefundOrder = null
                feedback = if (paymentMethod == "TARJETA_DEMO") "Se anuló la simulación de tarjeta. No hubo movimientos de billetera." else "Devolución DEMO confirmada. Se revirtieron el pago y el cashback en forma atómica."
            }
            .addOnFailureListener { error ->
                refundInProgress = false
                feedback = error.localizedMessage ?: "No se pudo completar la devolución DEMO."
            }
    }

    fun cancelCustomerOrder(order: OmniStoreOrder) {
        if (order.customerUid != uid || order.status != "RECIBIDO" || order.paymentStatus != "PENDIENTE") {
            feedback = "Solo podés cancelar un pedido propio, pendiente de pago y todavía no aceptado."
            return
        }
        FirebaseFunctions.getInstance("us-central1")
            .getHttpsCallable("updateDemoStoreOrderStatus")
            .call(mapOf("orderId" to order.id, "nextStatus" to "CANCELADO"))
            .addOnSuccessListener {
                UsageAnalyticsRepository.trackEvent(uid, "orders", "ORDER_CANCELLED_BY_CUSTOMER", mapOf("operationId" to order.id, "stage" to "CUSTOMER_CANCELLATION_CONFIRMED", "success" to true))
                orders = orders.map { current -> if (current.id == order.id) current.copy(status = "CANCELADO") else current }
                if (selectedScannedOrder?.id == order.id) selectedScannedOrder = selectedScannedOrder?.copy(status = "CANCELADO")
                feedback = "El pedido fue cancelado."
            }
            .addOnFailureListener { feedback = it.localizedMessage ?: "No se pudo cancelar el pedido." }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF070A14)).verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Volver", tint = Color.White) }
            Column(Modifier.weight(1f)) {
                Text(if (merchantMode) "PANEL DE PEDIDOS" else "PEDIDOS OMNI", color = Color(0xFF57DDF5), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                Text(if (merchantMode) store.name else if (demoMode && demoStores.isEmpty()) "Mercado DEMO" else "Pedido QR · ${store.name}", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }

        if (merchantMode) {
            Text("Acceso rápido para leer el QR del cliente, revisar el pedido y gestionar cobro DEMO, preparación y entrega.", color = Color(0xFF9AAAC2), fontSize = 11.sp)
            if (merchantReady && merchantError == null) {
                OrderQRScanner(
                    onOrderScanned = { handleScannedOrder(it) },
                    onLegacyWalletQr = { feedback = "Este es un QR de cuenta, no un QR de pedido." },
                    onDemoOrderScanned = { feedback = "QR DEMO local detectado. Es una muestra de compra: no existe un pedido compartido ni permite cobrar o entregar." },
                    onError = { feedback = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    buttonLabel = "Escanear QR · Pedidos y cobros",
                    buttonColor = Color(0xFF087EAB)
                )
            }
            if (requiresClaimCode && !merchantReady && merchantError == null) {
                Text("Este comercio DEMO requiere un código de titularidad de un solo uso.", color = Color(0xFFFFC978), fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
                OutlinedTextField(
                    value = merchantClaimCode,
                    onValueChange = { merchantClaimCode = it.uppercase().filter { ch -> ch.isLetterOrDigit() || ch == '-' }.take(21) },
                    label = { Text("Código de titularidad") },
                    singleLine = true,
                    enabled = !claimingMerchant,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        if (merchantClaimCode.isBlank() || claimingMerchant) return@Button
                        claimingMerchant = true
                        FirebaseFunctions.getInstance("us-central1")
                            .getHttpsCallable("claimDemoMerchant")
                            .call(mapOf("code" to merchantClaimCode, "storeId" to store.id))
                            .addOnSuccessListener { result ->
                                val data = result.data as? Map<*, *>
                                if (data?.get("storeId") == store.id) {
                                    merchantReady = true
                                    requiresClaimCode = false
                                    merchantClaimCode = ""
                                    merchantError = null
                                } else {
                                    merchantError = "El código corresponde a otro comercio. Verificá el local seleccionado."
                                }
                                claimingMerchant = false
                            }
                            .addOnFailureListener {
                                merchantError = it.localizedMessage ?: "No se pudo validar el código de titularidad."
                                claimingMerchant = false
                            }
                    },
                    enabled = merchantClaimCode.length == 21 && !claimingMerchant,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (claimingMerchant) "Validando código…" else "Vincular este comercio") }
            }
            if (merchantError != null) Text(merchantError.orEmpty(), color = Color(0xFFFF9B8F), fontSize = 12.sp, modifier = Modifier.padding(8.dp))
            else if (!merchantReady && !requiresClaimCode) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color(0xFF57DDF5)) }
        } else {
            Text(if (demoMode) "Elegí un comercio de muestra, seleccioná productos y generá un QR DEMO." else "Elegí un comercio, seleccioná sus productos y enviá tu pedido. El QR identifica el pedido; no acredita un pago.", color = Color(0xFF9AAAC2), fontSize = 11.sp)
            Text(if (demoMode) "CATÁLOGO DEMO INDEPENDIENTE · No requiere aceptación del comercio ni crea movimientos reales." else "CATÁLOGO DE PRUEBA · Los precios y la disponibilidad deben confirmarse con el comercio. OMNI Pay no procesa dinero real en este flujo.", color = Color(0xFFFFC978), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 5.dp))
            if (demoMode) Text("COMERCIOS DE MUESTRA", color = Color(0xFFB69CFF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                items(availableStores.filter { it.id != managedStore.id }, key = { it.id }) { candidate ->
                    FilterChip(
                        selected = candidate.id == store.id,
                        onClick = { if (candidate.id != store.id) viewModel.selectStoreForOrders(candidate) },
                        label = { Text(candidate.name.substringBefore(" · ").take(22), maxLines = 1) }
                    )
                }
            }
            if (demoMode && demoStores.isEmpty()) {
                Text(
                    if (demoStoresLoading) "Cargando comercios y catálogos DEMO…" else "Todavía no hay comercios DEMO provisionados. No se mostrarán comercios reales en este espacio.",
                    color = Color(0xFFFFC978), fontSize = 12.sp
                )
            } else if (demoMode) {
                Text(listOfNotNull(store.demoSizeTier, "${store.inventory.count { it.inStock }} productos activos", store.demoCapacityLabel).joinToString(" · "), color = Color(0xFF9AAAC2), fontSize = 10.sp)
            }
            if (demoMode && demoStores.isEmpty()) {
                // No se utiliza el catálogo real como respaldo del mercado de prueba.
            } else if (store.inventory.none { it.inStock }) Text("Este comercio todavía no publicó productos para pedidos.", color = Color(0xFFFFC978), fontSize = 12.sp)
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
                            if (store.pickupEnabled) {
                                FilterChip(selected = fulfillmentType == "RETIRO", onClick = { fulfillmentType = "RETIRO" }, label = { Text("Retirar") })
                            }
                            if (store.deliveryEnabled) {
                                FilterChip(selected = fulfillmentType == "DELIVERY", onClick = { fulfillmentType = "DELIVERY" }, label = { Text("Delivery") })
                            }
                        }
                        if (fulfillmentType == "DELIVERY") {
                            OutlinedTextField(deliveryAddress, { deliveryAddress = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Dirección de entrega") }, singleLine = true)
                            Button(onClick = {
                                if (RealLocationHelper.hasLocationPermission(context)) {
                                    screenScope.launch {
                                        locatingDelivery = true
                                        val location = RealLocationHelper.getCurrentLocation(context)
                                        deliveryLatitude = location?.latitude
                                        deliveryLongitude = location?.longitude
                                        locatingDelivery = false
                                        feedback = if (location != null) "Ubicación GPS lista para validar la cobertura." else "No se pudo obtener la ubicación. Activá el GPS e intentá nuevamente."
                                    }
                                } else deliveryLocationPermission.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
                            }, enabled = !locatingDelivery, modifier = Modifier.fillMaxWidth()) {
                                Text(if (locatingDelivery) "Obteniendo ubicación…" else if (deliveryLatitude != null) "Ubicación GPS confirmada ✓" else "Compartir ubicación para verificar cobertura")
                            }
                            Text("Compartí la ubicación desde el domicilio de entrega. Se usa solo para validar este pedido; no se publica en el mapa ni en tu perfil.", color = Color(0xFF9AAAC2), fontSize = 10.sp)
                        }
                        if (fulfillmentType == "DELIVERY" && store.deliveryEnabled) {
                            Text(
                                if (deliveryFee == 0.0) "Envío: GRATIS" else "Envío: OM$ ${deliveryFee.toInt()}",
                                color = Color(0xFF9AAAC2), fontSize = 11.sp
                            )
                            Text("Preparación estimada: ${store.estimatedPrepMinutes} min · Cobertura configurada: ${store.deliveryRadiusKm} km", color = Color(0xFF9AAAC2), fontSize = 10.sp)
                        }
                        if (store.minimumOrderAmount > 0) {
                            Text("Compra mínima del comercio: OM$ ${store.minimumOrderAmount.toInt()}", color = Color(0xFF9AAAC2), fontSize = 11.sp)
                        }
                        Text("Podés seguir agregando productos. El QR se genera únicamente cuando confirmás el pedido.", color = Color(0xFF9AAAC2), fontSize = 10.sp)
                        Button(
                            onClick = {
                                loadingCashbackReview = true
                                db.collection("merchantProfiles").document(store.id).get()
                                    .addOnSuccessListener { merchant ->
                                        loadingCashbackReview = false
                                        if (!merchant.exists() || merchant.getString("environment") != "DEMO") {
                                            feedback = "Este comercio no tiene un perfil DEMO habilitado para pedidos."
                                        } else {
                                            reviewCashbackTerms = decodeCashbackTerms(merchant.get("cashback"))
                                            showOrderReview = true
                                        }
                                    }
                                    .addOnFailureListener {
                                        loadingCashbackReview = false
                                        feedback = "No se pudieron actualizar las condiciones del comercio. Intentá nuevamente."
                                    }
                            },
                            enabled = !sending && !loadingCashbackReview,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF087EAB))
                        ) {
                            Text(if (loadingCashbackReview) "Actualizando condiciones…" else "Revisar carrito")
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
                            onReject = {
                                selectedScannedOrder = order
                                rejectReasonInput = ""
                                showRejectReasonDialog = true
                            },
                            onRefund = {
                                selectedRefundOrder = order
                                refundReasonInput = ""
                            }
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
                            onPay = { payOrderDemo(order) },
                            onPayWithDemoCard = if (order.environment == "DEMO") ({ selectedCardOrder = order }) else null,
                            paying = payingOrderId == order.id
                        )
                    }
                }
            }
        }
    }

    selectedCardOrder?.let { order ->
        AlertDialog(
            onDismissRequest = { if (!cardPaymentInProgress) selectedCardOrder = null },
            title = { Text("Pasarela de tarjeta · DEMO") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("Pedido: ${order.id.take(9)} · OM$ ${String.format("%,.2f", order.total)}", fontWeight = FontWeight.Bold)
                    Text("Elegí una tarjeta ficticia y el resultado que querés probar. No ingreses números, vencimiento ni CVV reales.", fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = demoCardBrand == "VISA", onClick = { demoCardBrand = "VISA" }, enabled = !cardPaymentInProgress, label = { Text("Visa DEMO") })
                        FilterChip(selected = demoCardBrand == "MASTERCARD", onClick = { demoCardBrand = "MASTERCARD" }, enabled = !cardPaymentInProgress, label = { Text("Mastercard DEMO") })
                    }
                    Text("Resultado de prueba", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = demoCardApproved, onClick = { demoCardApproved = true }, enabled = !cardPaymentInProgress, label = { Text("Aprobada") })
                        FilterChip(selected = !demoCardApproved, onClick = { demoCardApproved = false }, enabled = !cardPaymentInProgress, label = { Text("Rechazada") })
                    }
                    Text("La simulación solo registra una autorización ficticia. No cobra ni acredita fondos.", color = Color(0xFFFFC978), fontSize = 10.sp)
                }
            },
            confirmButton = {
                Button(
                    enabled = !cardPaymentInProgress,
                    onClick = { payOrderWithDemoCard(order) }
                ) { Text(if (cardPaymentInProgress) "Simulando…" else "Ejecutar simulación") }
            },
            dismissButton = {
                TextButton(onClick = { if (!cardPaymentInProgress) selectedCardOrder = null }, enabled = !cardPaymentInProgress) {
                    Text("Cancelar")
                }
            }
        )
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
                    if (scanned.paymentStatus == "PENDIENTE") Text("El cliente puede pagar desde Mis pedidos con su OMNI Pay DEMO, o abonar al retirar/recibir.", color = Color(0xFFFFC978), fontSize = 10.sp)
                }
            },
            confirmButton = {
                val next = when (scanned.status) {
                    "RECIBIDO" -> "ACEPTADO"
                    "ACEPTADO" -> "EN_PREPARACION"
                    "EN_PREPARACION" -> "LISTO"
                    "LISTO" -> if (scanned.fulfillmentType == "DELIVERY") "EN_CAMINO" else "ENTREGADO"
                    "EN_CAMINO" -> "ENTREGADO"
                    else -> ""
                }
                if (next.isNotBlank()) Button(onClick = {
                    advanceOrder(scanned, next)
                }) { Text(when(next) {
                    "ACEPTADO" -> "Aceptar pedido"
                    "EN_PREPARACION" -> "Preparar pedido"
                    "LISTO" -> "Marcar listo"
                    "EN_CAMINO" -> "Enviar delivery"
                    else -> "Marcar entregado"
                }) }
                else TextButton(onClick = { selectedScannedOrder = null }) { Text("Cerrar") }
            },
            dismissButton = {
                Row {
                    if (scanned.status == "RECIBIDO") TextButton(onClick = {
                        rejectReasonInput = ""
                        showRejectReasonDialog = true
                    }) { Text("Rechazar") }
                    TextButton(onClick = { selectedScannedOrder = null }) { Text("Cerrar") }
                }
            }
        )
    }

    selectedRefundOrder?.let { order ->
        AlertDialog(
            onDismissRequest = { if (!refundInProgress) selectedRefundOrder = null },
            containerColor = Color(0xFF101B30),
            title = { Text("Devolución de pedido DEMO", color = Color.White) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Pedido ${order.id.take(9)} · OM$ ${String.format("%,.2f", order.total)}", color = Color(0xFF57DDF5), fontWeight = FontWeight.Bold)
                    Text(if (order.paymentMethod == "TARJETA_DEMO") "Se anulará el registro de tarjeta simulado; no hubo movimiento de billeteras." else "Se devolverá el pago al comprador y se revertirá el cashback de este pedido. El proceso requiere saldo suficiente en el comercio y que el cashback siga disponible en la billetera del comprador.", color = Color(0xFFB8C8D9), fontSize = 11.sp)
                    OutlinedTextField(value = refundReasonInput, onValueChange = { refundReasonInput = it.take(240) }, label = { Text("Motivo (10 a 240 caracteres)") }, minLines = 3, maxLines = 5, enabled = !refundInProgress)
                    Text("${refundReasonInput.trim().length}/240", color = Color(0xFF9AAAC2), fontSize = 10.sp)
                }
            },
            confirmButton = {
                Button(enabled = !refundInProgress && refundReasonInput.trim().length in 10..240,
                    onClick = { refundPaidOrder(order, refundReasonInput) }) {
                    Text(if (refundInProgress) "Procesando devolución…" else "Confirmar devolución")
                }
            },
            dismissButton = { TextButton(enabled = !refundInProgress, onClick = { selectedRefundOrder = null }) { Text("Volver") } }
        )
    }

    if (showRejectReasonDialog && selectedScannedOrder != null) {
        AlertDialog(
            onDismissRequest = { showRejectReasonDialog = false },
            containerColor = Color(0xFF101B30),
            title = { Text("Motivo del rechazo", color = Color.White) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("El cliente verá el motivo. No se puede rechazar un pedido ya pagado.", color = Color(0xFFB8C8D9), fontSize = 12.sp)
                    OutlinedTextField(
                        value = rejectReasonInput,
                        onValueChange = { rejectReasonInput = it.take(240) },
                        label = { Text("Motivo (10 a 240 caracteres)") },
                        minLines = 3,
                        maxLines = 5
                    )
                    Text("${rejectReasonInput.trim().length}/240", color = Color(0xFF9AAAC2), fontSize = 10.sp)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = rejectReasonInput.trim().length in 10..240,
                    onClick = {
                        val order = selectedScannedOrder ?: return@TextButton
                        showRejectReasonDialog = false
                        advanceOrder(order, "RECHAZADO", rejectReasonInput)
                        selectedScannedOrder = null
                    }
                ) { Text("Confirmar rechazo") }
            },
            dismissButton = {
                TextButton(onClick = { showRejectReasonDialog = false }) { Text("Volver") }
            }
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
                    if (reviewCashbackTerms.enabled) {
                        Text(
                            "Política vigente del comercio: ${reviewCashbackTerms.percentage}% de cashback configurado" +
                                (reviewCashbackTerms.capPerPurchase?.let { " · Tope por compra OM$ ${String.format("%,.2f", it)}" } ?: "") +
                                (reviewCashbackTerms.monthlyCap?.let { " · Tope mensual OM$ ${String.format("%,.2f", it)}" } ?: ""),
                            color = Color(0xFF65E6A5), fontSize = 10.sp
                        )
                        Text("El crédito automático todavía no está habilitado; esta compra no recibirá un reintegro en saldo.", color = Color(0xFFFFC978), fontSize = 10.sp)
                    } else {
                        Text("Este comercio no tiene cashback activo para este pedido.", color = Color(0xFFB8C8D9), fontSize = 10.sp)
                    }
                    Text(if (fulfillmentType == "DELIVERY") "Entrega: ${deliveryAddress.trim()}" else "Modalidad: Retiro en el comercio", color = Color(0xFFB8C8D9), fontSize = 11.sp)
                    Text(if (demoMode) "La compra es ilustrativa: se genera un QR DEMO local, sin pedido compartido ni pago." else "Pago pendiente. Se abona al retirar o recibir; el QR solo identifica el pedido.", color = Color(0xFFFFC978), fontSize = 10.sp)
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
                    Text(if (order.isLocalDemo) "QR ilustrativo de la compra. No se envía al comercio ni genera un pedido compartido." else "Mostrá este QR en el comercio para identificar tu pedido.", color = Color(0xFFB8C8D9), fontSize = 11.sp)
                    bitmap?.let { qrBitmap ->
                        Image(qrBitmap.asImageBitmap(), "QR del pedido", modifier = Modifier.size(190.dp))
                        OutlinedButton(onClick = {
                            runCatching {
                                val folder = File(context.cacheDir, "shared_qr").apply { mkdirs() }
                                val qrFile = File(folder, if (order.isLocalDemo) "omni-pedido-demo-${order.id.take(10)}.png" else "omni-pedido-${order.id.take(10)}.png")
                                FileOutputStream(qrFile).use { output -> check(qrBitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) }
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", qrFile)
                                UsageAnalyticsRepository.trackCurrentAction("orders", "ORDER_SHARED", mapOf("method" to "share_sheet"))
                                val share = Intent(Intent.ACTION_SEND).apply {
                                    type = "image/png"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    putExtra(Intent.EXTRA_TEXT, if (order.isLocalDemo) "QR DEMO LOCAL OMNI\nNo es un pedido compartido.\nComercio de muestra: ${order.storeName}\nTotal ilustrativo: OM$ ${String.format("%,.2f", order.total)}" else "Pedido OMNI ${order.id}\nComercio: ${order.storeName}\nTotal: OM$ ${String.format("%,.2f", order.total)}\nPago pendiente al retirar o recibir.")
                                    clipData = ClipData.newUri(context.contentResolver, "QR Pedido OMNI", uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(share, if (order.isLocalDemo) "Compartir QR DEMO" else "Compartir QR del pedido"))
                            }.onFailure { feedback = it.localizedMessage ?: "No se pudo compartir el QR." }
                        }) { Text("Compartir QR") }
                    } ?: Text("No se pudo generar la imagen QR.", color = Color(0xFFFF9B8F), fontSize = 10.sp)
                    Text("${if (order.isLocalDemo) "Código DEMO" else "Pedido"} ${order.id.take(10)} · ${order.status}", color = Color(0xFF57DDF5), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    Text(if (order.isLocalDemo) "Solo demostración: no genera retiro, cobro, pago ni registro comercial." else "Pago pendiente · se abona al retirar o recibir. Este QR no es un comprobante de pago.", color = Color(0xFFFFC978), fontSize = 10.sp)
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
    onPayWithDemoCard: (() -> Unit)? = null,
    paying: Boolean = false,
    onReject: (() -> Unit)? = null,
    onRefund: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var history by remember(order.id) { mutableStateOf<List<OrderStatusEvent>>(emptyList()) }
    var historyExpanded by remember(order.id) { mutableStateOf(false) }
    DisposableEffect(order.id) {
        val listener = FirebaseFirestore.getInstance()
            .collection("storeOrders").document(order.id)
            .collection("statusHistory").orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(8)
            .addSnapshotListener { snapshot, _ ->
                history = snapshot?.documents.orEmpty().map { doc ->
                    OrderStatusEvent(
                        fromStatus = doc.getString("fromStatus").orEmpty(),
                        toStatus = doc.getString("toStatus").orEmpty(),
                        actorRole = doc.getString("actorRole").orEmpty(),
                        reason = doc.getString("reason").orEmpty(),
                        createdAt = doc.getTimestamp("createdAt")
                    )
                }
            }
        onDispose { listener.remove() }
    }
    val next = when (order.status) {
        "RECIBIDO" -> "ACEPTADO"
        "ACEPTADO" -> "EN_PREPARACION"
        "EN_PREPARACION" -> "LISTO"
        "LISTO" -> if (order.fulfillmentType == "DELIVERY") "EN_CAMINO" else "ENTREGADO"
        "EN_CAMINO" -> "ENTREGADO"
        else -> ""
    }
    val label = when (order.status) {
        "RECIBIDO" -> "Recibido"
        "ACEPTADO" -> "Aceptado"
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
            Text(if (order.fulfillmentType == "DELIVERY") "Delivery · ${order.deliveryAddress}" else "Retiro en el comercio", color = Color(0xFFB8C8D9), fontSize = 9.sp)
            if (order.status == "RECHAZADO" && order.rejectionReason.isNotBlank()) {
                Text("Motivo del comercio: ${order.rejectionReason}", color = Color(0xFFFFC978), fontSize = 10.sp)
            }
            TextButton(onClick = { historyExpanded = !historyExpanded }) {
                Text(if (historyExpanded) "Ocultar trazabilidad" else "Ver trazabilidad del pedido", color = Color(0xFF57DDF5), fontSize = 10.sp)
            }
            if (historyExpanded) {
                if (history.isEmpty()) {
                    Text("Todavía no hay cambios de estado registrados.", color = Color(0xFF9AAAC2), fontSize = 9.sp)
                } else {
                    history.forEach { event ->
                        val time = event.createdAt?.toDate()?.let {
                            SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(it)
                        } ?: "Hora pendiente"
                        Text(
                            "${event.toStatus.replace("_", " ")} · ${if (event.actorRole == "COMERCIO") "Comercio" else "Comprador"} · $time",
                            color = Color(0xFFB8C8D9), fontSize = 9.sp
                        )
                        if (event.reason.isNotBlank()) Text("Motivo: ${event.reason}", color = Color(0xFFFFC978), fontSize = 9.sp)
                    }
                }
            }
            if (order.cashbackPolicy.enabled) {
                Text(
                    "Condiciones registradas al crear el pedido: ${order.cashbackPolicy.percentage}% de cashback" +
                        (order.cashbackPolicy.capPerPurchase?.let { " · Tope por compra OM$ ${String.format("%,.2f", it)}" } ?: "") +
                        (order.cashbackPolicy.monthlyCap?.let { " · Tope mensual OM$ ${String.format("%,.2f", it)}" } ?: ""),
                    color = Color(0xFF65E6A5), fontSize = 9.sp
                )
                Text("No acreditable todavía: circuito DEMO de reintegro pendiente.", color = Color(0xFFFFC978), fontSize = 9.sp)
            }
            Text(
                when {
                    order.paymentStatus == "REEMBOLSADA · DEMO" -> "Pago: devuelto · ${if (order.paymentMethod == "TARJETA_DEMO") "Tarjeta DEMO" else "OMNI Pay DEMO"}"
                    order.paymentStatus.startsWith("PAGADA") -> "Pago: acreditado · ${if (order.paymentMethod == "TARJETA_DEMO") "Tarjeta DEMO" else "OMNI Pay DEMO"}"
                    else -> "Pago: pendiente · OMNI Pay DEMO, tarjeta DEMO o al retirar/recibir"
                },
                color = if (order.paymentStatus == "REEMBOLSADA · DEMO") Color(0xFF57DDF5) else if (order.paymentStatus.startsWith("PAGADA")) Color(0xFF65E6A5) else Color(0xFFFFC978),
                fontSize = 9.sp
            )
            if (merchantMode && order.paymentStatus == "PAGADA · DEMO" && order.status in listOf("RECIBIDO", "ACEPTADO", "EN_PREPARACION", "LISTO", "EN_CAMINO") && onRefund != null) {
                OutlinedButton(onClick = onRefund, modifier = Modifier.fillMaxWidth()) { Text("Resolver devolución DEMO", fontSize = 10.sp) }
            }
            if (!merchantMode && onPay != null && order.paymentStatus == "PENDIENTE" &&
                order.status !in listOf("ENTREGADO", "RECHAZADO", "CANCELADO")) {
                if (order.total <= 50000.0) {
                    Button(onClick = onPay, enabled = !paying, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF087EAB))) {
                        Text(if (paying) "Procesando pago DEMO…" else "Pagar con OMNI Pay DEMO", fontSize = 10.sp)
                    }
                } else {
                    Text("El total supera el límite de pagos DEMO (OM$ 50.000). Podés abonar al retirar o recibir.", color = Color(0xFFFFC978), fontSize = 9.sp)
                }
                if (onPayWithDemoCard != null && order.total <= 50000.0) {
                    OutlinedButton(onClick = onPayWithDemoCard, enabled = !paying, modifier = Modifier.fillMaxWidth()) {
                        Text("Simular pago con tarjeta DEMO", fontSize = 10.sp)
                    }
                }
            }
            if (merchantMode && order.customerPhone.isNotBlank()) {
                TextButton(onClick = {
                    val phoneNumber = order.customerPhone.filter { it.isDigit() }
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$phoneNumber"))) }
                }) { Text("Contactar cliente por WhatsApp", color = Color(0xFF57DDF5), fontSize = 10.sp) }
            }
            if (!merchantMode && order.status == "RECIBIDO") {
                OutlinedButton(onClick = { onAdvance("CANCELADO") }, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancelar pedido", fontSize = 10.sp)
                }
            }
            if (merchantMode && next.isNotBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Button(onClick = { onAdvance(next) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF087EAB))) {
                        Text(when (next) {
                            "ACEPTADO" -> "Aceptar pedido"
                            "EN_PREPARACION" -> "Comenzar preparación"
                            "LISTO" -> "Marcar listo"
                            "EN_CAMINO" -> "Enviar delivery"
                            else -> "Marcar entregado"
                        }, fontSize = 10.sp)
                    }
                    if (order.status == "RECIBIDO" && onReject != null) {
                        OutlinedButton(onClick = onReject) {
                            Text("Rechazar", fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}
