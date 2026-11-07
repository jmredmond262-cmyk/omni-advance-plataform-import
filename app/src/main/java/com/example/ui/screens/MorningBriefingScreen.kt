package com.example.ui.screens

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.WorkOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.MainViewModel
import com.example.ui.Screen
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

private val FlowCyan = Color(0xFF68E7FF)
private val FlowMuted = Color(0xFF9EB4C9)
private val FlowSurface = Color(0xB8172940)

@Composable
fun MorningBriefingScreen(
    viewModel: MainViewModel,
    onContinue: () -> Unit
) {
    val context = LocalContext.current
    val briefing by viewModel.morningBriefing.collectAsState()
    val city by viewModel.currentCity.collectAsState()
    val weather by viewModel.liveWeatherData.collectAsState()
    val tasks by viewModel.tasks.collectAsState()
    val profile by viewModel.userProfile.collectAsState()
    val uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()

    var nearbyConexCount by remember { mutableStateOf(0) }
    var nearbyEventCount by remember { mutableStateOf(0) }
    var dataLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(uid, city) {
        if (uid.isBlank()) {
            dataLoaded = true
            return@LaunchedEffect
        }
        val db = FirebaseFirestore.getInstance()
        val locality = city.trim().takeUnless { it.isBlank() || it.equals("Detectando ubicación", true) }
        if (locality == null) {
            dataLoaded = true
            return@LaunchedEffect
        }
        runCatching {
            val jobs = db.collection("jobPosts").whereEqualTo("status", "ACTIVE").get().await()
            nearbyConexCount = jobs.documents.count { job ->
                val expiry = job.getTimestamp("expiresAt")?.toDate()?.time ?: Long.MAX_VALUE
                expiry > System.currentTimeMillis() && job.getString("location").orEmpty().contains(locality, true)
            }
        }.onFailure { nearbyConexCount = 0 }
        runCatching {
            val events = db.collection("omniEvents").get().await()
            val parser = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("es", "AR"))
            nearbyEventCount = events.documents.count { event ->
                val future = runCatching { parser.parse(event.getString("dateTime").orEmpty())?.time ?: 0L }.getOrDefault(0L)
                future > System.currentTimeMillis() && event.getString("location").orEmpty().contains(locality, true)
            }
        }.onFailure { nearbyEventCount = 0 }
        dataLoaded = true
    }

    val prefs = remember { context.getSharedPreferences("omni_morning_briefing", android.content.Context.MODE_PRIVATE) }
    var showWeather by remember { mutableStateOf(prefs.getBoolean("show_weather", true)) }
    var showTasks by remember { mutableStateOf(prefs.getBoolean("show_metrics", true)) }
    var showQuote by remember { mutableStateOf(prefs.getBoolean("show_quote", true)) }
    var showYesterday by remember { mutableStateOf(prefs.getBoolean("show_yesterday", true)) }

    val firstName = profile.fullName.trim().split(Regex("\\s+")).firstOrNull()
        ?.takeUnless { it.isBlank() || it.equals("Usuario", true) }
    val greeting = firstName?.let { "Buen día, $it" } ?: "Buen día"
    val now = Date()
    val dateText = SimpleDateFormat("EEEE d 'de' MMMM", Locale("es", "AR")).format(now)
        .replaceFirstChar { it.titlecase(Locale("es", "AR")) }
    val weatherText = weather?.let { "${it.temperatureC.toInt()}° · ${it.weatherDescription}" } ?: "Pronóstico no disponible"
    val pendingTasks = tasks.count { !it.isCompleted }.takeIf { it > 0 } ?: briefing.pendingTasksCount

    val yesterdayPrefs = remember {
        val storage = context.getSharedPreferences("omni_daily_summary", android.content.Context.MODE_PRIVATE)
        val yesterday = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, -1) }
        val key = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(yesterday.time)
        if (storage.getString("summary_date", "") == key) {
            storage.getInt("summary_completed", 0) to storage.getInt("summary_total", 0)
        } else null
    }

    val quoteOptions = listOf(
        "Lo importante no es correr, sino avanzar con dirección.",
        "Cada día trae una oportunidad para empezar mejor.",
        "Las pequeñas decisiones construyen grandes cambios.",
        "No hace falta tener todo resuelto para dar el próximo paso.",
        "La constancia convierte las ideas en resultados."
    )
    val quote = quoteOptions[Math.floorMod(SimpleDateFormat("yyyyMMdd", Locale.US).format(now).hashCode(), quoteOptions.size)]
    val demoRandom = remember { Random(SimpleDateFormat("yyyyMMdd", Locale.US).format(now).hashCode()) }
    val demoConex = listOf(
        "Se busca técnico para reparación de celulares",
        "Emprendimiento local solicita asistente de ventas",
        "Servicio de electricidad: nueva solicitud en tu zona"
    )[demoRandom.nextInt(3)]
    val demoEvent = listOf(
        "Feria de emprendedores y diseño local",
        "Encuentro cultural y música en vivo",
        "Jornada deportiva y recreativa para la comunidad"
    )[demoRandom.nextInt(3)]

    val shareText = buildString {
        appendLine("OMNI FLOW · $dateText")
        appendLine(greeting)
        appendLine("Clima: $weatherText")
        if (showTasks) appendLine("Tareas pendientes: $pendingTasks")
        if (nearbyConexCount > 0) appendLine("CONEX: $nearbyConexCount publicaciones activas cercanas")
        if (nearbyEventCount > 0) appendLine("Eventos: $nearbyEventCount próximos en tu zona")
        if (showQuote) appendLine(quote)
    }

    Column(
        modifier = Modifier.fillMaxSize()
            .background(Color(0xFF070D18))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 15.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        // Encabezado FLOW: marca abstracta, saludo breve y fecha.
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent)
        ) {
            Box(
                Modifier.fillMaxWidth()
                    .background(Brush.linearGradient(listOf(Color(0xD91B4564), Color(0xC51B2445), Color(0xB51C2040))))
                    .padding(horizontal = 17.dp, vertical = 15.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FlowIdentityMark(Modifier.size(38.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text("OMNI", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.5.sp)
                            Text("FLOW", color = FlowCyan, fontSize = 16.sp, fontWeight = FontWeight.Black, letterSpacing = 2.2.sp)
                        }
                        Spacer(Modifier.weight(1f))
                        Text("TU DÍA, EN UN VISTAZO", color = FlowMuted, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    }
                    Text(greeting, color = Color.White, fontSize = 25.sp, lineHeight = 29.sp, fontWeight = FontWeight.Bold)
                    Text(dateText, color = FlowMuted, fontSize = 11.sp)
                }
            }
        }

        // Clima compacto y contextual.
        if (showWeather) {
            FlowSectionCard(
                title = "ASÍ EMPIEZA TU DÍA",
                accent = Color(0xFF75DDFB),
                icon = { Icon(Icons.Default.Cloud, null, tint = Color(0xFF75DDFB), modifier = Modifier.size(17.dp)) }
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(weatherText, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(city.takeUnless { it.isBlank() || it.equals("Detectando ubicación", true) } ?: "Tu ubicación", color = FlowMuted, fontSize = 10.sp)
                        weather?.daily?.firstOrNull()?.let {
                            Text("Hoy ${it.minC.toInt()}° / ${it.maxC.toInt()}° · lluvia ${it.precipitationProbability}%", color = FlowMuted, fontSize = 10.sp)
                        }
                    }
                    weather?.let {
                        Text("Sensación\n${it.apparentTemperatureC.toInt()}°", color = FlowCyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
                    }
                }
            }
        }

        // Agenda y pendientes.
        FlowSectionCard(
            title = "TU JORNADA",
            accent = Color(0xFF63E5C1),
            icon = { Icon(Icons.Default.CalendarMonth, null, tint = Color(0xFF63E5C1), modifier = Modifier.size(17.dp)) }
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                FlowMiniMetric(Modifier.weight(1f), pendingTasks.toString(), "Pendientes", Color(0xFF63E5C1))
                FlowMiniMetric(Modifier.weight(1f), tasks.count { it.isCompleted }.toString(), "Completadas", Color(0xFF75DDFB))
            }
            Text(
                if (tasks.isEmpty()) "Todavía no hay actividades registradas para hoy." else "Tu lista está actualizada. Podés revisar y organizar tus tareas.",
                color = FlowMuted, fontSize = 10.sp, lineHeight = 14.sp
            )
            TextButton(onClick = { viewModel.navigateTo(Screen.Tasks) }) {
                Text("Abrir mis tareas  ›", color = FlowCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Novedades cercanas: datos reales cuando existen; contenido DEMO claramente identificado si no.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            FlowSectionCard(
                modifier = Modifier.weight(1f),
                title = "CONEX",
                accent = Color(0xFFB99AFF),
                icon = { Icon(Icons.Default.WorkOutline, null, tint = Color(0xFFB99AFF), modifier = Modifier.size(17.dp)) },
                onClick = { viewModel.navigateTo(Screen.JobBoard) }
            ) {
                if (nearbyConexCount > 0) {
                    Text("$nearbyConexCount", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                    Text("publicaciones activas cerca", color = FlowMuted, fontSize = 9.sp, lineHeight = 12.sp)
                    Text("Ver publicaciones  ›", color = FlowCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                } else {
                    Text("Novedad de ejemplo", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Text(demoConex, color = FlowMuted, fontSize = 9.sp, lineHeight = 13.sp, maxLines = 3)
                    FlowDemoTag()
                }
            }
            FlowSectionCard(
                modifier = Modifier.weight(1f),
                title = "CERCA TUYO",
                accent = Color(0xFFFFC978),
                icon = { Icon(Icons.Default.Event, null, tint = Color(0xFFFFC978), modifier = Modifier.size(17.dp)) },
                onClick = { viewModel.navigateTo(Screen.Events) }
            ) {
                if (nearbyEventCount > 0) {
                    Text("$nearbyEventCount", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                    Text("eventos próximos en tu zona", color = FlowMuted, fontSize = 9.sp, lineHeight = 12.sp)
                    Text("Ver eventos  ›", color = FlowCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                } else {
                    Text("Evento de ejemplo", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Text(demoEvent, color = FlowMuted, fontSize = 9.sp, lineHeight = 13.sp, maxLines = 3)
                    FlowDemoTag()
                }
            }
        }

        if (showYesterday && yesterdayPrefs != null) {
            FlowSectionCard(
                title = "AYER EN OMNI",
                accent = Color(0xFF8CCBFF),
                icon = { Icon(Icons.Default.TaskAlt, null, tint = Color(0xFF8CCBFF), modifier = Modifier.size(17.dp)) }
            ) {
                Text("${yesterdayPrefs.first} de ${yesterdayPrefs.second} tareas completadas", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        if (showQuote) {
            FlowSectionCard(
                title = "UNA IDEA PARA HOY",
                accent = Color(0xFFFFC978),
                icon = { Icon(Icons.Default.Lightbulb, null, tint = Color(0xFFFFC978), modifier = Modifier.size(17.dp)) }
            ) {
                Text("“$quote”", color = Color.White, fontSize = 12.sp, lineHeight = 17.sp, textAlign = TextAlign.Start)
            }
        }

        FlowSectionCard(title = "PERSONALIZAR FLOW", accent = FlowCyan) {
            FlowSetting("Clima", showWeather) { showWeather = it; prefs.edit().putBoolean("show_weather", it).apply() }
            FlowSetting("Tareas e indicadores", showTasks) { showTasks = it; prefs.edit().putBoolean("show_metrics", it).apply() }
            FlowSetting("Idea del día", showQuote) { showQuote = it; prefs.edit().putBoolean("show_quote", it).apply() }
            FlowSetting("Resumen de ayer", showYesterday) { showYesterday = it; prefs.edit().putBoolean("show_yesterday", it).apply() }
        }

        OutlinedButton(
            onClick = {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "OMNI FLOW")
                    putExtra(Intent.EXTRA_TEXT, shareText)
                }
                runCatching { context.startActivity(Intent.createChooser(intent, "Compartir OMNI FLOW")) }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = FlowCyan),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(Icons.Default.Share, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(7.dp))
            Text("Compartir mi resumen", fontSize = 12.sp)
        }

        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF087B9E)),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text("Continuar a OMNI", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Text(
            if (dataLoaded) "Tu resumen se actualiza con la información disponible." else "Preparando tu resumen…",
            color = Color(0xFF71869C), fontSize = 9.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        )
    }
}

@Composable
private fun FlowIdentityMark(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val c = center
        val r = size.minDimension * 0.34f
        drawCircle(Brush.radialGradient(listOf(Color(0xFF7AEAFF).copy(alpha = 0.38f), Color.Transparent)), radius = size.minDimension * 0.5f, center = c)
        drawCircle(Color(0xFF75E8FF), radius = r, center = c, style = Stroke(width = size.minDimension * 0.075f))
        drawArc(Color(0xFFB59AFF), startAngle = 35f, sweepAngle = 235f, useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(c.x-r*0.72f,c.y-r*0.72f),
            size = androidx.compose.ui.geometry.Size(r*1.44f,r*1.44f), style = Stroke(width = size.minDimension*0.075f, cap = StrokeCap.Round))
        drawCircle(Color.White, radius = size.minDimension * 0.07f, center = c)
    }
}

@Composable
private fun FlowSectionCard(
    modifier: Modifier = Modifier,
    title: String,
    accent: Color,
    icon: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable Column.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        colors = CardDefaults.cardColors(containerColor = FlowSurface),
        shape = RoundedCornerShape(17.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                if (icon != null) icon()
                Text(title, color = accent, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.1.sp)
            }
            content()
        }
    }
}

@Composable
private fun FlowMiniMetric(modifier: Modifier, value: String, label: String, accent: Color) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(accent.copy(alpha = 0.13f), Color(0xFF111D31)))).padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(label, color = FlowMuted, fontSize = 9.sp)
    }
}

@Composable
private fun FlowDemoTag() {
    Text("DEMO · CONTENIDO SIMULADO", color = Color(0xFFFFC978), fontSize = 7.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.45.sp)
}

@Composable
private fun FlowSetting(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color.White, fontSize = 10.sp)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
