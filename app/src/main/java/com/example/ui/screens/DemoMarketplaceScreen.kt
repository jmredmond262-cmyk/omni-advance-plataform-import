package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.SampleData
import com.example.model.ProductCategory
import com.example.model.Store
import com.example.model.StoreProduct
import com.example.model.StoreType
import com.example.ui.MainViewModel
import com.google.firebase.firestore.FirebaseFirestore

private val MarketBg = Color(0xFF070A14)
private val MarketCard = Color(0xFF111D32)
private val MarketCyan = Color(0xFF57DDF5)
private val MarketMuted = Color(0xFFB8C8D9)

@Composable
fun DemoMarketplaceScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onOpenOrders: () -> Unit
) {
    var stores by remember { mutableStateOf<List<Store>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        val registration = FirebaseFirestore.getInstance()
            .collection("merchantProfiles")
            .addSnapshotListener { snapshot, failure ->
                if (failure != null) {
                    loading = false
                    error = "No se pudo consultar el catálogo comercial: ${failure.localizedMessage ?: "error de conexión"}"
                } else {
                    val baseStores = SampleData.sampleStores.associateBy { it.id }
                    stores = snapshot?.documents.orEmpty().mapNotNull { doc ->
                        val base = baseStores[doc.id] ?: return@mapNotNull null
                        val products = (doc.get("inventory") as? List<*>)?.mapNotNull { raw ->
                            val item = raw as? Map<*, *> ?: return@mapNotNull null
                            val category = runCatching {
                                ProductCategory.valueOf(item["category"] as? String ?: ProductCategory.ALMACEN.name)
                            }.getOrDefault(ProductCategory.ALMACEN)
                            val price = (item["price"] as? Number)?.toDouble() ?: 0.0
                            val name = item["name"] as? String ?: return@mapNotNull null
                            if (price <= 0.0) return@mapNotNull null
                            StoreProduct(
                                id = item["id"] as? String ?: name.hashCode().toString(),
                                name = name,
                                category = category,
                                price = price,
                                originalPrice = (item["originalPrice"] as? Number)?.toDouble(),
                                inStock = item["inStock"] as? Boolean ?: true,
                                unitLabel = item["unitLabel"] as? String ?: "unid.",
                                photoUri = item["photoUri"] as? String,
                                barcode = item["barcode"] as? String,
                                description = item["description"] as? String ?: ""
                            )
                        }.orEmpty()
                        base.copy(
                            name = doc.getString("storeName") ?: doc.getString("name") ?: base.name,
                            address = doc.getString("address") ?: base.address,
                            phone = doc.getString("phone") ?: doc.getString("contactPhone") ?: base.phone,
                            description = doc.getString("description") ?: base.description,
                            inventory = products
                        )
                    }.filter { it.inventory.any(StoreProduct::inStock) }
                        .sortedBy { it.name.lowercase() }
                    loading = false
                    error = null
                }
            }
        onDispose { registration.remove() }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MarketBg).padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Volver", tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text("COMPRAS Y COMERCIOS", color = MarketCyan, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                Text("Mercado OMNI", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold)
            }
            Icon(Icons.Default.ShoppingBag, contentDescription = null, tint = MarketCyan, modifier = Modifier.size(25.dp))
        }
        Surface(
            color = Color(0xFF10243A), shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("MERCADO DE PRUEBA · OMNI PAY DEMO", color = MarketCyan, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                Text("Elegí un comercio registrado, revisá sus artículos y armá tu pedido. El pago se debita y acredita únicamente con saldo ficticio.", color = Color.White, fontSize = 11.sp)
            }
        }
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MarketCyan)
            }
            error != null -> Text(error.orEmpty(), color = Color(0xFFFFA49B), modifier = Modifier.padding(12.dp))
            stores.isEmpty() -> Card(
                colors = CardDefaults.cardColors(containerColor = MarketCard),
                shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Todavía no hay comercios con catálogo publicado", color = Color.White, fontWeight = FontWeight.Bold)
                    Text("El comercio debe activar su perfil y publicar al menos un artículo disponible. Los datos de muestra no se presentan como vendedores reales.", color = MarketMuted, fontSize = 11.sp)
                }
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 90.dp, top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Text("${stores.size} COMERCIOS CON PRODUCTOS DISPONIBLES", color = MarketCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                items(stores, key = { it.id }) { store ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MarketCard),
                        shape = RoundedCornerShape(15.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MarketCyan.copy(alpha = 0.22f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Storefront, contentDescription = null, tint = MarketCyan, modifier = Modifier.size(22.dp))
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(store.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text(store.address, color = MarketMuted, fontSize = 10.sp)
                                }
                                Surface(color = Color(0xFF123D46), shape = RoundedCornerShape(7.dp)) {
                                    Text("${store.inventory.count { it.inStock }} artículos", color = MarketCyan, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp))
                                }
                            }
                            Text(store.description, color = MarketMuted, fontSize = 10.sp, maxLines = 2)
                            store.inventory.filter { it.inStock }.take(3).forEach { product ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(product.name, color = Color.White, fontSize = 10.sp, modifier = Modifier.weight(1f))
                                    Text("OM$ ${String.format("%,.2f", product.price)}", color = MarketCyan, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                                }
                            }
                            Button(
                                onClick = {
                                    viewModel.selectStoreForOrders(store)
                                    onOpenOrders()
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF087D98))
                            ) {
                                Icon(Icons.Default.ShoppingBag, contentDescription = null, modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(7.dp))
                                Text("Ver catálogo y comprar")
                            }
                        }
                    }
                }
            }
        }
    }
}
