package com.omni.admin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

private val Navy = Color(0xFF060B16)
private val Panel = Color(0xFF0D1729)
private val PanelSoft = Color(0xFF14243B)
private val Mint = Color(0xFF00E5FF)
private val Muted = Color(0xFF9EADC3)
private val White = Color(0xFFF4F7FC)
private val Danger = Color(0xFFFF8585)
private const val ADMIN_API_URL = "https://omni-admin-api.jmredmond262.workers.dev"

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.statusBarColor = android.graphics.Color.rgb(6, 11, 22)
    window.navigationBarColor = android.graphics.Color.rgb(6, 11, 22)
    setContent {
      MaterialTheme(colorScheme = darkColorScheme(
        primary = Mint, onPrimary = Navy, background = Navy, surface = Panel,
        onBackground = White, onSurface = White, secondary = Muted, error = Danger
      )) { OmniAdminRoot() }
    }
  }
}

private data class AdminTab(val id: String, val label: String, val collection: String? = null)
private val tabs = listOf(
  AdminTab("dashboard", "Panel ejecutivo"), AdminTab("liveMap", "Mapa en tiempo real"), AdminTab("users", "Cuentas de usuario", "users"),
  AdminTab("wallets", "Cuentas OMNI Pay", "walletAccounts"), AdminTab("operations", "Movimientos OMNI Pay", "walletOperations"), AdminTab("finance", "Control financiero DEMO"),
  AdminTab("stores", "Comercios registrados", "merchantProfiles"), AdminTab("demoClaims", "Titularidad DEMO"), AdminTab("orders", "Pedidos comerciales", "storeOrders"), AdminTab("receipts", "Comprobantes DEMO", "storeReceipts"), AdminTab("receiptAdjustments", "Ajustes de comprobantes", "storeReceiptAdjustments"), AdminTab("events", "Eventos publicados", "omniEvents"), AdminTab("moments", "Fechas especiales", "omniMoments"), AdminTab("conexPosts", "Ofertas laborales", "jobPosts"), AdminTab("conexProfiles", "Perfiles profesionales", "omniProfessionalProfiles"),
  AdminTab("feedback", "Opiniones y reportes", "appFeedback"), AdminTab("activity", "Actividad de uso", "usageEvents"), AdminTab("modules", "Módulos y funciones", "moduleRegistry"),
  AdminTab("audit", "Auditoría y trazabilidad", "adminAuditLogs"), AdminTab("invitations", "Accesos de prueba", "betaInvitations"), AdminTab("settings", "Parámetros generales")
)
private data class AdminNavGroup(val id: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val tabIds: List<String>)
private val navGroups = listOf(
  AdminNavGroup("overview", "Panel", Icons.Default.Dashboard, listOf("dashboard")),
  AdminNavGroup("people", "Usuarios", Icons.Default.PeopleAlt, listOf("users", "feedback")),
  AdminNavGroup("commerce", "Comercio y pagos", Icons.Default.Storefront, listOf("stores", "demoClaims", "orders", "receipts", "receiptAdjustments", "wallets", "operations", "finance")),
  AdminNavGroup("system", "Sistema", Icons.Default.Settings, listOf("activity", "events", "moments", "conexPosts", "conexProfiles", "modules", "audit", "invitations", "settings")),
  AdminNavGroup("geography", "Mapa", Icons.Default.Public, listOf("liveMap"))
)

@Composable
private fun OmniAdminRoot() {
  val auth = remember { FirebaseAuth.getInstance() }
  var user by remember { mutableStateOf(auth.currentUser) }
  var selected by remember { mutableStateOf("dashboard") }
  var payload by remember { mutableStateOf(JSONObject()) }
  var loading by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf("") }
  var nextPageToken by remember { mutableStateOf("") }
  var nextOffset by remember { mutableStateOf(0) }
  var hasMore by remember { mutableStateOf(false) }
  var issuedClaimCode by remember { mutableStateOf("") }
  var issuedClaimStore by remember { mutableStateOf("") }
  var showOrderContext by remember { mutableStateOf(false) }
  var orderContext by remember { mutableStateOf<JSONObject?>(null) }
  var orderContextLoading by remember { mutableStateOf(false) }
  var orderContextError by remember { mutableStateOf("") }
  val scope = rememberCoroutineScope()

  fun refresh() {
    val current = auth.currentUser ?: run {
      orderContextError = "La sesión administrativa ya no está disponible."
      return
    }
    scope.launch {
      loading = true
      error = ""
      try {
        val token = current.getIdToken(true).await().token ?: throw IllegalStateException("No se pudo renovar la sesión.")
        val tab = tabs.first { it.id == selected }
        nextPageToken = ""; nextOffset = 0; hasMore = false
        payload = when (selected) {
          "dashboard" -> AdminApi.call(token, "dashboard")
          "liveMap" -> AdminApi.call(token, "geoPresence")
          "finance" -> AdminApi.call(token, "finance")
          "demoClaims" -> AdminApi.call(token, "demoMerchants")
          "settings" -> AdminApi.call(token, "getConfig")
          else -> AdminApi.call(token, "list", JSONObject().put("collection", tab.collection).put("limit", 100))
        }
        nextPageToken = payload.optString("nextPageToken", "")
        nextOffset = payload.optInt("nextOffset", 0)
        hasMore = payload.optBoolean("hasMore", false)
      } catch (ex: Exception) { error = ex.message ?: "No se pudo conectar con OMNI Admin." }
      finally { loading = false }
    }
  }

  fun issueDemoCode(storeId: String, storeName: String) {
    val current = auth.currentUser ?: return
    scope.launch {
      loading = true; error = ""; issuedClaimCode = ""; issuedClaimStore = ""
      try {
        val token = current.getIdToken(true).await().token ?: throw IllegalStateException("No se pudo renovar la sesión.")
        val result = AdminApi.call(token, "issueDemoMerchantClaimCode", JSONObject().put("storeId", storeId))
        issuedClaimCode = result.optString("code"); issuedClaimStore = storeName
        payload = AdminApi.call(token, "demoMerchants")
      } catch (ex: Exception) { error = ex.message ?: "No se pudo emitir el código." }
      finally { loading = false }
    }
  }

  fun loadMore() {
    val current = auth.currentUser ?: return
    if (!hasMore || loading) return
    scope.launch {
      loading = true; error = ""
      try {
        val token = current.getIdToken(true).await().token ?: throw IllegalStateException("No se pudo renovar la sesión.")
        val tab = tabs.first { it.id == selected }
        val data = JSONObject().put("collection", tab.collection).put("limit", 100)
        if (nextPageToken.isNotBlank()) data.put("pageToken", nextPageToken)
        val page = AdminApi.call(token, "list", data)
        val merged = JSONArray()
        val previous = payload.optJSONArray("items") ?: JSONArray()
        val incoming = page.optJSONArray("items") ?: JSONArray()
        for (i in 0 until previous.length()) merged.put(previous.get(i))
        for (i in 0 until incoming.length()) merged.put(incoming.get(i))
        payload.put("items", merged)
        nextPageToken = page.optString("nextPageToken", "")
        nextOffset = page.optInt("nextOffset", nextOffset + incoming.length())
        hasMore = page.optBoolean("hasMore", false)
      } catch (ex: Exception) { error = ex.message ?: "No se pudieron cargar más registros." }
      finally { loading = false }
    }
  }

  fun openOrderContext(record: Map<String, Any?>) {
    val orderId = record["id"]?.toString().orEmpty()
    val storeId = record["storeId"]?.toString().orEmpty()
    showOrderContext = true
    orderContext = null
    orderContextError = ""
    if (orderId.isBlank() || storeId.isBlank()) {
      orderContextError = "El pedido no incluye una referencia válida de comercio."
      return
    }
    val current = auth.currentUser ?: return
    scope.launch {
      orderContextLoading = true
      try {
        val token = current.getIdToken(true).await().token
          ?: throw IllegalStateException("No se pudo renovar la sesión.")
        orderContext = AdminApi.call(
          token,
          "orderContext",
          JSONObject().put("orderId", orderId).put("storeId", storeId)
        )
      } catch (ex: Exception) {
        orderContextError = ex.message ?: "No se pudo cargar la trazabilidad del pedido."
      } finally {
        orderContextLoading = false
      }
    }
  }

  LaunchedEffect(user?.uid, selected) { if (user != null) refresh() }

  if (user == null) {
    AdminLogin(
      onLogin = { email, password ->
        scope.launch {
          loading = true; error = ""
          try { user = auth.signInWithEmailAndPassword(email.trim(), password).await().user }
          catch (ex: Exception) { error = authMessage(ex) }
          finally { loading = false }
        }
      },
      onReset = { email ->
        scope.launch {
          try {
            auth.sendPasswordResetEmail(email.trim()).await()
            error = "Si el correo está registrado, recibirás un enlace para restablecer la contraseña."
          } catch (ex: Exception) { error = authMessage(ex) }
        }
      }, loading = loading, error = error
    )
  } else {
    AdminHome(
      email = user?.email ?: "Administrador",
      selected = selected,
      payload = payload,
      loading = loading,
      error = error,
      onSelect = { selected = it },
      onRefresh = ::refresh,
      onLoadMore = ::loadMore,
      hasMore = hasMore,
      issuedClaimCode = issuedClaimCode,
      issuedClaimStore = issuedClaimStore,
      onIssueDemoCode = ::issueDemoCode,
      onClearIssuedCode = { issuedClaimCode = ""; issuedClaimStore = "" },
      showOrderContext = showOrderContext,
      orderContext = orderContext,
      orderContextLoading = orderContextLoading,
      orderContextError = orderContextError,
      onOpenOrderContext = ::openOrderContext,
      onCloseOrderContext = {
        showOrderContext = false
        orderContext = null
        orderContextError = ""
      },
      onSignOut = {
        auth.signOut()
        user = null
        payload = JSONObject()
        error = ""
        selected = "dashboard"
        showOrderContext = false
        orderContext = null
        orderContextError = ""
      }
    )
  }
}

@Composable
private fun AdminLogin(onLogin: (String, String) -> Unit, onReset: (String) -> Unit, loading: Boolean, error: String) {
  var email by remember { mutableStateOf("") }
  var password by remember { mutableStateOf("") }
  var resetMode by remember { mutableStateOf(false) }
  Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF071426), Navy, Color(0xFF030711)))).padding(24.dp), contentAlignment = Alignment.Center) {
    Column(Modifier.fillMaxWidth().widthIn(max = 440.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { Box(Modifier.size(46.dp).clip(RoundedCornerShape(15.dp)).background(Brush.linearGradient(listOf(Color(0xFF00E5FF), Color(0xFF1268D8)))), contentAlignment = Alignment.Center) { Text("O", color = Navy, fontSize = 30.sp, fontWeight = FontWeight.Black) }; Column { Text("OMNI", color = White, fontSize = 18.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp); Text("ADMIN CONTROL", color = Mint, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp) } }
      Text("Tu ecosistema, bajo control", color = White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
      Text("Centro de control y administración", color = Muted, fontSize = 13.sp)
      Spacer(Modifier.height(30.dp))
      Card(modifier = Modifier.border(1.dp, Color(0xFF164B68), RoundedCornerShape(24.dp)), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(24.dp), elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)) {
        Column(Modifier.fillMaxWidth().padding(22.dp)) {
          Text(if (resetMode) "Recuperar acceso" else "Acceso administrativo", color = White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
          Spacer(Modifier.height(18.dp))
          OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text("Correo electrónico") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
          if (!resetMode) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Contraseña") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
          }
          Spacer(Modifier.height(18.dp))
          Button(onClick = { if (resetMode) onReset(email) else onLogin(email, password) }, modifier = Modifier.fillMaxWidth().height(52.dp), enabled = !loading && email.contains("@") && (resetMode || password.isNotBlank())) {
            if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text(if (resetMode) "Enviar enlace" else "Ingresar", fontWeight = FontWeight.Bold)
          }
          TextButton(onClick = { resetMode = !resetMode }) { Text(if (resetMode) "Volver al inicio de sesión" else "Olvidé mi contraseña", color = Mint) }
          if (error.isNotBlank()) Text(error, color = Danger, fontSize = 13.sp)
        }
      }
      Spacer(Modifier.height(18.dp))
      Text("Acceso privado. Solo las cuentas habilitadas por el propietario pueden ingresar.", color = Muted, fontSize = 12.sp)
    }
  }
}

@Composable
private fun AdminHome(
  email: String, selected: String, payload: JSONObject, loading: Boolean, error: String,
  onSelect: (String) -> Unit, onRefresh: () -> Unit, onLoadMore: () -> Unit, hasMore: Boolean,
  issuedClaimCode: String, issuedClaimStore: String, onIssueDemoCode: (String, String) -> Unit,
  onClearIssuedCode: () -> Unit,
  showOrderContext: Boolean, orderContext: JSONObject?, orderContextLoading: Boolean,
  orderContextError: String, onOpenOrderContext: (Map<String, Any?>) -> Unit,
  onCloseOrderContext: () -> Unit, onSignOut: () -> Unit
) {
  val activeGroup = navGroups.firstOrNull { selected in it.tabIds } ?: navGroups.first()
  val activeTab = tabs.firstOrNull { it.id == selected }
  Scaffold(
    containerColor = Navy,
    topBar = {
      Column(
        Modifier.fillMaxWidth()
          .background(Brush.verticalGradient(listOf(Color(0xFF101D35), Color(0xFF070D1B))))
          .border(width = 1.dp, color = Color(0xFF164B68))
          .padding(horizontal = 14.dp, vertical = 11.dp)
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Box(
            Modifier.size(42.dp).clip(RoundedCornerShape(14.dp))
              .background(Brush.linearGradient(listOf(Color(0xFF00E5FF), Color(0xFF1768D8), Color(0xFF7048D8))))
              .border(1.dp, Color(0xFF58F0FF), RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
          ) {
            Icon(Icons.Default.Public, null, tint = White, modifier = Modifier.size(25.dp))
          }
          Spacer(Modifier.width(11.dp))
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
              Text("OMNI ADMIN", color = White, fontSize = 15.sp, fontWeight = FontWeight.Black, letterSpacing = 1.6.sp)
              Box(Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xFF073B4A)).border(1.dp, Mint.copy(alpha = .7f), RoundedCornerShape(20.dp)).padding(horizontal = 7.dp, vertical = 3.dp)) {
                Text("CONTROL CENTER", color = Mint, fontSize = 8.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = .7.sp)
              }
            }
            Text(activeTab?.label ?: "Panel ejecutivo", color = Color(0xFF9CB1CA), fontSize = 11.sp)
          }
          IconButton(onClick = onRefresh) {
            Icon(Icons.Default.Refresh, "Actualizar datos", tint = Mint)
          }
          IconButton(onClick = onSignOut) {
            Icon(Icons.Default.Logout, "Cerrar sesión", tint = Color(0xFF9CB1CA))
          }
        }
        Spacer(Modifier.height(9.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
          Box(Modifier.size(7.dp).background(Color(0xFF32E7A0), RoundedCornerShape(50)))
          Spacer(Modifier.width(7.dp))
          Text(email, color = Color(0xFF9CB1CA), fontSize = 10.sp, maxLines = 1)
          Spacer(Modifier.weight(1f))
          Text("SESIÓN ADMINISTRATIVA", color = Color(0xFF6F88A7), fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = .8.sp)
        }
        val groupTabs = tabs.filter { it.id in activeGroup.tabIds }
        if (groupTabs.size > 1) {
          Spacer(Modifier.height(11.dp))
          Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            groupTabs.forEach { tab ->
              FilterChip(
                selected = selected == tab.id,
                onClick = { onSelect(tab.id) },
                label = { Text(tab.label, maxLines = 1, fontSize = 10.sp, fontWeight = FontWeight.SemiBold) },
                colors = FilterChipDefaults.filterChipColors(
                  selectedContainerColor = Color(0xFF063D50),
                  selectedLabelColor = Mint,
                  labelColor = Color(0xFF9CB1CA),
                  containerColor = Color(0xFF0B1426)
                ),
                border = FilterChipDefaults.filterChipBorder(
                  enabled = true, selected = selected == tab.id,
                  borderColor = Color(0xFF263B59), selectedBorderColor = Mint
                )
              )
            }
          }
        }
      }
    },
    bottomBar = {
      NavigationBar(
        containerColor = Color(0xFF080F1F),
        contentColor = Mint,
        tonalElevation = 0.dp,
        modifier = Modifier.border(width = 1.dp, color = Color(0xFF18314C))
      ) {
        navGroups.forEach { group ->
          NavigationBarItem(
            selected = activeGroup.id == group.id,
            onClick = { onSelect(group.tabIds.first()) },
            icon = { Icon(group.icon, contentDescription = group.label) },
            label = { Text(group.label, fontSize = 9.sp, maxLines = 1, fontWeight = FontWeight.SemiBold) },
            colors = NavigationBarItemDefaults.colors(
              selectedIconColor = Mint,
              selectedTextColor = Mint,
              indicatorColor = Color(0xFF073B4A),
              unselectedIconColor = Color(0xFF7186A3),
              unselectedTextColor = Color(0xFF7186A3)
            )
          )
        }
      }
    }
  ) { padding ->
    Column(Modifier.fillMaxSize().background(Navy).padding(padding)) {
      if (loading) LinearProgressIndicator(
        Modifier.fillMaxWidth(),
        color = Mint,
        trackColor = Color(0xFF14243B)
      )
      if (error.isNotBlank()) {
        Text(
          error,
          Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
          color = if (error.contains("correctamente") || error.contains("registrada") || error.contains("actualizado")) Color(0xFF32E7A0) else Danger,
          fontSize = 12.sp
        )
      }
      when (selected) {
        "dashboard" -> DashboardContent(payload, onSelect, onRefresh)
        "liveMap" -> LiveMapScreen(payload, loading, error, onRefresh)
        "finance" -> FinanceContent(payload)
        "demoClaims" -> DemoMerchantClaimsContent(payload, issuedClaimCode, issuedClaimStore, onIssueDemoCode, onClearIssuedCode)
        "modules" -> ModuleContent(payload.optJSONArray("items") ?: JSONArray())
        "settings" -> ConfigContent(payload.optJSONObject("config") ?: JSONObject())
        "invitations" -> InvitationContent(payload.optJSONArray("items") ?: JSONArray())
        else -> RecordsContent(
          selected, payload.optJSONArray("items") ?: JSONArray(), hasMore, onLoadMore,
          onOpenOrderContext = if (selected == "orders") onOpenOrderContext else null
        )
      }
      if (showOrderContext) {
        OrderContextDialog(
          payload = orderContext,
          loading = orderContextLoading,
          error = orderContextError,
          onClose = onCloseOrderContext
        )
      }
    }
  }
}

@Composable
private fun DashboardContent(payload: JSONObject, onSelect: (String) -> Unit, onRefresh: () -> Unit) {
  val counts = payload.optJSONObject("counts") ?: JSONObject()
  val analytics = payload.optJSONObject("analytics") ?: JSONObject()
  val diagnostics = payload.optJSONObject("diagnostics") ?: JSONObject()
  val recentActivity = jsonArrayToList(payload.optJSONArray("recentUsageEvents"))
  val recentOperations = jsonArrayToList(payload.optJSONArray("recentOperations"))
  val shortcuts = listOf(
    Triple("Usuarios", "users", Icons.Default.PeopleAlt), Triple("OMNI Pay", "wallets", Icons.Default.AccountBalanceWallet),
    Triple("Comercios", "stores", Icons.Default.Storefront), Triple("Pedidos", "orders", Icons.Default.ShoppingBag),
    Triple("Eventos", "events", Icons.Default.Event), Triple("Momentos", "moments", Icons.Default.Cake),
    Triple("Bolsa de trabajo", "conexPosts", Icons.Default.Work), Triple("Perfiles", "conexProfiles", Icons.Default.Badge),
    Triple("Opiniones", "feedback", Icons.Default.Forum), Triple("Estadísticas", "activity", Icons.Default.QueryStats),
    Triple("Movimientos", "operations", Icons.Default.SwapHoriz), Triple("Auditoría", "audit", Icons.Default.FactCheck),
    Triple("Módulos", "modules", Icons.Default.Extension), Triple("Finanzas DEMO", "finance", Icons.Default.Savings),
    Triple("Accesos", "invitations", Icons.Default.VpnKey), Triple("Configuración", "settings", Icons.Default.Settings)
  )
  val accents = listOf(Color(0xFF00DFFC), Color(0xFF28DFA2), Color(0xFF9B78FF), Color(0xFFFFB547), Color(0xFFFF5C9A))
  val systemReady = diagnostics.optBoolean("diagnosticEventSeen")
  LazyColumn(
    Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF071426), Color(0xFF050B17), Color(0xFF030711)))).padding(horizontal = 14.dp),
    contentPadding = PaddingValues(top = 15.dp, bottom = 30.dp), verticalArrangement = Arrangement.spacedBy(14.dp)
  ) {
    item {
      Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Brush.linearGradient(listOf(Color(0xFF102B49), Color(0xFF0A172B), Color(0xFF17152F)))).border(1.dp, Color(0xFF167A9A), RoundedCornerShape(22.dp)).padding(17.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("OMNI / COMMAND", color = Mint, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            Text("Centro de operaciones", color = White, fontSize = 23.sp, fontWeight = FontWeight.Black)
            Text("Visión general del ecosistema", color = Color(0xFF9CB1CA), fontSize = 10.sp)
          }
          Box(Modifier.size(48.dp).clip(RoundedCornerShape(15.dp)).background(Brush.linearGradient(listOf(Color(0xFF00DFFC), Color(0xFF315DEB), Color(0xFF8656E8)))).border(1.dp, Color(0xFF7BEFFF), RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Public, null, tint = White, modifier = Modifier.size(27.dp))
          }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
          Box(Modifier.size(7.dp).background(if (systemReady) Color(0xFF32E7A0) else Color(0xFFFFB547), RoundedCornerShape(50)))
          Spacer(Modifier.width(7.dp))
          Text(if (systemReady) "SISTEMA CONECTADO" else "DIAGNÓSTICO PENDIENTE", color = if (systemReady) Color(0xFF65E9B7) else Color(0xFFFFC66D), fontSize = 8.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
          Spacer(Modifier.weight(1f))
          Text("PANEL PRIVADO", color = Color(0xFF7188A6), fontSize = 8.sp, fontWeight = FontWeight.Bold)
        }
      }
    }
    item {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text("INDICADORES PRINCIPALES", color = White, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp); Spacer(Modifier.weight(1f)); Text("DATOS REGISTRADOS", color = Color(0xFF7188A6), fontSize = 8.sp, fontWeight = FontWeight.Bold) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          ExecutiveMetric("USUARIOS", counts.optInt("users").toString(), Icons.Default.PeopleAlt, Color(0xFF00DFFC), Modifier.weight(1f))
          ExecutiveMetric("CUENTAS OMNI PAY", counts.optInt("walletAccounts").toString(), Icons.Default.AccountBalanceWallet, Color(0xFF28DFA2), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          ExecutiveMetric("PEDIDOS", counts.optInt("storeOrders").toString(), Icons.Default.ShoppingBag, Color(0xFF9B78FF), Modifier.weight(1f))
          ExecutiveMetric("ACTIVOS · 7 DÍAS", payload.optInt("activeUsers7d").toString(), Icons.Default.Bolt, Color(0xFFFFB547), Modifier.weight(1f))
        }
      }
    }
    item {
      Card(Modifier.fillMaxWidth().clickable { onSelect("liveMap") }.border(1.dp, Color(0xFF00DFFC), RoundedCornerShape(20.dp)), colors = CardDefaults.cardColors(containerColor = Color(0xFF091A2D)), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF103D55), Color(0xFF0A1B30), Color(0xFF11152D)))).padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text("GEOACTIVITY", color = Mint, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.8.sp); Spacer(Modifier.weight(1f))
            Text("VISOR GEOGRÁFICO", color = Color(0xFF65E9B7), fontSize = 7.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xFF073B35)).border(1.dp, Color(0xFF1B9E79), RoundedCornerShape(20.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
          }
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(54.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF073D52)).border(1.dp, Mint.copy(alpha = .75f), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Public, null, tint = Mint, modifier = Modifier.size(30.dp)) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
              Text("Actividad geográfica OMNI", color = White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
              Text("Local · Santa Fe · Argentina", color = Color(0xFF9CB1CA), fontSize = 9.sp)
              Text("Abrir mapa y panel territorial", color = Mint, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
            Icon(Icons.Default.ArrowForward, null, tint = Mint, modifier = Modifier.size(22.dp))
          }
        }
      }
    }
    item {
      Row(verticalAlignment = Alignment.CenterVertically) { Text("MÓDULOS DEL ECOSISTEMA", color = White, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = .9.sp); Spacer(Modifier.weight(1f)); Text("16 ACCESOS", color = Color(0xFF7188A6), fontSize = 8.sp, fontWeight = FontWeight.Bold) }
    }
    items(shortcuts.chunked(2)) { rowItems ->
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        rowItems.forEach { shortcut ->
          val index = shortcuts.indexOf(shortcut); val accent = accents[index % accents.size]
          Card(Modifier.weight(1f).height(78.dp).clickable { onSelect(shortcut.second) }.border(1.dp, accent.copy(alpha = .55f), RoundedCornerShape(15.dp)), colors = CardDefaults.cardColors(containerColor = Color(0xFF0A1528)), shape = RoundedCornerShape(15.dp)) {
            Row(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(accent.copy(alpha = .13f), Color(0xFF091426)))).padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
              Box(Modifier.size(35.dp).clip(RoundedCornerShape(11.dp)).background(accent.copy(alpha = .13f)).border(1.dp, accent.copy(alpha = .6f), RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) { Icon(shortcut.third, null, tint = accent, modifier = Modifier.size(19.dp)) }
              Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) { Text(shortcut.first, color = White, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 2, lineHeight = 12.sp); Text("INGRESAR", color = Color(0xFF7188A6), fontSize = 7.sp, fontWeight = FontWeight.Bold, letterSpacing = .5.sp) }
              Icon(Icons.Default.ChevronRight, null, tint = accent, modifier = Modifier.size(15.dp))
            }
          }
        }
        if (rowItems.size == 1) Spacer(Modifier.weight(1f))
      }
    }
    item {
      Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text("MONITOREO DE ACTIVIDAD", color = White, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = .9.sp); Spacer(Modifier.weight(1f)); Text("ÚLTIMOS REGISTROS", color = Color(0xFF7188A6), fontSize = 8.sp, fontWeight = FontWeight.Bold) }
        Card(Modifier.fillMaxWidth().border(1.dp, Color(0xFF263C5A), RoundedCornerShape(17.dp)), colors = CardDefaults.cardColors(containerColor = Color(0xFF091426)), shape = RoundedCornerShape(17.dp)) {
          Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            val recent = (recentActivity + recentOperations).take(6)
            if (recent.isEmpty()) Text("Todavía no hay actividad reciente disponible.", color = Color(0xFF91A7C1), fontSize = 11.sp)
            else recent.forEachIndexed { index, record ->
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Box(Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).background(accents[index % accents.size].copy(alpha = .13f)), contentAlignment = Alignment.Center) { Icon(if (index % 2 == 0) Icons.Default.Bolt else Icons.Default.ReceiptLong, null, tint = accents[index % accents.size], modifier = Modifier.size(16.dp)) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                  Text(firstPresentValue(record, "event", "eventName", "kind", "type", "action").ifBlank { "Tipo de evento no informado" }.let { friendlyEnum(it) }, color = White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                  Text(firstPresentValue(record, "userLabel", "storeName", "email", "module").ifBlank { "Módulo o usuario no informado" }, color = Color(0xFF879DB8), fontSize = 9.sp, maxLines = 1)
                }
                Text(firstPresentValue(record, "createdAt", "timestamp").take(16), color = Color(0xFF7188A6), fontSize = 8.sp, maxLines = 1)
              }
              if (index < recent.lastIndex) HorizontalDivider(color = Color(0xFF1A2B45))
            }
          }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          CompactDashboardStat("Sesiones · 7 días", analytics.optInt("sessions7d").toString(), Icons.Default.QueryStats, Modifier.weight(1f))
          CompactDashboardStat("Vistas de pantalla", analytics.optInt("screenViews7d").toString(), Icons.Default.Visibility, Modifier.weight(1f))
        }
      }
    }
  }
}

@Composable
private fun ExecutiveMetric(
  label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, accent: Color, modifier: Modifier = Modifier
) {
  Card(
    modifier.border(1.dp, accent.copy(alpha = .48f), RoundedCornerShape(14.dp)),
    colors = CardDefaults.cardColors(containerColor = Color(0xFF0B172B)),
    shape = RoundedCornerShape(14.dp)
  ) {
    Row(
      Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(accent.copy(alpha = .14f), Color(0xFF0B172B))))
        .padding(horizontal = 11.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
      Icon(icon, null, tint = accent, modifier = Modifier.size(22.dp))
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, color = White, fontSize = 20.sp, fontWeight = FontWeight.Black, maxLines = 1)
        Text(label, color = Color(0xFF8EA3BF), fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1)
      }
    }
  }
}

@Composable
private fun CompactDashboardStat(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
  Card(modifier, colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(12.dp)) {
    Row(Modifier.fillMaxWidth().padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
      Icon(icon, null, tint = Mint, modifier = Modifier.size(19.dp))
      Spacer(Modifier.width(7.dp))
      Column {
        Text(value, color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Muted, fontSize = 8.sp)
      }
    }
  }
}

@Composable
private fun ActivityMapDialog(points: JSONArray, summary: JSONObject, onRefresh: () -> Unit, onDismiss: () -> Unit) {
  var selectedScope by remember { mutableStateOf("Zona local") }
  val geoPoints = remember(points.toString()) {
    (0 until points.length()).mapNotNull { index ->
      val item = points.optJSONObject(index) ?: return@mapNotNull null
      val latitude = item.optDouble("latitude", Double.NaN)
      val longitude = item.optDouble("longitude", Double.NaN)
      val count = item.optInt("count", 0)
      if (!latitude.isFinite() || !longitude.isFinite() || count < 3) null
      else Triple(latitude, longitude, count)
    }
  }
  val bounds = when (selectedScope) {
    "Santa Fe" -> doubleArrayOf(-32.5, -29.0, -62.5, -59.4)
    "Argentina" -> doubleArrayOf(-56.0, -21.5, -73.5, -53.5)
    else -> doubleArrayOf(-31.9, -31.3, -61.0, -60.4)
  }
  val visiblePoints = geoPoints.filter { (lat, lon, _) ->
    lat in bounds[0]..bounds[1] && lon in bounds[2]..bounds[3]
  }

  Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    Box(Modifier.fillMaxSize().background(Color(0xFF050C18)).padding(8.dp)) {
      Column(
        Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp))
          .border(1.dp, Color(0xFF168AC5), RoundedCornerShape(18.dp))
          .background(Color(0xFF071321)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Default.Map, null, tint = Mint, modifier = Modifier.size(24.dp))
          Spacer(Modifier.width(8.dp))
          Column(Modifier.weight(1f)) {
            Text("OMNI GEOACTIVITY", color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text("Actividad agrupada · ubicación aproximada", color = Muted, fontSize = 10.sp)
          }
          IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Actualizar actividad", tint = Mint) }
          IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Cerrar mapa", tint = Mint) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          listOf("Zona local", "Santa Fe", "Argentina").forEach { level ->
            FilterChip(
              selected = selectedScope == level,
              onClick = { selectedScope = level },
              label = { Text(level, fontSize = 11.sp) },
              colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = Mint, selectedLabelColor = Navy,
                labelColor = Muted, containerColor = PanelSoft
              )
            )
          }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          CompactDashboardStat("Cuentas activas 24 h", summary.optInt("consentedUsersFresh24h").toString(), Icons.Default.PeopleAlt, Modifier.weight(1f))
          CompactDashboardStat("Áreas visibles", summary.optInt("visibleAreas").toString(), Icons.Default.MyLocation, Modifier.weight(1f))
        }
        Box(
          Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(13.dp))
            .background(Color(0xFF06101E))
            .border(1.dp, Color(0xFF12658C), RoundedCornerShape(13.dp))
        ) {
          Canvas(Modifier.fillMaxSize()) {
            val gridColor = Mint.copy(alpha = 0.12f)
            val stepX = size.width / 8f
            val stepY = size.height / 8f
            for (i in 1..7) {
              drawLine(gridColor, Offset(stepX * i, 0f), Offset(stepX * i, size.height), 1.dp.toPx())
              drawLine(gridColor, Offset(0f, stepY * i), Offset(size.width, stepY * i), 1.dp.toPx())
            }
            drawLine(Color(0xFF167A9A).copy(alpha = .5f), Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), 1.dp.toPx())
            drawLine(Color(0xFF167A9A).copy(alpha = .5f), Offset(size.width / 2f, 0f), Offset(size.width / 2f, size.height), 1.dp.toPx())
            visiblePoints.forEach { (lat, lon, count) ->
              val x = ((lon - bounds[2]) / (bounds[3] - bounds[2])).toFloat() * size.width
              val y = (1.0 - (lat - bounds[0]) / (bounds[1] - bounds[0])).toFloat() * size.height
              val radius = (7f + count.coerceAtMost(20) * .45f).dp.toPx()
              drawCircle(Color(0xFF00E5FF).copy(alpha = .14f), radius * 2.2f, Offset(x, y))
              drawCircle(Color(0xFF00E5FF).copy(alpha = .85f), radius, Offset(x, y))
              drawCircle(Color.White, 2.5.dp.toPx(), Offset(x, y))
            }
          }
          Column(Modifier.align(Alignment.TopStart).padding(12.dp)) {
            Text(selectedScope.uppercase(), color = Mint, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.2.sp)
            Text("${visiblePoints.size} grupos que cumplen el umbral de privacidad", color = Muted, fontSize = 9.sp)
          }
          if (visiblePoints.isEmpty()) {
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
              Icon(Icons.Default.Radar, null, tint = Color(0xFF3B7792), modifier = Modifier.size(42.dp))
              Text("Todavía no hay grupos visibles en esta zona", color = White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
              Text("Se muestran áreas únicamente cuando al menos ${summary.optInt("privacyThreshold", 3)} cuentas comparten ubicación aproximada y autorizada.", color = Muted, fontSize = 10.sp)
            }
          }
        }
        Text(
          "Vista esquemática por coordenadas aproximadas; no representa calles ni rutas. Los puntos solo aparecen con consentimiento vigente y un mínimo de ${summary.optInt("privacyThreshold", 3)} cuentas por área.",
          color = Muted, fontSize = 9.sp, lineHeight = 13.sp
        )
      }
    }
  }
}
@Composable
private fun FinanceContent(payload: JSONObject) {
  val summary = payload.optJSONObject("summary") ?: JSONObject()
  val accounts = jsonArrayToList(payload.optJSONArray("accounts"))
  val transactions = jsonArrayToList(payload.optJSONArray("transactions"))
  val operations = jsonArrayToList(payload.optJSONArray("operations"))
  val commerce = payload.optJSONObject("commerce") ?: JSONObject()
  val commerceSampleComplete = commerce.optBoolean("sampleComplete", true)
  val accountCount = summary.optInt("accountCount", accounts.size)
  val transactionCount = summary.optInt("transactionCount", transactions.size)
  val operationCount = summary.optInt("operationCount", operations.size)
  val accountSampleComplete = summary.optBoolean("accountSampleComplete", true)
  val transactionSampleComplete = summary.optBoolean("transactionSampleComplete", transactions.size < 100)
  val operationSampleComplete = summary.optBoolean("operationsSampleComplete", operations.size < 100)
  fun money(key: String): String = "OM$ " + String.format("%.2f", summary.optDouble(key))
  LazyColumn(
    Modifier.fillMaxSize().background(Navy).padding(horizontal = 15.dp),
    contentPadding = PaddingValues(vertical = 18.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    item {
      Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
          .background(Brush.linearGradient(listOf(Color(0xFF123A5D), Color(0xFF101B35), Color(0xFF0A1426))))
          .border(1.dp, Color(0xFF1679A6), RoundedCornerShape(22.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        Text("OMNI PAY · CONTROL FINANCIERO DEMO", color = Mint, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.2.sp)
        Text(money("currentBalance"), color = White, fontSize = 27.sp, fontWeight = FontWeight.ExtraBold)
        Text(if (accountSampleComplete) "Capital virtual DEMO distribuido entre las billeteras" else "Capital virtual DEMO · muestra parcial de cuentas", color = Muted, fontSize = 12.sp)
        Text("$accountCount cuentas registradas · $transactionCount movimientos individuales", color = Mint, fontSize = 11.sp)
        Text("Cuentas sumadas: ${summary.optInt("accountSampleSize", accountCount)} · Operaciones analizadas: ${summary.optInt("operationsSampleSize", 0)}${if (operationSampleComplete) " · muestra completa" else " · muestra limitada"}", color = Muted, fontSize = 9.sp)
      }
    }
    item { Text("Capital y circulación", color = White, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
    item {
      Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        FinanceMetric("Capital inicial DEMO", money("initialCapital"), Icons.Default.Savings, Modifier.weight(1f))
        FinanceMetric(if (operationSampleComplete) "Recargas DEMO" else "Recargas · muestra", money("deposits"), Icons.Default.AddCard, Modifier.weight(1f))
      }
    }
    item {
      Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        FinanceMetric(if (operationSampleComplete) "Transferencias" else "Transferencias · muestra", money("transferVolume"), Icons.Default.SwapHoriz, Modifier.weight(1f))
        FinanceMetric(if (operationSampleComplete) "Pagos QR" else "Pagos QR · muestra", money("qrVolume"), Icons.Default.QrCode2, Modifier.weight(1f))
      }
    }
    item {
      Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        FinanceMetric(if (operationSampleComplete) "Transferencias recibidas" else "Transferencias recibidas · muestra", money("transferIn"), Icons.Default.CallReceived, Modifier.weight(1f))
        FinanceMetric(if (operationSampleComplete) "Cobros QR" else "Cobros QR · muestra", money("qrCollections"), Icons.Default.QrCode2, Modifier.weight(1f))
      }
    }
    item {
      Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF1A5068), RoundedCornerShape(16.dp))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
          Text("Lectura correcta del capital", color = Mint, fontSize = 12.sp, fontWeight = FontWeight.Bold)
          Text("Créditos totales: ${money("totalCredits")}", color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
          Text("Salidas registradas: ${money("outflows")}", color = White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
          Text("Las transferencias internas y los pagos QR representan circulación del saldo, no capital nuevo. No se suman a las recargas.", color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
          Text("Datos de prueba. No representan dinero real ni fondos bancarios.", color = Color(0xFFFFC66D), fontSize = 10.sp)
        }
      }
    }
    item {
      Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
          .background(Brush.linearGradient(listOf(Color(0xFF172D4A), Color(0xFF101A31), Color(0xFF0A1426))))
          .border(1.dp, Color(0xFF8057B8), RoundedCornerShape(18.dp)).padding(15.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)
      ) {
        Text("COMERCIO DEMO · ACTIVIDAD COMERCIAL", color = Color(0xFFC3A5FF), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
        Text("${commerce.optInt("orderCount")} pedidos registrados · ${commerce.optInt("paidOrderCount")} pagos confirmados", color = White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text("Importe de pedidos actualmente pagados: OM$ ${String.format("%.2f", commerce.optDouble("paidGross"))}", color = White, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          FinanceMetric("OMNI Pay DEMO", "OM$ ${String.format("%.2f", commerce.optDouble("omniPayDemoGross"))}", Icons.Default.AccountBalanceWallet, Modifier.weight(1f))
          FinanceMetric("Tarjeta DEMO", "OM$ ${String.format("%.2f", commerce.optDouble("cardDemoGross"))}", Icons.Default.CreditCard, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          FinanceMetric("Efectivo DEMO", "OM$ ${String.format("%.2f", commerce.optDouble("cashDemoGross"))}", Icons.Default.Payments, Modifier.weight(1f))
          FinanceMetric("Devoluciones", "OM$ ${String.format("%.2f", commerce.optDouble("refundedGross"))}", Icons.Default.Replay, Modifier.weight(1f))
        }
        Text("${commerce.optInt("refundedOrderCount")} pedidos reembolsados · ${commerce.optInt("cancelledUnpaidCount")} cancelados sin cobro", color = Muted, fontSize = 10.sp)
        Text(
          if (commerceSampleComplete) "Muestra comercial completa." else "Muestra parcial: ${commerce.optInt("sampledOrders")} de ${commerce.optInt("orderCount")} pedidos. Los importes no representan el total histórico.",
          color = if (commerceSampleComplete) Color(0xFF65E9B7) else Color(0xFFFFC66D), fontSize = 10.sp
        )
        Text("El efectivo y la tarjeta DEMO se informan como ventas comerciales; no se suman al saldo de las billeteras OMNI Pay.", color = Muted, fontSize = 10.sp, lineHeight = 14.sp)
      }
    }
    item { Text("Cuentas y saldos · ${accounts.size} visibles de $accountCount", color = White, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
    if (accounts.isEmpty()) item { EmptyState("Todavía no hay billeteras registradas.") }
    else items(accounts, key = { it["id"]?.toString() ?: it.hashCode().toString() }) { RecordCard(it, "walletAccounts") }
    item { Text("Historial de movimientos · ${transactions.size} visibles de $transactionCount", color = White, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
    if (transactions.isEmpty()) item { EmptyState("Todavía no hay movimientos individuales registrados.") }
    else items(transactions, key = { it["id"]?.toString() ?: it.hashCode().toString() }) { RecordCard(it, "walletTransactions") }
    item { Text("Operaciones globales · ${operations.size} visibles de $operationCount${if (operationSampleComplete) "" else " · muestra limitada"}", color = White, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
    if (operations.isEmpty()) item { EmptyState("Las operaciones se reflejan en el historial individual de cada billetera. La colección global todavía no contiene registros.") }
    else items(operations.take(100), key = { it["id"]?.toString() ?: it.hashCode().toString() }) { RecordCard(it, "walletOperations") }
  }
}

@Composable
private fun FinanceMetric(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
  Card(modifier, colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1830)), shape = RoundedCornerShape(15.dp)) {
    Column(Modifier.fillMaxWidth().padding(9.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
      Icon(icon, null, tint = Mint, modifier = Modifier.size(17.dp))
      Text(value, color = White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
      Text(label, color = Muted, fontSize = 10.sp, maxLines = 2)
    }
  }
}

@Composable
private fun DemoMerchantClaimsContent(payload: JSONObject, issuedCode: String, issuedStore: String, onIssue: (String, String) -> Unit, onClearCode: () -> Unit) {
  val stores = jsonArrayToList(payload.optJSONArray("items"))
  LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
    item {
      Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Brush.linearGradient(listOf(Color(0xFF123A5D), Panel, Color(0xFF0A1426)))).border(1.dp, Color(0xFF1679A6), RoundedCornerShape(20.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("ASIGNACIÓN CONTROLADA", color = Mint, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.5.sp)
        Text("Titularidad de comercios DEMO", color = White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        Text("Emití un código para un comercio de prueba sin titular. Entregalo únicamente al usuario elegido. Cada código vence en 30 días y se consume una sola vez.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        Text("${stores.size} comercios DEMO provisionados", color = Mint, fontSize = 11.sp, fontWeight = FontWeight.Bold)
      }
    }
    if (issuedCode.isNotBlank()) item {
      Card(Modifier.fillMaxWidth().border(1.dp, Color(0xFF32E7A0), RoundedCornerShape(16.dp)), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("CÓDIGO EMITIDO · $issuedStore", color = Color(0xFF32E7A0), fontWeight = FontWeight.ExtraBold, fontSize = 11.sp)
          SelectionContainer { Text(issuedCode, color = White, fontSize = 21.sp, fontWeight = FontWeight.Black, letterSpacing = 1.4.sp) }
          Text("Copialo ahora y compartilo con el titular asignado. Por seguridad no se vuelve a mostrar.", color = Muted, fontSize = 11.sp)
          TextButton(onClick = onClearCode) { Text("Ocultar código", color = Mint) }
        }
      }
    }
    if (stores.isEmpty()) item { EmptyState("No hay comercios DEMO provisionados. Primero debe ejecutarse la carga inicial autorizada.") }
    items(stores) { store ->
      val id = store["id"]?.toString().orEmpty()
      val name = store["storeName"]?.toString() ?: "Comercio DEMO"
      val tier = store["sizeTier"]?.toString().orEmpty()
      val capacity = store["capacityLabel"]?.toString().orEmpty()
      val claimed = store["claimed"] == true
      Card(Modifier.fillMaxWidth().border(1.dp, if (claimed) Color(0xFF34445B) else Color(0xFF1A6078), RoundedCornerShape(15.dp)), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(15.dp)) {
        Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(name, color = White, fontSize = 14.sp, fontWeight = FontWeight.Bold); Text(listOf(tier, capacity).filter { it.isNotBlank() }.joinToString(" · "), color = Muted, fontSize = 10.sp) }
            Text(if (claimed) "ASIGNADO" else "DISPONIBLE", color = if (claimed) Muted else Color(0xFF32E7A0), fontSize = 9.sp, fontWeight = FontWeight.ExtraBold)
          }
          Text("${store["productCount"] ?: 0} productos · ${if (claimed) "Titular vinculado" else "Sin titular asignado"}", color = Muted, fontSize = 10.sp)
          if (!claimed && id.isNotBlank()) Button(onClick = { onIssue(id, name) }, modifier = Modifier.fillMaxWidth()) { Text("Emitir código de titularidad", fontSize = 11.sp) }
        }
      }
    }
  }
}
@Composable
private fun RecordsContent(
  selected: String,
  array: JSONArray,
  hasMore: Boolean,
  onLoadMore: () -> Unit,
  onOpenOrderContext: ((Map<String, Any?>) -> Unit)? = null
) {
  val records = jsonArrayToList(array)
  var search by remember(selected) { mutableStateOf("") }
  val filtered = remember(records, search) {
    if (search.isBlank()) records else records.filter { record ->
      record.entries.any { (key, value) ->
        key !in setOf("password", "token", "accessToken", "refreshToken", "secret", "apiKey", "rawData", "authorization") &&
          displayValue(value).contains(search.trim(), ignoreCase = true)
      }
    }
  }
  LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    item {
      Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
          .background(Brush.linearGradient(listOf(Color(0xFF12334B), Panel, Color(0xFF0A1423))))
          .border(1.dp, Color(0xFF1A6078), RoundedCornerShape(22.dp)).padding(19.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
      ) {
        Text("SUPERVISIÓN OPERATIVA · OMNI", color = Mint, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.8.sp)
        Text(tabs.first { it.id == selected }.label, color = White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        Text("${filtered.size} de ${records.size} registros cargados · historial paginado", color = Muted, fontSize = 12.sp)
      }
    }
    item {
      OutlinedTextField(
        value = search, onValueChange = { search = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Buscar en ${tabs.first { it.id == selected }.label.lowercase()}") },
        placeholder = { Text("Nombre, correo, estado o referencia") },
        singleLine = true,
        leadingIcon = { Icon(Icons.Default.Search, null, tint = Mint) },
        trailingIcon = { if (search.isNotBlank()) IconButton(onClick = { search = "" }) { Icon(Icons.Default.Close, "Limpiar", tint = Muted) } }
      )
    }
    if (filtered.isEmpty()) item { EmptyState(if (records.isEmpty()) "Todavía no hay registros para mostrar." else "No hay coincidencias con esa búsqueda.") }
    items(filtered) { record ->
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RecordCard(record, selected)
        if (onOpenOrderContext != null) {
          TextButton(onClick = { onOpenOrderContext(record) }, modifier = Modifier.fillMaxWidth()) {
            Text("Ver trazabilidad del pedido", color = Mint, fontSize = 11.sp)
          }
        }
      }
    }
    if (hasMore && search.isBlank()) item {
      Button(onClick = onLoadMore, modifier = Modifier.fillMaxWidth()) { Text("Cargar más registros") }
    }
  }
}

@Composable
private fun OrderContextDialog(
  payload: JSONObject?,
  loading: Boolean,
  error: String,
  onClose: () -> Unit
) {
  Dialog(
    onDismissRequest = onClose,
    properties = DialogProperties(usePlatformDefaultWidth = false)
  ) {
    Surface(
      modifier = Modifier.fillMaxWidth().fillMaxHeight(0.88f).padding(16.dp),
      shape = RoundedCornerShape(20.dp),
      color = Panel
    ) {
      Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) {
            Text("Trazabilidad del pedido", color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text("Vista administrativa · solo lectura", color = Muted, fontSize = 11.sp)
          }
          IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Cerrar", tint = Muted) }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Mint)
        if (error.isNotBlank()) Text(error, color = Danger, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
        val order = payload?.optJSONObject("order")
        if (payload == null && !loading && error.isBlank()) {
          EmptyState("No se recibió el contexto del pedido.")
        } else if (order != null) {
          val movements = jsonArrayToList(payload?.optJSONArray("inventoryMovements"))
          val adjustments = jsonArrayToList(payload?.optJSONArray("receiptAdjustments"))
          val financialOperations = jsonArrayToList(payload?.optJSONArray("financialOperations"))
          val cardPayments = jsonArrayToList(payload?.optJSONArray("cardPayments"))
          val receipt = payload?.optJSONObject("receipt")
            ?.takeUnless { it == JSONObject.NULL }
            ?.let(::jsonObjectToMap)
          LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            item {
              Text("Pedido", color = Mint, fontSize = 13.sp, fontWeight = FontWeight.Bold)
              RecordCard(jsonObjectToMap(order), "orders")
            }
            item { ContextSectionTitle("Movimientos de inventario", movements.size) }
            if (movements.isEmpty()) item { EmptyState("No hay movimientos vinculados por pedido y comercio.") }
            items(movements) { RecordCard(it, "inventoryMovements") }
            item { ContextSectionTitle("Comprobante", if (receipt == null) 0 else 1) }
            if (receipt == null) item { EmptyState("No hay comprobante vinculado a este pedido.") }
            else item { RecordCard(receipt, "receipts") }
            item { ContextSectionTitle("Ajustes de comprobante", adjustments.size) }
            if (adjustments.isEmpty()) item { EmptyState("No hay ajustes vinculados a este pedido.") }
            items(adjustments) { RecordCard(it, "receiptAdjustments") }
            item { ContextSectionTitle("Operaciones financieras", financialOperations.size) }
            if (financialOperations.isEmpty()) item { EmptyState("No hay operaciones de billetera vinculadas a este pedido.") }
            items(financialOperations) { RecordCard(it, "operations") }
            item { ContextSectionTitle("Registros de tarjeta DEMO", cardPayments.size) }
            if (cardPayments.isEmpty()) item { EmptyState("No hay intentos o pagos de tarjeta vinculados a este pedido.") }
            items(cardPayments) { RecordCard(it, "demoCardPayments") }
          }
        }
      }
    }
  }
}

@Composable
private fun ContextSectionTitle(title: String, count: Int) {
  Text("$title · $count", color = Mint, fontSize = 13.sp, fontWeight = FontWeight.Bold)
}

private val technicalFields = setOf(
  "id", "uid", "ownerUid", "senderUid", "recipientUid", "customerUid", "merchantUid",
  "operationId", "storeId", "targetUid", "createdBy", "updatedBy", "photoUrl", "providerIds",
  "qrNonce", "lastOperationId", "cvu", "reference", "rawData"
)
private val fieldLabels = mapOf(
  "displayName" to "Nombre", "fullName" to "Nombre", "name" to "Nombre",
  "email" to "Correo", "phoneNumber" to "Teléfono", "phone" to "Teléfono",
  "alias" to "Alias secundario", "holderName" to "Titular", "ownerName" to "Titular", "balance" to "Saldo", "cashbackTotal" to "Cashback acumulado",
  "amount" to "Importe", "peerName" to "Contraparte", "title" to "Concepto", "senderUid" to "Cuenta de origen", "recipientUid" to "Cuenta de destino", "kind" to "Tipo", "type" to "Tipo", "status" to "Estado",
  "storeName" to "Comercio", "orderNumber" to "Número de pedido", "receiptNumber" to "Número de comprobante", "receiptId" to "Identificador de comprobante", "originalReceiptNumber" to "Comprobante original", "fiscalValidity" to "Validez fiscal", "validationStatus" to "Estado de validación", "customerName" to "Comprador", "subtotal" to "Subtotal", "deliveryFee" to "Envío", "issuedAt" to "Fecha de emisión", "environment" to "Entorno", "reason" to "Motivo de ajuste", "localHour" to "Hora local del dispositivo", "localDayOfWeek" to "Día local de la semana", "timezoneOffsetMinutes" to "Diferencia horaria UTC (minutos)", "localDate" to "Fecha local del dispositivo", "sessionId" to "Sesión", "total" to "Total",
  "paymentStatus" to "Estado del pago", "paymentMethod" to "Medio de pago",
  "fulfillmentType" to "Entrega", "action" to "Acción", "target" to "Destino",
  "message" to "Mensaje", "description" to "Descripción", "enabled" to "Habilitado",
  "event" to "Evento", "module" to "Módulo", "eventName" to "Evento", "screen" to "Pantalla", "appVersion" to "Versión",
  "category" to "Categoría", "profession" to "Profesión u oficio", "availability" to "Disponibilidad", "visibility" to "Visibilidad", "occasion" to "Tipo de momento", "date" to "Fecha especial", "ownerUid" to "Cuenta propietaria", "startAt" to "Inicio", "endAt" to "Finalización",
  "createdAt" to "Fecha de registro", "expiresAt" to "Vencimiento de publicación", "updatedAt" to "Última actualización", "lastSeenAt" to "Última actividad", "userLabel" to "Usuario",
  "data" to "Detalles de acción", "screenName" to "Pantalla", "eventId" to "Identificador de evento",
  "cvu" to "CVU"
)
private fun friendlyEnum(value: String): String = when (value.uppercase()) {
  "UNKNOWN" -> "Tipo de evento no informado"
  "TIPO_DE_EVENTO_NO_INFORMADO" -> "Tipo de evento no informado"
  "MODULO_NO_INFORMADO" -> "Módulo no informado"
  "PANTALLA_NO_INFORMADA" -> "Pantalla no informada"
  "UNSPECIFIED" -> "Tipo no especificado"
  "OTHER" -> "Otro tipo · requiere clasificación"
  "SESSION_START" -> "Inicio de sesión"
  "SESSION_END" -> "Fin de sesión"
  "APP_FOREGROUND" -> "Aplicación en primer plano"
  "APP_BACKGROUND" -> "Aplicación en segundo plano"
  "SCREEN_VIEW" -> "Visita de pantalla"
  "SCREEN_EXIT" -> "Salida de pantalla"
  "DEPOSIT" -> "Recarga virtual DEMO"
  "CARGA_SALDO" -> "Crédito de saldo DEMO"
  "TRANSFER" -> "Transferencia"
  "QR_PAYMENT" -> "Pago con QR"
  "COMPRA_LOCAL" -> "Compra en comercio"
  "REINTEGRO_CASHBACK" -> "Reintegro de cashback"
  "EFECTIVO_DEMO" -> "Efectivo DEMO"
  "OMNI_PAY_DEMO" -> "OMNI Pay DEMO"
  "TARJETA_DEMO" -> "Tarjeta DEMO"
  "TRANSFERENCIA_ENVIADA" -> "Transferencia enviada"
  "TRANSFERENCIA_RECIBIDA" -> "Transferencia recibida"
  "QR_PAGO" -> "Pago con QR"
  "QR_COBRO" -> "Cobro con QR"
  "RECIBIDO" -> "Recibido"
  "ACEPTADO" -> "Aceptado"
  "EN_PREPARACION" -> "En preparación"
  "LISTO" -> "Listo"
  "EN_CAMINO" -> "En camino"
  "ENTREGADO" -> "Entregado"
  "PENDIENTE" -> "Pendiente"
  "APROBADA" -> "Aprobada"
  "RECHAZADO" -> "Rechazado"
  else -> value.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
}
private fun looksLikeTechnicalId(value: String): Boolean =
  value.length >= 22 && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }

@Composable
private fun RecordCard(item: Map<String, Any?>, type: String) {
  var expanded by remember(item["id"]) { mutableStateOf(false) }
  // RecordsContent passes tab IDs; dashboard panels may pass Firestore collection names.
  // Normalize both forms so every module uses its real, module-specific fields.
  val recordType = when (type) {
    "wallets" -> "walletAccounts"
    "operations" -> "walletOperations"
    "stores" -> "merchantProfiles"
    "orders" -> "storeOrders"
    "receipts" -> "storeReceipts"
    "receiptAdjustments" -> "storeReceiptAdjustments"
    "activity" -> "usageEvents"
    "events" -> "omniEvents"
    "moments" -> "omniMoments"
    "conexPosts" -> "jobPosts"
    "conexProfiles" -> "omniProfessionalProfiles"
    "feedback" -> "appFeedback"
    "audit" -> "adminAuditLogs"
    else -> type
  }
  val title = when (recordType) {
    "users" -> firstPresentValue(item, "displayName", "fullName", "name", "email").ifBlank { "Perfil de usuario · nombre no informado" }
    "walletAccounts" -> firstPresentValue(item, "holderName", "ownerName", "fullName", "displayName", "name", "email").ifBlank { "Cuenta OMNI Pay · titular no informado" }
    "walletOperations" -> friendlyEnum(firstPresentValue(item, "operationType", "transactionType", "kind", "type", "action", "title", "description").ifBlank { "Tipo de movimiento no informado" })
    "merchantProfiles" -> firstPresentValue(item, "storeName", "name").ifBlank { "Perfil comercial · nombre no informado" }
    "storeOrders" -> "Pedido " + firstPresentValue(item, "orderNumber", "status").ifBlank { "de comercio · número no informado" }
    "storeReceipts" -> "Comprobante " + firstPresentValue(item, "receiptNumber", "orderId").ifBlank { "DEMO · referencia no informada" }
    "storeReceiptAdjustments" -> "Ajuste de comprobante · " + firstPresentValue(item, "originalReceiptNumber", "orderId").ifBlank { "referencia no informada" }
    "inventoryMovements" -> friendlyEnum(firstPresentValue(item, "type").ifBlank { "Movimiento de inventario" }) + " · " +
      firstPresentValue(item, "productName", "productId").ifBlank { "producto no informado" }
    "demoCardPayments" -> friendlyEnum(firstPresentValue(item, "status", "paymentStatus", "type").ifBlank { "Registro de tarjeta DEMO" })
    "appFeedback" -> "Comentario de usuario"
    "usageEvents" -> friendlyEnum(firstPresentValue(item, "event", "eventName").ifBlank { "Evento de uso · tipo no informado" })
    "walletTransactions" -> friendlyEnum(firstPresentValue(item, "operationType", "transactionType", "type", "kind", "action", "title", "description").ifBlank { "Tipo de movimiento no informado" })
    "omniEvents" -> firstPresentValue(item, "title", "name", "event", "eventName").ifBlank { "Evento comunitario · título no informado" }
    "omniMoments" -> friendlyEnum(firstPresentValue(item, "occasion").ifBlank { "Fecha especial · tipo no informado" }) + " · " + firstPresentValue(item, "date").ifBlank { "fecha no informada" }
    "jobPosts" -> firstPresentValue(item, "title").ifBlank { "Publicación CONEX · título no informado" }
    "omniProfessionalProfiles" -> firstPresentValue(item, "profession").ifBlank { "Perfil profesional · profesión no informada" } + " · " + firstPresentValue(item, "location").ifBlank { "zona no informada" }
    "adminAuditLogs" -> friendlyEnum(firstPresentValue(item, "action").ifBlank { "Registro administrativo · acción no informada" })
    else -> firstPresentValue(item, "title", "name", "action").ifBlank { "Registro de ${type.ifBlank { "módulo no identificado" }} · clasificación pendiente" }
  }
  val preferred = when (recordType) {
    "users" -> listOf("email", "phoneNumber", "phone", "createdAt", "lastSeenAt")
    "walletAccounts" -> listOf("alias", "email", "balance", "cashbackTotal", "createdAt")
    "walletOperations" -> listOf("amount", "status", "createdAt")
    "walletTransactions" -> listOf("amount", "status", "peerName", "createdAt")
    "merchantProfiles" -> listOf("description", "category", "address", "status", "createdAt")
    "storeOrders" -> listOf("total", "paymentStatus", "fulfillmentType", "createdAt")
    "storeReceipts" -> listOf("receiptNumber", "total", "paymentMethod", "paymentStatus", "issuedAt", "createdAt")
    "storeReceiptAdjustments" -> listOf("type", "amount", "reason", "createdAt")
    "inventoryMovements" -> listOf("quantityDelta", "stockBefore", "stockAfter", "createdAt")
    "demoCardPayments" -> listOf("amount", "status", "paymentStatus", "createdAt")
    "appFeedback" -> listOf("message", "createdAt")
    "usageEvents" -> listOf("userLabel", "module", "appVersion", "createdAt")
    "omniEvents" -> listOf("description", "category", "status", "startAt", "createdAt")
    "omniMoments" -> listOf("occasion", "date", "status", "ownerUid", "createdAt")
    "jobPosts" -> listOf("category", "location", "workType", "workSchedule", "remuneration", "status", "createdAt", "expiresAt", "ownerUid")
    "omniProfessionalProfiles" -> listOf("location", "availability", "visibility", "ownerUid", "updatedAt")
    else -> listOf("status", "createdAt", "updatedAt")
  }
  val visible = preferred.mapNotNull { key ->
    val value = item[key] ?: return@mapNotNull null
    val rendered = displayValue(value)
    if (rendered.isBlank() || looksLikeTechnicalId(rendered)) null
    else (fieldLabels[key] ?: key) + ": " + if (key == "kind" || key == "type" || key == "status" || key == "paymentStatus") friendlyEnum(rendered) else rendered
  }
  val protectedKeys = setOf("password", "token", "accessToken", "refreshToken", "secret", "apiKey", "qrNonce", "rawData", "authorization")
  val details = item.entries.filter { (key, value) -> key !in protectedKeys && value != null && displayValue(value).isNotBlank() }.sortedBy { it.key }
  val accent = when (recordType) {
    "users" -> Color(0xFF16CFFF)
    "walletAccounts", "walletOperations", "walletTransactions" -> Color(0xFF32E7A0)
    "merchantProfiles", "storeOrders" -> Color(0xFFFFB52E)
    "appFeedback", "usageEvents" -> Color(0xFFBD70FF)
    "omniEvents", "omniMoments" -> Color(0xFF16CFFF)
    "jobPosts", "omniProfessionalProfiles" -> Color(0xFFFF7DB8)
    "adminAuditLogs" -> Color(0xFFFF8A65)
    else -> Mint
  }
  Card(
    Modifier.fillMaxWidth().border(1.dp, accent.copy(alpha = 0.72f), RoundedCornerShape(14.dp)).clickable { expanded = !expanded },
    colors = CardDefaults.cardColors(containerColor = Panel),
    shape = RoundedCornerShape(14.dp),
    elevation = CardDefaults.cardElevation(defaultElevation = 5.dp)
  ) {
    Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(accent.copy(alpha = 0.15f), Panel, Color(0xFF091426)))).padding(11.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title.take(100), modifier = Modifier.weight(1f), color = White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 2)
        Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = if (expanded) "Contraer detalle" else "Ver detalle", tint = accent)
      }
      visible.forEach { line -> Spacer(Modifier.height(3.dp)); Text(line.take(180), color = Muted, fontSize = 10.sp, maxLines = 2) }
      if (expanded) {
        Spacer(Modifier.height(10.dp)); HorizontalDivider(color = PanelSoft)
        Text("Detalle del registro", color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 9.dp, bottom = 4.dp))
        details.forEach { (key, value) ->
          val rendered = displayValue(value)
          val label = fieldLabels[key] ?: key.replace(Regex("([a-z])([A-Z])"), "$1 $2").replaceFirstChar { it.uppercase() }
          Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Text(label, color = Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            Text(rendered.take(1200), color = White, fontSize = 11.sp)
          }
        }
      } else Text("Tocá para ver todos los datos disponibles", color = accent, fontSize = 10.sp, modifier = Modifier.padding(top = 7.dp))
    }
  }
}
@Composable
private fun ConfigContent(config: JSONObject) {
  val rows = listOf(
    "Modo mantenimiento" to if (config.optBoolean("maintenanceMode", false)) "ACTIVO" else "INACTIVO",
    "Mensaje de mantenimiento" to config.optString("maintenanceMessage").ifBlank { "No configurado" },
    "Aviso global" to config.optString("globalNotice").ifBlank { "No hay aviso global" },
    "Versión mínima" to config.optString("minimumVersion").ifBlank { "No informada" },
    "Versión recomendada" to config.optString("recommendedVersion").ifBlank { "No informada" }
  )
  LazyColumn(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    item {
      Text("Configuración global", color = White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
      Text("Vista de consulta. Los parámetros no se modifican desde OMNI Admin.", color = Muted, fontSize = 12.sp)
    }
    items(rows) { (label, value) ->
      Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(15.dp)) {
        Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
          Text(label, color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
          Text(value, color = White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
      }
    }
  }
}

@Composable
private fun ModuleSummary(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
  Card(modifier, colors = CardDefaults.cardColors(containerColor = Color(0xFF10243A)), shape = RoundedCornerShape(13.dp)) {
    Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Icon(icon, null, tint = Mint, modifier = Modifier.size(17.dp))
      Text(value, color = White, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
      Text(label, color = Muted, fontSize = 9.sp, maxLines = 1)
    }
  }
}

@Composable
private fun ModuleContent(array: JSONArray) {
  val records = jsonArrayToList(array)
  val enabledCount = records.count { it["enabled"] == true }
  LazyColumn(Modifier.fillMaxSize().padding(16.dp), contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
    item {
      Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Brush.linearGradient(listOf(Color(0xFF123A5D), Panel, Color(0xFF0A1426)))).border(1.dp, Color(0xFF1679A6), RoundedCornerShape(22.dp)).padding(19.dp)) {
        Text("CENTRO DE FUNCIONES", color = Mint, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.6.sp)
        Text("Módulos de OMNI", color = White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Text("Catálogo en modo consulta. La activación y configuración no se modifican desde este panel.", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          ModuleSummary("Registrados", records.size.toString(), Icons.Default.Apps, Modifier.weight(1f))
          ModuleSummary("Habilitados", enabledCount.toString(), Icons.Default.CheckCircle, Modifier.weight(1f))
          ModuleSummary("Pausados", (records.size - enabledCount).toString(), Icons.Default.PauseCircle, Modifier.weight(1f))
        }
      }
    }
    if (records.isEmpty()) item { EmptyState("No hay módulos registrados en moduleRegistry. No se muestran módulos inventados.") }
    items(records) { item ->
      val id = item["id"]?.toString() ?: return@items
      val enabled = item["enabled"] == true
      val title = item["title"]?.toString() ?: id
      val description = item["description"]?.toString()?.takeIf { it.isNotBlank() } ?: "Descripción no cargada para esta ficha de módulo."
      Card(Modifier.fillMaxWidth().border(1.dp, if (enabled) Color(0xFF17647A) else Color(0xFF34445B), RoundedCornerShape(18.dp)), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(if (enabled) Color(0xFF103B4B) else PanelSoft), contentAlignment = Alignment.Center) {
              Icon(Icons.Default.Extension, null, tint = if (enabled) Mint else Muted)
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
              Text(title, color = White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
              Text(id, color = Muted, fontSize = 10.sp)
            }
            Text(if (enabled) "HABILITADO" else "PAUSADO", color = if (enabled) Mint else Muted, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold)
          }
          Text(description, color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
          val updated = item["updatedAt"]?.toString().orEmpty()
          if (updated.isNotBlank()) Text("Última modificación: ${updated.take(16).replace("T", " ")}", color = Color(0xFF7890AA), fontSize = 10.sp)
        }
      }
    }
  }
}

@Composable
private fun InvitationContent(array: JSONArray) {
  LazyColumn(Modifier.fillMaxSize().padding(16.dp), contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    item {
      Text("Accesos de prueba", color = White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
      Text("Registro de invitaciones en modo consulta. Las altas y cambios de acceso se gestionan fuera de OMNI Admin.", color = Muted, fontSize = 12.sp)
    }
    if (array.length() == 0) item { EmptyState("No hay invitaciones registradas.") }
    items(jsonArrayToList(array)) { RecordCard(it, "betaInvitations") }
  }
}

@Composable
private fun EmptyState(message: String) {
  Card(
    Modifier.fillMaxWidth().border(1.dp, Color(0xFF1A4058), RoundedCornerShape(18.dp)),
    colors = CardDefaults.cardColors(containerColor = Panel),
    shape = RoundedCornerShape(18.dp)
  ) {
    Row(Modifier.fillMaxWidth().padding(19.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Icon(Icons.Default.Info, contentDescription = null, tint = Mint)
      Text(message, color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
    }
  }
}

private object AdminApi {
  suspend fun call(token: String, action: String, data: JSONObject = JSONObject()): JSONObject = withContext(Dispatchers.IO) {
    val connection = (URL(ADMIN_API_URL).openConnection() as HttpURLConnection).apply {
      requestMethod = "POST"; connectTimeout = 20000; readTimeout = 30000; doOutput = true
      setRequestProperty("Authorization", "Bearer " + token)
      setRequestProperty("Content-Type", "application/json; charset=utf-8")
      setRequestProperty("Accept", "application/json")
    }
    try {
      val body = JSONObject().put("action", action).put("data", data).toString()
      connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
      val status = connection.responseCode
      val stream = if (status in 200..299) connection.inputStream else connection.errorStream
      val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
      val response = JSONObject(text.ifBlank { "{}" })
      if (status !in 200..299 || !response.optBoolean("ok", false)) throw IllegalStateException(response.optString("message", "Error HTTP " + status))
      response.optJSONObject("result") ?: JSONObject()
    } finally { connection.disconnect() }
  }
}

private fun jsonArrayToList(array: JSONArray?): List<Map<String, Any?>> {
  if (array == null) return emptyList()
  return (0 until array.length()).mapNotNull { index ->
    val obj = array.optJSONObject(index) ?: return@mapNotNull null
    jsonObjectToMap(obj)
  }
}
private fun jsonObjectToMap(obj: JSONObject): Map<String, Any?> =
  obj.keys().asSequence().associateWith { key -> jsonValue(obj.opt(key)) }
private fun jsonValue(value: Any?): Any? = when (value) {
  JSONObject.NULL -> null
  is JSONObject -> jsonObjectToMap(value)
  is JSONArray -> (0 until value.length()).map { jsonValue(value.opt(it)) }
  else -> value
}
private fun firstPresentValue(item: Map<String, Any?>, vararg keys: String): String =
  keys.firstNotNullOfOrNull { key -> item[key]?.toString()?.takeIf { it.isNotBlank() && it != "null" && !looksLikeTechnicalId(it) } } ?: ""
private fun firstValue(item: Map<String, Any?>, vararg keys: String): String =
  keys.firstNotNullOfOrNull { key -> item[key]?.toString()?.takeIf { it.isNotBlank() && it != "null" } } ?: "Registro"
private fun displayValue(value: Any?): String = when (value) {
  null -> ""
  is Map<*, *> -> value.entries.joinToString(" ") { it.key.toString() + ": " + it.value }
  is List<*> -> value.joinToString(", ")
  else -> value.toString()
}
private fun authMessage(ex: Exception): String {
  val raw = ex.message ?: "No se pudo iniciar sesión."
  return when {
    raw.contains("INVALID_LOGIN_CREDENTIALS") || raw.contains("wrong-password") -> "Correo o contraseña incorrectos."
    raw.contains("too-many-requests") -> "Demasiados intentos. Esperá unos minutos y volvé a probar."
    raw.contains("network") -> "No hay conexión. Revisá internet e intentá nuevamente."
    raw.contains("user-not-found") -> "No existe una cuenta administrativa con ese correo."
    else -> raw
  }
}