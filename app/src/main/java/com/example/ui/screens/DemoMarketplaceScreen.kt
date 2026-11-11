package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val DemoBg = Color(0xFF070A14)
private val DemoCard = Color(0xFF111D32)
private val DemoCyan = Color(0xFF57DDF5)
private val DemoViolet = Color(0xFFB69CFF)
private val DemoMuted = Color(0xFFB8C8D9)

@Composable
fun DemoMarketplaceScreen(
    onBack: () -> Unit,
    onOpenOrders: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().background(DemoBg).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Volver", tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text("EXPERIENCIA DE PRUEBA", color = DemoViolet, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                Text("Mercado OMNI", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            }
            Icon(Icons.Default.ShoppingCart, contentDescription = null, tint = DemoCyan, modifier = Modifier.size(25.dp))
        }

        Surface(
            color = Color(0xFF201B35),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("ENTORNO DEMO · SIN DINERO REAL", color = DemoViolet, fontWeight = FontWeight.ExtraBold, fontSize = 10.sp)
                Text(
                    "Elegí entre los comercios de muestra, armá tu carrito y generá un QR DEMO para recorrer la experiencia de compra. No se utiliza dinero real.",
                    color = Color.White, fontSize = 11.sp
                )
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = DemoCard),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, DemoCyan.copy(alpha = 0.28f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.QrCode2, contentDescription = null, tint = DemoCyan, modifier = Modifier.size(25.dp))
                    Text("Pedidos y compras OMNI", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                        modifier = Modifier.padding(start = 9.dp))
                }
                Text(
                    "Elegí un comercio de muestra, agregá productos y confirmá para generar un QR DEMO. El catálogo de prueba está separado del directorio de comercios reales.",
                    color = DemoMuted, fontSize = 12.sp
                )
                Text(
                    "El QR permite visualizar el resumen de compra y compartir la muestra. Esta ruta no crea pedidos compartidos ni mueve saldos.",
                    color = DemoMuted, fontSize = 11.sp
                )
                Button(
                    onClick = onOpenOrders,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF087D98))
                ) {
                    Icon(Icons.Default.ShoppingCart, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Abrir comercios y realizar pedido")
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = DemoCard.copy(alpha = 0.72f)),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("¿Qué representa el QR DEMO?", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text(
                    "Es un QR ilustrativo que contiene el identificador de muestra, comercio, importe y productos. No es una orden aceptada por el comercio ni un comprobante de pago.",
                    color = DemoMuted, fontSize = 10.sp
                )
            }
        }

        Spacer(Modifier.weight(1f))
        Text(
            "Compras de prueba · Billetera ficticia · Sin cobros reales",
            color = DemoMuted, fontSize = 9.sp, modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }
}
