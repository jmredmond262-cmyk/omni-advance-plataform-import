package com.example.data.merchant

import com.example.model.ComboDeal
import com.example.model.ProductCategory
import com.example.model.Store
import com.example.model.StoreNews
import com.example.model.StoreProduct
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Persists the merchant-owned part of a Store in the existing merchantProfiles document.
 * Product photo URIs are device-local until OMNI has an approved free image-hosting strategy.
 */
object MerchantProfileRepository {
    private val db = FirebaseFirestore.getInstance()

    fun loadOrClaim(store: Store, uid: String, callback: (Result<Pair<Store, List<StoreNews>>>) -> Unit) {
        val ref = db.collection("merchantProfiles").document(store.id)
        ref.get()
            .addOnSuccessListener { snapshot ->
                if (snapshot.exists()) {
                    val ownerUid = snapshot.getString("ownerUid").orEmpty()
                    if (ownerUid.isBlank() && snapshot.getString("environment") == "DEMO") {
                        callback(Result.failure(IllegalStateException("Este comercio DEMO requiere un código de titularidad de un solo uso.")))
                    } else if (ownerUid != uid) {
                        callback(Result.failure(IllegalStateException("Este comercio ya está vinculado a otra cuenta OMNI.")))
                    } else if (snapshot.getString("environment") == "REAL") {
                        callback(Result.failure(IllegalStateException("Este perfil pertenece al directorio de comercios reales y no puede convertirse en comercio DEMO.")))
                    } else if (snapshot.getString("environment") == null) {
                        ref.update(
                            "environment", "DEMO",
                            "cashback", mapOf(
                                "enabled" to false,
                                "percentage" to 0.0,
                                "capPerPurchase" to null,
                                "monthlyCap" to null
                            )
                        ).addOnSuccessListener {
                            ref.get().addOnSuccessListener { migrated ->
                                callback(Result.success(decode(migrated, store)))
                            }.addOnFailureListener { callback(Result.failure(it)) }
                        }.addOnFailureListener { callback(Result.failure(it)) }
                    } else if (snapshot.getString("environment") != "DEMO") {
                        callback(Result.failure(IllegalStateException("El entorno de este comercio no está habilitado para pedidos DEMO.")))
                    } else {
                        callback(Result.success(decode(snapshot, store)))
                    }
                } else {
                    callback(Result.failure(IllegalStateException("Este comercio todavía no está habilitado como DEMO. Necesita ser provisionado y asignado mediante un código de titularidad.")))
                }
            }
            .addOnFailureListener { callback(Result.failure(it)) }
    }

    private fun validateDemoCatalog(store: Store, news: List<StoreNews>): String? {
        val productLimit = when (store.demoSizeTier) {
            "MICRO" -> 20
            "PEQUENO" -> 50
            "MEDIANO" -> 100
            "GRANDE" -> 250
            "ESPECIALIZADO" -> 50
            else -> return "El comercio no tiene un tamaño DEMO válido para administrar el catálogo."
        }
        if (store.inventory.size > productLimit) return "Este comercio admite hasta $productLimit productos."
        if (store.comboDeals.size > 30) return "Este comercio admite hasta 30 combos."
        if (news.size > 50) return "Este comercio admite hasta 50 novedades."
        val ids = store.inventory.map { it.id.trim() }
        if (ids.any { it.isBlank() } || ids.toSet().size != ids.size) return "Cada producto debe tener un identificador único."
        store.inventory.forEachIndexed { index, product ->
            val label = "Producto ${index + 1}"
            if (product.name.trim().length !in 2..80) return "$label: el nombre debe tener entre 2 y 80 caracteres."
            if (!product.price.isFinite() || product.price <= 0.0 || product.price > 50000.0) return "$label: el precio debe ser mayor a cero y no superar OM$ 50.000."
            val original = product.originalPrice
            if (original != null && (!original.isFinite() || original <= product.price || original > 100000.0)) return "$label: el precio anterior debe ser válido y mayor al precio actual."
            if (product.unitLabel.trim().isEmpty() || product.unitLabel.length > 24) return "$label: revisá la unidad de venta."
            if (product.barcode != null && product.barcode.length > 64) return "$label: el código de barras supera 64 caracteres."
            if (product.description.length > 500) return "$label: la descripción supera 500 caracteres."
            if (product.stockQuantity !in 0..100000 || product.inStock != (product.stockQuantity > 0)) return "$label: el stock debe ser un entero entre 0 y 100.000 y coincidir con la disponibilidad."
        }
        store.comboDeals.forEachIndexed { index, combo ->
            if (combo.title.trim().length !in 2..80) return "Combo ${index + 1}: el título debe tener entre 2 y 80 caracteres."
            if (combo.description.length > 300 || combo.discountBadge.length > 24 || combo.audience.length > 24) return "Combo ${index + 1}: revisá la descripción, etiqueta o público."
        }
        news.forEachIndexed { index, item ->
            if (item.title.trim().length !in 2..100 || item.content.length > 1000 || item.badge.length > 40) return "Novedad ${index + 1}: revisá el título, contenido o etiqueta."
        }
        return null
    }

    fun save(store: Store, news: List<StoreNews>, uid: String, callback: (Result<Unit>) -> Unit) {
        val validationError = validateDemoCatalog(store, news)
        if (validationError != null) {
            callback(Result.failure(IllegalArgumentException(validationError)))
            return
        }
        if (uid.isBlank() || store.id.isBlank()) {
            callback(Result.failure(IllegalArgumentException("Falta identificar al titular o al comercio DEMO.")))
            return
        }
        val data = mapOf(
            "storeId" to store.id,
            "ownerUid" to uid,
            "storeName" to store.name,
            "environment" to "DEMO",
            "address" to store.address,
            "phone" to store.phone,
            "description" to store.description,
            "isOpen" to store.isOpen,
            "hoursText" to store.hoursText,
            "openTime" to store.openTime,
            "closeTime" to store.closeTime,
            "weekendHoursText" to store.weekendHoursText,
            "is24Hours" to store.is24Hours,
            "isOnDuty" to store.isOnDuty,
            "openOnHolidays" to store.openOnHolidays,
            "scheduleNotes" to store.scheduleNotes,
            "pickupEnabled" to store.pickupEnabled,
            "deliveryEnabled" to store.deliveryEnabled,
            "deliveryRadiusKm" to store.deliveryRadiusKm,
            "deliveryFee" to store.deliveryFee,
            "freeDeliveryFrom" to store.freeDeliveryFrom,
            "minimumOrderAmount" to store.minimumOrderAmount,
            "estimatedPrepMinutes" to store.estimatedPrepMinutes,
            "merchantLatitude" to store.merchantLatitude,
            "merchantLongitude" to store.merchantLongitude,
            "minSpendDiscountThreshold" to store.minSpendDiscountThreshold,
            "minSpendDiscountPercent" to store.minSpendDiscountPercent,
            "specialOfferText" to store.specialOfferText,
            "cashback" to mapOf(
                "enabled" to store.cashbackEnabled,
                "percentage" to store.cashbackPercent,
                "capPerPurchase" to store.cashbackPerPurchaseCap,
                "monthlyCap" to store.cashbackMonthlyCap
            ),
            "comboDeals" to store.comboDeals.map { deal ->
                mapOf(
                    "title" to deal.title,
                    "description" to deal.description,
                    "discountBadge" to deal.discountBadge,
                    "audience" to deal.audience
                )
            },
            "inventory" to store.inventory.map { product ->
                mapOf(
                    "id" to product.id,
                    "name" to product.name,
                    "category" to product.category.name,
                    "price" to product.price,
                    "originalPrice" to product.originalPrice,
                    "inStock" to product.inStock,
                    "stockQuantity" to product.stockQuantity,
                    "unitLabel" to product.unitLabel,
                    "photoUri" to product.photoUri,
                    "barcode" to product.barcode,
                    "description" to product.description
                )
            },
            "news" to news.map { item ->
                mapOf(
                    "id" to item.id,
                    "storeId" to item.storeId,
                    "storeName" to item.storeName,
                    "title" to item.title,
                    "content" to item.content,
                    "timeAgoText" to item.timeAgoText,
                    "badge" to item.badge,
                    "isHot" to item.isHot
                )
            },
            "updatedAt" to FieldValue.serverTimestamp()
        )
        val catalogPayload = mapOf(
            "storeId" to store.id,
            "inventory" to data["inventory"],
            "comboDeals" to data["comboDeals"],
            "news" to data["news"]
        )
        CoroutineScope(Dispatchers.Main).launch {
            runCatching { DemoMerchantApi.saveCatalog(JSONObject(catalogPayload)) }
                .onSuccess {
                    // The Worker validates and writes catalog data atomically. Operational
                    // fields remain a separate owner-authorized Firestore update.
                    val operationalData = data - setOf("inventory", "comboDeals", "news")
                    db.collection("merchantProfiles").document(store.id)
                        .set(operationalData, SetOptions.merge())
                        .addOnSuccessListener { callback(Result.success(Unit)) }
                        .addOnFailureListener { callback(Result.failure(it)) }
                }
                .onFailure { callback(Result.failure(it)) }
        }
    }


    /**
     * Enriches real directory results with the merchant's latest public catalog.
     * It never claims a profile and falls back to the directory data if Firestore is unavailable.
     */

    /** Loads only seeded sandbox merchants. Real directory records are never mixed into this list. */
    fun loadDemoStores(callback: (Result<List<Store>>) -> Unit) {
        db.collection("merchantProfiles")
            .whereEqualTo("environment", "DEMO")
            .get()
            .addOnSuccessListener { snapshot ->
                val stores = snapshot.documents.filter { doc ->
                    (doc.get("seedVersion") as? Number)?.toInt() == 1
                }.mapNotNull { doc ->
                    runCatching {
                        val type = runCatching {
                            com.example.model.StoreType.valueOf(doc.getString("type") ?: "ALMACEN")
                        }.getOrDefault(com.example.model.StoreType.ALMACEN)
                        val base = Store(
                            id = doc.id,
                            name = doc.getString("storeName") ?: return@runCatching null,
                            type = type,
                            rating = (doc.get("rating") as? Number)?.toFloat() ?: 0f,
                            reviewsCount = (doc.get("reviewsCount") as? Number)?.toInt() ?: 0,
                            distanceMeters = (doc.get("distanceMeters") as? Number)?.toInt() ?: 0,
                            address = doc.getString("address") ?: "Dirección ficticia · Entorno DEMO",
                            isOpen = doc.getBoolean("isOpen") ?: true,
                            hoursText = doc.getString("hoursText") ?: "Horario DEMO",
                            accentColorHex = (doc.get("accentColorHex") as? Number)?.toLong() ?: 0xFF57DDF5,
                            photosCount = (doc.get("photosCount") as? Number)?.toInt() ?: 0,
                            hasVideoTour = doc.getBoolean("hasVideoTour") ?: false,
                            videoTourDuration = doc.getString("videoTourDuration") ?: "",
                            description = doc.getString("description") ?: "Comercio ficticio para pruebas OMNI.",
                            specialOfferText = doc.getString("specialOfferText") ?: "",
                            minSpendDiscountThreshold = (doc.get("minSpendDiscountThreshold") as? Number)?.toDouble() ?: 0.0,
                            minSpendDiscountPercent = (doc.get("minSpendDiscountPercent") as? Number)?.toInt() ?: 0,
                            comboDeals = emptyList(),
                            inventory = emptyList(),
                            mapOffsetX = 0.5f,
                            mapOffsetY = 0.5f,
                            openTime = doc.getString("openTime") ?: "08:00",
                            closeTime = doc.getString("closeTime") ?: "20:00",
                            weekendHoursText = doc.getString("weekendHoursText") ?: "Horario DEMO",
                            is24Hours = doc.getBoolean("is24Hours") ?: false,
                            isOnDuty = doc.getBoolean("isOnDuty") ?: false,
                            openOnHolidays = doc.getBoolean("openOnHolidays") ?: false,
                            scheduleNotes = doc.getString("scheduleNotes") ?: "",
                            phone = doc.getString("phone") ?: "",
                            demoSizeTier = doc.getString("sizeTier"),
                            demoCapacityLabel = doc.getString("capacityLabel")
                        )
                        val decoded = decode(doc, base).first
                        decoded.copy(
                            demoSizeTier = doc.getString("sizeTier"),
                            demoCapacityLabel = doc.getString("capacityLabel")
                        )
                    }.getOrNull()
                }.sortedWith(compareBy<Store> { when (it.demoSizeTier) { "MICRO" -> 0; "PEQUENO" -> 1; "MEDIANO" -> 2; "GRANDE" -> 3; else -> 4 } }.thenBy { it.name })
                callback(Result.success(stores))
            }
            .addOnFailureListener { callback(Result.failure(it)) }
    }

    fun loadPublicCatalogs(stores: List<Store>, callback: (List<Store>) -> Unit) {
        if (stores.isEmpty()) {
            callback(emptyList())
            return
        }
        db.collection("merchantProfiles").get()
            .addOnSuccessListener { snapshot ->
                // Never attach sandbox inventory/offers to real directory listings.
                // Only profiles explicitly marked REAL may enrich the real directory.
                val profiles = snapshot.documents.filter { it.getString("environment") == "REAL" }
                val enriched = stores.map { base ->
                    val profile = profiles.firstOrNull { it.id == base.id }
                        ?: profiles.firstOrNull { document ->
                            val nameMatches = document.getString("storeName").orEmpty()
                                .equals(base.name, ignoreCase = true)
                            val profileAddress = document.getString("address").orEmpty()
                            val addressMatches = profileAddress.isNotBlank() &&
                                (base.address.contains(profileAddress, ignoreCase = true) ||
                                    profileAddress.contains(base.address, ignoreCase = true))
                            nameMatches && addressMatches
                        }
                    if (profile != null) decode(profile, base).first else base
                }
                callback(enriched)
            }
            .addOnFailureListener { callback(stores) }
    }

    private fun decode(snapshot: DocumentSnapshot, base: Store): Pair<Store, List<StoreNews>> {
        val combos = (snapshot.get("comboDeals") as? List<*>)?.mapNotNull { raw ->
            val map = raw as? Map<*, *> ?: return@mapNotNull null
            ComboDeal(
                title = map["title"] as? String ?: return@mapNotNull null,
                description = map["description"] as? String ?: "",
                discountBadge = map["discountBadge"] as? String ?: "",
                audience = map["audience"] as? String ?: "TODOS"
            )
        } ?: base.comboDeals

        val isDemoInventory = snapshot.getString("environment") == "DEMO"
        val products = (snapshot.get("inventory") as? List<*>)?.mapNotNull { raw ->
            val map = raw as? Map<*, *> ?: return@mapNotNull null
            val categoryName = map["category"] as? String ?: ProductCategory.ALMACEN.name
            val category = runCatching { ProductCategory.valueOf(categoryName) }.getOrDefault(ProductCategory.ALMACEN)
            StoreProduct(
                id = map["id"] as? String ?: return@mapNotNull null,
                name = map["name"] as? String ?: return@mapNotNull null,
                category = category,
                price = (map["price"] as? Number)?.toDouble() ?: 0.0,
                originalPrice = (map["originalPrice"] as? Number)?.toDouble(),
                inStock = if (isDemoInventory && map["stockQuantity"] is Number) {
                    ((map["stockQuantity"] as? Number)?.toInt() ?: 0) > 0
                } else map["inStock"] as? Boolean ?: true,
                stockQuantity = (map["stockQuantity"] as? Number)?.toInt() ?: 0,
                unitLabel = map["unitLabel"] as? String ?: "unid.",
                photoUri = map["photoUri"] as? String,
                barcode = map["barcode"] as? String,
                description = map["description"] as? String ?: ""
            )
        } ?: base.inventory

        val updated = base.copy(
            address = snapshot.getString("address") ?: base.address,
            phone = snapshot.getString("phone") ?: base.phone,
            description = snapshot.getString("description") ?: base.description,
            isOpen = snapshot.getBoolean("isOpen") ?: base.isOpen,
            hoursText = snapshot.getString("hoursText") ?: base.hoursText,
            openTime = snapshot.getString("openTime") ?: base.openTime,
            closeTime = snapshot.getString("closeTime") ?: base.closeTime,
            weekendHoursText = snapshot.getString("weekendHoursText") ?: base.weekendHoursText,
            is24Hours = snapshot.getBoolean("is24Hours") ?: base.is24Hours,
            isOnDuty = snapshot.getBoolean("isOnDuty") ?: base.isOnDuty,
            openOnHolidays = snapshot.getBoolean("openOnHolidays") ?: base.openOnHolidays,
            scheduleNotes = snapshot.getString("scheduleNotes") ?: base.scheduleNotes,
            pickupEnabled = snapshot.getBoolean("pickupEnabled") ?: base.pickupEnabled,
            deliveryEnabled = snapshot.getBoolean("deliveryEnabled") ?: base.deliveryEnabled,
            deliveryRadiusKm = snapshot.getDouble("deliveryRadiusKm") ?: base.deliveryRadiusKm,
            deliveryFee = snapshot.getDouble("deliveryFee") ?: base.deliveryFee,
            freeDeliveryFrom = snapshot.getDouble("freeDeliveryFrom"),
            minimumOrderAmount = snapshot.getDouble("minimumOrderAmount") ?: base.minimumOrderAmount,
            estimatedPrepMinutes = (snapshot.getLong("estimatedPrepMinutes") ?: base.estimatedPrepMinutes.toLong()).toInt(),
            merchantLatitude = snapshot.getDouble("merchantLatitude"),
            merchantLongitude = snapshot.getDouble("merchantLongitude"),
            minSpendDiscountThreshold = snapshot.getDouble("minSpendDiscountThreshold") ?: base.minSpendDiscountThreshold,
            minSpendDiscountPercent = (snapshot.getLong("minSpendDiscountPercent") ?: base.minSpendDiscountPercent.toLong()).toInt(),
            specialOfferText = snapshot.getString("specialOfferText") ?: base.specialOfferText,
            cashbackEnabled = (snapshot.get("cashback") as? Map<*, *>)?.get("enabled") as? Boolean ?: false,
            cashbackPercent = ((snapshot.get("cashback") as? Map<*, *>)?.get("percentage") as? Number)?.toDouble() ?: 0.0,
            cashbackPerPurchaseCap = ((snapshot.get("cashback") as? Map<*, *>)?.get("capPerPurchase") as? Number)?.toDouble(),
            cashbackMonthlyCap = ((snapshot.get("cashback") as? Map<*, *>)?.get("monthlyCap") as? Number)?.toDouble(),
            comboDeals = combos,
            inventory = products
        )

        val news = (snapshot.get("news") as? List<*>)?.mapNotNull { raw ->
            val map = raw as? Map<*, *> ?: return@mapNotNull null
            StoreNews(
                id = map["id"] as? String ?: return@mapNotNull null,
                storeId = map["storeId"] as? String ?: base.id,
                storeName = map["storeName"] as? String ?: base.name,
                title = map["title"] as? String ?: return@mapNotNull null,
                content = map["content"] as? String ?: "",
                timeAgoText = map["timeAgoText"] as? String ?: "Publicado",
                badge = map["badge"] as? String ?: "Novedad",
                isHot = map["isHot"] as? Boolean ?: true
            )
        }.orEmpty()

        return updated to news
    }
}
