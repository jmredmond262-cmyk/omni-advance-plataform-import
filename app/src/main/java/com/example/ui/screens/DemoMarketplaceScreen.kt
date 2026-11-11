package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.SampleData
import com.example.model.Store
import com.example.ui.MainViewModel

private val MarketBg = Color(0xFF070A14)
private val MarketCard = Color(0xFF111D32)
private val MarketCyan = Color(0xFF57DDF5)
private val MarketViolet = Color(0xFFB69CFF)
private val MarketMuted = Color(0xFFB8C8D9)

@Composable
fun DemoMarketplaceScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onOpenOrders: () -> Unit
) {
    val stores = SampleData.sampleStores

    Column(Modifier.fillMaxSize().background(MarketBg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Volver", tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text("ENTORNO DE PRUEBA", color = MarketViolet, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                Text("Mercado OMNI", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold)
            }
            Icon(Icons.Default.ShoppingBag, contentDescription = null, tint = MarketCyan, modifier = Modifier.size(25.dp))
        }

        Surface(
            color = Color(0xFF10243A),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)
        ) {
            Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("COMERCIOS DEMO · OMNI PAY", color = MarketCyan, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                Text(
                    "Elegí entre los comercios de muestra, explorá sus catálogos y armá una compra. Los comercios DEMO están separados del directorio real y no se utiliza dinero real.",
                    color = Color.White, fontSize = 11.sp
                )
                Text(
                    "Si un comercio tiene una cuenta DEMO vinculada desde otra cuenta OMNI, el pedido puede compartirse para probar el circuito entre cliente y comercio. Si no tiene titular vinculado, se genera un QR local de muestra.",
                    color = MarketMuted, fontSize = 10.sp
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(
                    "${stores.size} COMERCIOS DE MUESTRA DISPONIBLES",
                    color = MarketCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold
                )
            }
            items(stores, key = { it.id }) { store ->
                DemoStoreCard(
                    store = store,
                    onSelect = {
                        viewModel.selectStoreForOrders(store)
                        onOpenOrders()
                    }
                )
            }
            item {
                Text(
                    "CATÁLOGOS FICTICIOS · PRECIOS DE PRUEBA · SIN COBROS REALES",
                    color = MarketMuted, fontSize = 9.sp,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun DemoStoreCard(store: Store, onSelect: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MarketCard),
        shape = RoundedCornerShape(15.dp),
        border = BorderStroke(1.dp, MarketCyan.copy(alpha = 0.24f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Storefront, contentDescription = null, tint = MarketCyan, modifier = Modifier.size(23.dp))
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(store.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(store.address, color = MarketMuted, fontSize = 10.sp)
                }
                Surface(color = Color(0xFF123D46), shape = RoundedCornerShape(7.dp)) {
                    Text(
                        "${store.inventory.count { it.inStock }} artículos",
                        color = MarketCyan, fontSize = 9.sp,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                    )
                }
            }
            Text(store.description, color = MarketMuted, fontSize = 10.sp, maxLines = 2)
            store.inventory.filter { it.inStock }.take(4).forEach { product ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        product.name, color = Color.White, fontSize = 10.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "OM$ ${String.format("%,.2f", product.price)}",
                        color = MarketCyan, fontWeight = FontWeight.Bold, fontSize = 10.sp
                    )
                }
            }
            Button(
                onClick = onSelect,
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
