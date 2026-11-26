package com.example.ui.screens

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.LunchDining
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Redeem
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.StoreType
import com.example.ui.MainViewModel
import com.example.ui.Screen
import com.example.ui.StoreScheduleFilter
import com.example.ui.theme.CyberGlowAmber
import com.example.ui.theme.CyberGlowCyan
import com.example.ui.theme.CyberGlowGold
import com.example.ui.theme.CyberGlowGreen
import com.example.ui.theme.CyberGlowPink
import com.example.ui.theme.CyberGlowPurple
import com.example.ui.theme.CyberGlowRose
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import com.example.ui.theme.CyberGlowTeal
import com.example.ui.theme.CyberGlowCyan
import com.example.ui.theme.CyberGlowPurple
import com.example.ui.theme.CyberGlowAmber
import com.example.ui.theme.CyberGlowPink
import com.example.ui.theme.CyberGlowGold
import com.example.ui.theme.CyberOrbGradient

data class HomeCategoryItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val accentColor: Color,
    val onClick: () -> Unit
)

@Composable
fun DailyHubScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val tasks by viewModel.tasks.collectAsState()
    LaunchedEffect(tasks) {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        context.getSharedPreferences("omni_daily_summary", 0).edit()
            .putString("summary_date", today)
            .putInt("summary_total", tasks.size)
            .putInt("summary_completed", tasks.count { it.isCompleted })
            .apply()
    }
    val currentCity by viewModel.currentCity.collectAsState()
    val displayLocality = currentCity.ifBlank { "Tu zona" }
    val stores by viewModel.stores.collectAsState()
    val weather by viewModel.liveWeatherData.collectAsState()
    val touristAttractions by viewModel.touristAttractions.collectAsState()
    val wallet by viewModel.wallet.collectAsState()
    var isBalanceVisible by remember { mutableStateOf(true) }

    var showTrackingDetails by remember { mutableStateOf(false) }
    var showRedeemDialog by remember { mutableStateOf(false) }

    // Servicios principales, sin accesos duplicados con el dock inferior
    val categoryItems = listOf(
        HomeCategoryItem(
            id = "food",
            title = "Comida",
            subtitle = "Pedí tus comidas favoritas",
            icon = Icons.Default.LunchDining,
            accentColor = Color(0xFFFFA33A),
            onClick = {
                viewModel.filterStoresByType(StoreType.GASTRONOMIA)
                viewModel.navigateTo(Screen.StoresList)
            }
        ),
        HomeCategoryItem(
            id = "orders",
            title = "Mis compras",
            subtitle = "Listas, comparaciones y pedidos",
            icon = Icons.Default.ShoppingCart,
            accentColor = Color(0xFF25D9FF),
            onClick = {
                viewModel.navigateTo(Screen.MyPurchases)
            }
        ),
        HomeCategoryItem(
            id = "cabs",
            title = "Remises",
            subtitle = "Pedí viajes y traslados",
            icon = Icons.Default.DirectionsCar,
            accentColor = Color(0xFF438BFF),
            onClick = {
                viewModel.navigateTo(Screen.TripPlanner)
            }
        ),
        HomeCategoryItem(
            id = "health",
            title = "Salud",
            subtitle = "Farmacias y emergencias",
            icon = Icons.Default.Favorite,
            accentColor = Color(0xFFFF5C9A),
            onClick = {
                viewModel.clearStoreTypeFilter()
                viewModel.setScheduleFilter(StoreScheduleFilter.FARMACIAS_TURNO)
                viewModel.navigateTo(Screen.StoresList)
            }
        ),
        HomeCategoryItem(
            id = "more",
            title = "Comercios",
            subtitle = "Explorá locales de tu zona",
            icon = Icons.Default.GridView,
            accentColor = Color(0xFF54E5FF),
            onClick = {
                viewModel.clearStoreTypeFilter()
                viewModel.setScheduleFilter(StoreScheduleFilter.TODOS)
                viewModel.navigateTo(Screen.StoresList)
            }
        ),
        HomeCategoryItem(
            id = "offers",
            title = "Ofertas",
            subtitle = "Promociones y combos vigentes",
            icon = Icons.Default.LocalOffer,
            accentColor = Color(0xFFFFC857),
            onClick = {
                viewModel.navigateTo(Screen.OffersBenefits)
            }
        ),
        HomeCategoryItem(
            id = "merchant_portal",
            title = "Mi comercio",
            subtitle = "Administrá tu negocio",
            icon = Icons.Default.Build,
            accentColor = Color(0xFF38D6A6),
            onClick = {
                viewModel.navigateTo(Screen.MerchantHub)
            }
        )
    )

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF070B14))
            .testTag("daily_hub_screen"),
        // El padding inferior de 100.dp previene que la barra dock o la barra del sistema tape contenido
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ==========================================
        // 1. TARJETA GLASSMORPHISM OMNI PAY (BILLETERA)
        // ==========================================
        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0E1726).copy(alpha = 0.85f)),
                border = androidx.compose.foundation.BorderStroke(1.2.dp, CyberGlowCyan.copy(alpha = 0.45f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("home_omni_pay_card")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    // Header de la tarjeta
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = CircleShape,
                                color = CyberGlowCyan.copy(alpha = 0.15f),
                                modifier = Modifier.size(28.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.AccountBalanceWallet,
                                        contentDescription = null,
                                        tint = CyberGlowCyan,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "OMNI PAY",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        }

                        // Badge de CashBack / Estado
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF00E676).copy(alpha = 0.15f),
                            border = androidx.compose.foundation.BorderStroke(0.8.dp, Color(0xFF00E676).copy(alpha = 0.4f))
                        ) {
                            Text(
                                text = "CASHBACK ACTIVO",
                                color = Color(0xFF00E676),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Saldo disponible
                    Text(
                        text = "Saldo disponible",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (isBalanceVisible) "OM$ ${String.format("%,.2f", wallet.balance)}" else "OM$ ••••••••",
                            color = Color.White,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.5.sp
                        )
                        IconButton(
                            onClick = { isBalanceVisible = !isBalanceVisible },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = if (isBalanceVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = "Toggle saldo",
                                tint = Color(0xFF94A3B8),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Botones de acción rápida: Transferir, Pagar QR, Cargar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 1. Transferir
                        Button(
                            onClick = { viewModel.navigateTo(Screen.Wallet) },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Send, contentDescription = null, tint = CyberGlowCyan, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(5.dp))
                                Text("Transferir", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }

                        // 2. Pagar QR
                        Button(
                            onClick = { viewModel.navigateTo(Screen.Wallet) },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = CyberGlowCyan.copy(alpha = 0.22f)),
                            border = androidx.compose.foundation.BorderStroke(1.dp, CyberGlowCyan.copy(alpha = 0.6f)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.QrCodeScanner, contentDescription = null, tint = CyberGlowCyan, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(5.dp))
                                Text("Pagar QR", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        // 3. Cargar
                        Button(
                            onClick = { viewModel.navigateTo(Screen.Wallet) },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Add, contentDescription = null, tint = Color(0xFF38D6A6), modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(5.dp))
                                Text("Cargar", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }

        // Acceso al pronóstico extendido
        item {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1321)),
                border = androidx.compose.foundation.BorderStroke(0.8.dp, Color(0xFF1E2D4A)),
                modifier = Modifier.fillMaxWidth().clickable { viewModel.navigateTo(Screen.Weather) }
                    .testTag("home_weather_forecast_entry")
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("🌤️", fontSize = 22.sp)
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text("Clima y pronóstico extendido", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = "Pronóstico por hora, próximos 7 días y alertas de tu zona",
                            color = Color(0xFF94A3B8), fontSize = 10.sp, maxLines = 1
                        )
                    }
                    Text("VER ›", color = CyberGlowCyan, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
        }

        // ==========================================
        // 2. SERVICIOS PRINCIPALES
        // ==========================================
        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Fila 1 (Comida, Pedidos, Remises, Servicios)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    categoryItems.take(4).forEach { item ->
                        CyberCategoryCard(
                            item = item,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // Fila 2 (Salud y Comercios)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    categoryItems.drop(4).take(4).forEach { item ->
                        CyberCategoryCard(
                            item = item,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // ==========================================
        // ACCESO A MODO RUTINA
        // ==========================================
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF101B30)),
                border = androidx.compose.foundation.BorderStroke(1.dp, CyberGlowCyan.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth().clickable { viewModel.navigateTo(Screen.Routine) }
                    .testTag("home_routine_mode_entry")
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(11.dp)
                ) {
                    Text("⏰", fontSize = 25.sp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Modo Rutina", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("Organizá tus actividades y recordatorios recurrentes", color = Color(0xFF9AAAC2), fontSize = 10.sp)
                    }
                    Text("ABRIR ›", color = CyberGlowCyan, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
        }

        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF101B30)),
                border = androidx.compose.foundation.BorderStroke(1.dp, CyberGlowPurple.copy(alpha = 0.38f)),
                modifier = Modifier.fillMaxWidth().clickable { viewModel.navigateTo(Screen.JobBoard) }
                    .testTag("home_job_board_entry")
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(11.dp)
                ) {
                    Text("🧰", fontSize = 25.sp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("OMNI CONEX", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("Empleo, oficios, profesionales y servicios", color = Color(0xFF9AAAC2), fontSize = 10.sp)
                    }
                    Text("ABRIR ›", color = CyberGlowPurple, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
        }

        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF101B30)),
                border = androidx.compose.foundation.BorderStroke(1.dp, CyberGlowAmber.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth().clickable { viewModel.navigateTo(Screen.Events) }
                    .testTag("home_events_entry")
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(11.dp)
                ) {
                    Text("📅", fontSize = 25.sp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Eventos y Agenda OMNI", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("Actividades, encuentros y propuestas de tu región", color = Color(0xFF9AAAC2), fontSize = 10.sp)
                    }
                    Text("ABRIR ›", color = CyberGlowAmber, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
        }

        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF101B30)),
                border = androidx.compose.foundation.BorderStroke(1.dp, CyberGlowPink.copy(alpha = 0.38f)),
                modifier = Modifier.fillMaxWidth().clickable { viewModel.navigateTo(Screen.Moments) }
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                    Text("🎁", fontSize = 25.sp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("OMNI MOMENTOS", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("Cumpleaños, aniversarios, saludos y recuerdos", color = Color(0xFF9AAAC2), fontSize = 10.sp)
                    }
                    Text("ABRIR ›", color = CyberGlowPink, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
        }

        // ==========================================
        // 3. OMNI EXPERIENCE
        // ==========================================
        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1629)),
                border = androidx.compose.foundation.BorderStroke(1.dp, CyberGlowPurple.copy(alpha = 0.48f)),
                modifier = Modifier.fillMaxWidth().clickable { viewModel.navigateTo(Screen.OmniExperience) }.testTag("home_omni_experience_card")
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(34.dp).clip(CircleShape).background(Color(0xFF292044)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = CyberGlowPurple, modifier = Modifier.size(19.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("OMNI EXPERIENCE", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                            Text("Experiencias, oportunidades y beneficios", fontSize = 10.sp, color = Color(0xFF9AAAC2))
                        }
                        Surface(shape = RoundedCornerShape(7.dp), color = Color(0xFF211B39)) {
                            Text("NUEVO", color = CyberGlowPurple, fontSize = 8.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp))
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        val experiences = listOf(
                            Triple("🎓", "OMNI Joven", "Estudiantes"),
                            Triple("🧳", "OMNI Turista", "Visitantes"),
                            Triple("⚡", "OMNI Flash", "Ofertas rápidas"),
                            Triple("⭐", "OMNI Puntos", "Recompensas")
                        )
                        experiences.forEach { experience ->
                            Surface(
                                modifier = Modifier.weight(1f).clickable { viewModel.navigateTo(Screen.OmniExperience) }.testTag("home_experience_${experience.second.lowercase().replace(" ", "_")}"),
                                shape = RoundedCornerShape(11.dp), color = Color(0xFF111F35),
                                border = androidx.compose.foundation.BorderStroke(0.8.dp, CyberGlowPurple.copy(alpha = 0.28f))
                            ) {
                                Column(Modifier.padding(vertical = 9.dp, horizontal = 2.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(experience.first, fontSize = 17.sp)
                                    Text(experience.second.removePrefix("OMNI "), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
                                }
                            }
                        }
                    }
                    TextButton(onClick = { viewModel.navigateTo(Screen.OmniExperience) }, modifier = Modifier.align(Alignment.End), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp)) {
                        Text("EXPLORAR EXPERIENCE ›", color = CyberGlowPurple, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
        }

        // ==========================================
        // 4. TARJETA DE SEGUIMIENTO EN VIVO (En español)
        // ==========================================
        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0C1424)),
                border = androidx.compose.foundation.BorderStroke(1.2.dp, CyberGlowCyan.copy(alpha = 0.4f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showTrackingDetails = !showTrackingDetails }
                    .testTag("home_live_tracking_card")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    // Header con pulso verde "Seguimiento en Vivo"
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF00E676))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Seguimiento en Vivo",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF132238),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E3557))
                        ) {
                            Text(
                                text = "Móvil Activo",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = CyberGlowCyan,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Mapa estilizado con trazo de ruta neón y vehículo
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF080D1A))
                            .border(1.dp, Color(0xFF142038), RoundedCornerShape(12.dp))
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val w = size.width
                            val h = size.height

                            // Líneas de cuadrícula de mapa sutil
                            drawLine(color = Color(0xFF152238), start = Offset(0f, h * 0.35f), end = Offset(w, h * 0.35f), strokeWidth = 1f)
                            drawLine(color = Color(0xFF152238), start = Offset(0f, h * 0.7f), end = Offset(w, h * 0.7f), strokeWidth = 1f)
                            drawLine(color = Color(0xFF152238), start = Offset(w * 0.25f, 0f), end = Offset(w * 0.25f, h), strokeWidth = 1f)
                            drawLine(color = Color(0xFF152238), start = Offset(w * 0.65f, 0f), end = Offset(w * 0.65f, h), strokeWidth = 1f)

                            val routePath = Path().apply {
                                moveTo(w * 0.16f, h * 0.75f)
                                lineTo(w * 0.28f, h * 0.75f)
                                lineTo(w * 0.38f, h * 0.42f)
                                lineTo(w * 0.58f, h * 0.42f)
                                lineTo(w * 0.70f, h * 0.30f)
                                lineTo(w * 0.82f, h * 0.30f)
                            }

                            drawPath(path = routePath, color = Color(0xFFFF9100).copy(alpha = 0.35f), style = Stroke(width = 8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                            drawPath(path = routePath, color = Color(0xFFFFB300), style = Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round))

                            drawCircle(color = Color(0xFF00E5FF), radius = 6f, center = Offset(w * 0.16f, h * 0.75f))
                            drawCircle(color = Color.White, radius = 2.5f, center = Offset(w * 0.16f, h * 0.75f))
                        }

                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 10.dp, end = 26.dp)
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(CyberGlowAmber),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.TwoWheeler,
                                contentDescription = "Delivery en camino",
                                tint = Color.Black,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Pedido Activo: Llega en 5 min.",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "Rotisería La Nueva • Pedido #OMNI-9082",
                                fontSize = 11.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }

                        Text(
                            text = "Ver ruta en vivo →",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = CyberGlowCyan
                        )
                    }

                    AnimatedVisibility(visible = showTrackingDetails) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp)
                                .background(Color(0xFF080E1C), RoundedCornerShape(10.dp))
                                .padding(10.dp)
                        ) {
                            Text(text = "📍 Destino: Tu domicilio en $displayLocality", fontSize = 11.sp, color = Color(0xFFE2E8F0))
                            Text(text = "💳 Abonado con OmniPay ($3.450)", fontSize = 11.sp, color = CyberGlowPink)
                            Text(text = "🛵 Repartidor asignado: Matías (Cel: 3482-654321)", fontSize = 11.sp, color = Color(0xFF94A3B8))
                        }
                    }
                }
            }
        }

        // ==========================================
        // 5. HERO BANNER OMNI AI
        // ==========================================
        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1629)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF9100).copy(alpha = 0.4f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.navigateTo(Screen.OmniAi) }
                    .testTag("home_omni_ai_banner")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                            .background(CyberOrbGradient),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Ai",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Black
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Omni AI Copilot",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "EN LÍNEA",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = CyberGlowAmber
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Preguntale a la IA sobre farmacias de turno, remises y precios en $displayLocality.",
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }

                    Text(
                        text = "Consultar →",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = CyberGlowAmber
                    )
                }
            }
        }

        // ==========================================
        // 6. OMNI PLACES: COMERCIOS DE TU ZONA
        // ==========================================
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "COMERCIOS EN $displayLocality".uppercase(),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF94A3B8),
                    letterSpacing = 1.sp
                )

                Text(
                    text = "Ver todos",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = CyberGlowCyan,
                    modifier = Modifier.clickable {
                        viewModel.clearStoreTypeFilter()
                        viewModel.navigateTo(Screen.StoresList)
                    }
                )
            }
        }

        item {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(horizontal = 2.dp)
            ) {
                items(stores.take(8), key = { it.id }) { store ->
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1627)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, CyberGlowCyan.copy(alpha = 0.25f)),
                        modifier = Modifier
                            .width(200.dp)
                            .clickable {
                                viewModel.selectStore(store)
                                viewModel.navigateTo(Screen.StoreDetail)
                            }
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            // Badge superior de estado y rating
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (store.isEffectivelyOpen) Color(0xFF00E676).copy(alpha = 0.15f) else Color(0xFFEF4444).copy(alpha = 0.15f),
                                    border = androidx.compose.foundation.BorderStroke(0.6.dp, if (store.isEffectivelyOpen) Color(0xFF00E676).copy(alpha = 0.4f) else Color(0xFFEF4444).copy(alpha = 0.4f))
                                ) {
                                    Text(
                                        text = if (store.isEffectivelyOpen) "ABIERTO" else "CERRADO",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = if (store.isEffectivelyOpen) Color(0xFF00E676) else Color(0xFFEF4444),
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = CyberGlowGold.copy(alpha = 0.15f)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(text = "★", color = CyberGlowGold, fontSize = 9.sp)
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text(
                                            text = "${store.rating}",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = store.name,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = store.address,
                                fontSize = 11.sp,
                                color = Color(0xFF94A3B8),
                                maxLines = 1
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "⚡ 15-30 min",
                                    fontSize = 10.sp,
                                    color = CyberGlowCyan,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Ver menú ›",
                                    fontSize = 10.sp,
                                    color = Color(0xFF94A3B8),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }


}

/**
 * Tarjeta compacta Cyber Neon: doble contorno, halo pulsante y acento luminoso.
 * Conserva las dimensiones originales para no alterar la grilla.
 */
@Composable
fun CyberCategoryCard(item: HomeCategoryItem, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val transition = rememberInfiniteTransition(label = "category_neon")
    val pulse by transition.animateFloat(
        initialValue = 0.55f, targetValue = 0.95f,
        animationSpec = infiniteRepeatable(tween(2000), repeatMode = RepeatMode.Reverse),
        label = "category_glow"
    )
    val glow = if (pressed) 1.15f else pulse
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .height(96.dp)
            .clip(shape)
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        item.accentColor.copy(alpha = 0.22f * glow),
                        Color(0xFF0F1829),
                        Color(0xFF060B14)
                    )
                )
            )
            .drawBehind {
                val radius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx())
                // Halo exterior difuminado neón
                drawRoundRect(
                    item.accentColor.copy(alpha = 0.15f + glow * 0.12f),
                    style = Stroke(8.dp.toPx()),
                    cornerRadius = radius
                )
                // Contorno fino brillante
                drawRoundRect(
                    item.accentColor.copy(alpha = 0.50f + glow * 0.45f),
                    style = Stroke(1.4.dp.toPx()),
                    cornerRadius = radius
                )
                // Reflejo interior vidrioso
                drawRoundRect(
                    Color.White.copy(alpha = 0.12f + glow * 0.08f),
                    topLeft = Offset(2.dp.toPx(), 2.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size(size.width - 4.dp.toPx(), size.height - 4.dp.toPx()),
                    style = Stroke(0.7.dp.toPx()),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(14.dp.toPx())
                )
                // Acento luminoso superior
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            item.accentColor.copy(alpha = 0.85f * glow),
                            Color.Transparent
                        )
                    ),
                    topLeft = Offset(size.width * 0.20f, 1.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size(size.width * 0.60f, 1.5.dp.toPx()),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
                )
            }
            .clickable(interactionSource = interaction, indication = null) { item.onClick() }
            .testTag("home_category_${item.id}"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Contenedor de icono estilizado con halo de neón
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(item.accentColor.copy(alpha = 0.12f))
                    .drawBehind {
                        drawCircle(
                            item.accentColor.copy(alpha = 0.25f + glow * 0.25f),
                            radius = size.minDimension * 0.52f
                        )
                        drawCircle(
                            item.accentColor.copy(alpha = 0.12f + glow * 0.15f),
                            radius = size.minDimension * 0.70f
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    item.icon,
                    contentDescription = item.title,
                    tint = item.accentColor,
                    modifier = Modifier.size(19.dp)
                )
            }
            Text(
                item.title,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1
            )
            Text(
                item.subtitle,
                fontSize = 8.sp,
                lineHeight = 10.sp,
                color = Color(0xFFCBD5E1),
                maxLines = 2,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = 2.dp)
            )
            // Barra de acento inferior neón
            Box(
                Modifier
                    .width(22.dp)
                    .height(2.2.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                item.accentColor.copy(alpha = 0.3f),
                                item.accentColor,
                                item.accentColor.copy(alpha = 0.3f)
                            )
                        )
                    )
            )
        }
    }
}
