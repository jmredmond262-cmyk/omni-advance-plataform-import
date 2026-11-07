package com.example.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Groups
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import java.text.SimpleDateFormat
import java.util.Locale

private data class OmniMoment(
    val id: String, val occasion: String, val personName: String,
    val date: String, val greeting: String, val note: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MomentsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val db = remember { FirebaseFirestore.getInstance() }
    val uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
    var moments by remember { mutableStateOf<List<OmniMoment>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var searchText by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("Todos") }
    var showFeatureInfo by remember { mutableStateOf<String?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var occasion by remember { mutableStateOf("Cumpleaños") }
    var personName by remember { mutableStateOf("") }
    var date by remember { mutableStateOf("") }
    var greeting by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    DisposableEffect(uid) {
        var listener: ListenerRegistration? = null
        if (uid.isBlank()) {
            loading = false
            error = "Iniciá sesión para administrar tus fechas especiales."
        } else {
            listener = db.collection("omniMoments").whereEqualTo("ownerUid", uid)
                .limit(100)
                .addSnapshotListener { snapshot, exception ->
                    if (exception != null) {
                        error = exception.localizedMessage ?: "No se pudieron cargar tus fechas especiales."
                    } else {
                        moments = snapshot?.documents.orEmpty().mapNotNull { doc ->
                            runCatching { OmniMoment(
                                doc.id, doc.getString("occasion").orEmpty(),
                                doc.getString("personName").orEmpty(), doc.getString("date").orEmpty(),
                                doc.getString("greeting").orEmpty(), doc.getString("note").orEmpty()
                            ) }.getOrNull()
                        }
                        error = null
                    }
                    loading = false
                }
        }
        onDispose { listener?.remove() }
    }

    val visibleMoments = remember(moments, searchText, selectedFilter) {
        moments.filter { moment ->
            val matchesSearch = searchText.isBlank() || moment.personName.contains(searchText, true) || moment.occasion.contains(searchText, true)
            val matchesFilter = selectedFilter == "Todos" || moment.occasion == selectedFilter
            matchesSearch && matchesFilter
        }.sortedBy { daysUntilMoment(it.date) }
    }

    fun saveMoment() {
        if (uid.isBlank() || personName.isBlank() || date.isBlank()) {
            feedback = "Completá el nombre y la fecha para registrar el momento."
            return
        }
        val parsed = runCatching {
            SimpleDateFormat("dd/MM/yyyy", Locale("es", "AR")).apply { isLenient = false }.parse(date.trim())
        }.getOrNull()
        if (parsed == null) {
            feedback = "Usá el formato de fecha dd/MM/aaaa."
            return
        }
        busy = true
        db.collection("omniMoments").add(mapOf(
            "ownerUid" to uid, "occasion" to occasion,
            "personName" to personName.trim().take(100), "date" to date.trim(),
            "greeting" to greeting.trim().take(1000), "note" to note.trim().take(500),
            "status" to "ACTIVE", "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )).addOnSuccessListener {
            busy = false; showEditor = false
            personName = ""; date = ""; greeting = ""; note = ""
            feedback = "Fecha especial guardada en tu espacio privado de OMNI Momentos."
        }.addOnFailureListener {
            busy = false; feedback = it.localizedMessage ?: "No se pudo guardar la fecha especial."
        }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF070A14)).padding(horizontal = 15.dp, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Volver", tint = Color.White) }
            Column(Modifier.weight(1f)) {
                Text("OMNI MOMENTOS", color = Color(0xFF57DDF5), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                Text("Fechas, saludos y recuerdos", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            }
            Icon(Icons.Default.Cake, null, tint = Color(0xFFBD83FF), modifier = Modifier.size(25.dp))
        }
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF101B30)), shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Tus momentos importantes, organizados en un solo lugar", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text("Registrá cumpleaños, aniversarios, graduaciones y fechas personales. Estos datos son privados y sólo visibles desde tu cuenta.", color = Color(0xFF9AAAC2), fontSize = 10.sp)
                Text("OMNI FLOW podrá mostrar recordatorios dentro de la aplicación. Las notificaciones automáticas con la app cerrada requieren una integración adicional.", color = Color(0xFF72E4FF), fontSize = 10.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = { showEditor = true }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF087EAB))) {
            Icon(Icons.Default.CalendarMonth, null, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("Agregar fecha especial")
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Saludos y tarjetas" to Icons.Default.Share, "Voz" to Icons.Default.Mic, "Programar" to Icons.Default.Schedule, "Regalos QR" to Icons.Default.CardGiftcard, "Vouchers" to Icons.Default.NotificationsActive, "Grupos" to Icons.Default.Groups).forEach { (label, icon) ->
                AssistChip(onClick = { showFeatureInfo = label }, label = { Text(label, fontSize = 9.sp) }, leadingIcon = { Icon(icon, null, modifier = Modifier.size(15.dp)) })
            }
        }
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF10243A)), shape = RoundedCornerShape(13.dp)) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("TU CALENDARIO DE MOMENTOS", color = Color(0xFF57DDF5), fontSize = 9.sp, fontWeight = FontWeight.ExtraBold)
                    Text("${moments.size} fechas guardadas", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("${moments.count { daysUntilMoment(it.date) in 0..30 }} próximas en 30 días", color = Color(0xFF9AAAC2), fontSize = 10.sp)
                }
                Icon(Icons.Default.Cake, null, tint = Color(0xFFBD83FF), modifier = Modifier.size(28.dp))
            }
        }
        OutlinedTextField(searchText, { searchText = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Buscar persona o celebración") }, leadingIcon = { Icon(Icons.Default.Search, null) })
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf("Todos", "Cumpleaños", "Aniversario", "Graduación", "Otra fecha").forEach { filter ->
                FilterChip(selected = selectedFilter == filter, onClick = { selectedFilter = filter }, label = { Text(filter, fontSize = 9.sp) })
            }
        }
        when {
            loading -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color(0xFF57DDF5)) }
            error != null -> Text(error.orEmpty(), color = Color(0xFFFFA7A7), fontSize = 12.sp)
            moments.isEmpty() -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Cake, null, tint = Color(0xFF57DDF5), modifier = Modifier.size(38.dp))
                    Text("Todavía no registraste fechas especiales", color = Color.White, fontWeight = FontWeight.Bold)
                    Text("Agregá el primer cumpleaños, aniversario o recuerdo importante.", color = Color(0xFF9AAAC2), fontSize = 11.sp)
                }
            }
            visibleMoments.isEmpty() && moments.isNotEmpty() -> Text("No hay momentos que coincidan con la búsqueda o el filtro.", color = Color(0xFF9AAAC2), fontSize = 11.sp, modifier = Modifier.padding(12.dp))
            else -> LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 0.dp, top = 8.dp, end = 0.dp, bottom = 90.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(visibleMoments, key = { it.id }) { moment ->
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF101B30)), shape = RoundedCornerShape(13.dp)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(moment.personName, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    Text(moment.occasion + " · " + moment.date, color = Color(0xFF57DDF5), fontSize = 10.sp)
                                }
                                IconButton(onClick = {
                                    val message = "✨ ${moment.occasion}: ${moment.personName}\nFecha: ${moment.date}\n${moment.greeting}\n${moment.note}\nCompartido desde OMNI Momentos."
                                    val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, message) }
                                    runCatching { context.startActivity(Intent.createChooser(intent, "Compartir saludo")) }
                                        .onFailure { feedback = "No se pudo abrir el menú para compartir." }
                                }) { Icon(Icons.Default.Share, "Compartir saludo", tint = Color(0xFF57DDF5)) }
                                IconButton(onClick = {
                                    db.collection("omniMoments").document(moment.id).delete()
                                        .addOnSuccessListener { feedback = "Fecha especial eliminada." }
                                        .addOnFailureListener { feedback = it.localizedMessage ?: "No se pudo eliminar la fecha." }
                                }) { Icon(Icons.Default.DeleteOutline, "Eliminar fecha", tint = Color(0xFFFF8F8F)) }
                            }
                            if (moment.greeting.isNotBlank()) Text(moment.greeting, color = Color(0xFFD2DCEC), fontSize = 11.sp)
                            if (moment.note.isNotBlank()) Text("Nota privada: ${moment.note}", color = Color(0xFF9AAAC2), fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
    if (showEditor) AlertDialog(
        onDismissRequest = { if (!busy) showEditor = false },
        containerColor = Color(0xFF101B30),
        title = { Text("Nueva fecha especial", color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("Cumpleaños", "Aniversario", "Graduación", "Otra fecha").forEach { type ->
                        FilterChip(selected = occasion == type, onClick = { occasion = type }, label = { Text(type, fontSize = 9.sp) })
                    }
                }
                OutlinedTextField(personName, { personName = it }, label = { Text("Persona o referencia") }, singleLine = true)
                OutlinedTextField(date, { date = it }, label = { Text("Fecha · dd/MM/aaaa") }, singleLine = true)
                OutlinedTextField(greeting, { greeting = it }, label = { Text("Saludo o dedicatoria (opcional)") }, minLines = 2)
                OutlinedTextField(note, { note = it }, label = { Text("Nota privada (opcional)") }, minLines = 2)
            }
        },
        confirmButton = { TextButton(onClick = { saveMoment() }, enabled = !busy) { Text(if (busy) "Guardando…" else "Guardar fecha") } },
        dismissButton = { TextButton(onClick = { showEditor = false }, enabled = !busy) { Text("Cancelar") } }
    )
    showFeatureInfo?.let { feature ->
        val detail = when (feature) {
            "Saludos y tarjetas" -> "Prepará dedicatorias y compartilas desde cada fecha guardada. La tarjeta visual personalizada se incorporará en la siguiente etapa."
            "Voz" -> "Los mensajes de voz requieren grabación, permiso de micrófono y almacenamiento seguro. No simulamos una grabación."
            "Programar" -> "Los envíos programados y avisos con OMNI cerrada requieren un planificador persistente y pruebas en el dispositivo."
            "Regalos QR" -> "Los regalos QR se habilitarán como obsequios DEMO, sin mover dinero real."
            "Vouchers" -> "Los vales necesitarán código único, vencimiento y registro de canje para evitar usos duplicados."
            else -> "Los regalos grupales necesitarán participantes, aportes DEMO y seguimiento del objetivo compartido."
        }
        AlertDialog(onDismissRequest = { showFeatureInfo = null }, containerColor = Color(0xFF101B30),
            title = { Text(feature, color = Color.White) }, text = { Text(detail, color = Color(0xFFB8C8D9), fontSize = 12.sp) },
            confirmButton = { TextButton(onClick = { showFeatureInfo = null }) { Text("Entendido") } })
    }
    feedback?.let { message ->
        AlertDialog(onDismissRequest = { feedback = null }, title = { Text("OMNI Momentos") }, text = { Text(message) }, confirmButton = { TextButton(onClick = { feedback = null }) { Text("Aceptar") } })
    }
}

private fun daysUntilMoment(dateText: String): Int {
    val parsed = runCatching { SimpleDateFormat("dd/MM/yyyy", Locale("es", "AR")).apply { isLenient = false }.parse(dateText) }.getOrNull() ?: return Int.MAX_VALUE
    val today = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }
    val target = java.util.Calendar.getInstance().apply {
        time = parsed
        set(java.util.Calendar.YEAR, today.get(java.util.Calendar.YEAR))
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }
    if (target.before(today)) target.add(java.util.Calendar.YEAR, 1)
    return ((target.timeInMillis - today.timeInMillis) / 86_400_000L).toInt()
}
