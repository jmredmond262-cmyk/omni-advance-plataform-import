package com.omni.admin

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

private const val OMNI_ADMIN_MAP_STYLE = "https://tiles.openfreemap.org/styles/dark"

@Composable
internal fun LiveMapScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var mapStatus by remember { mutableStateOf("Iniciando cartografía…") }

    val mapView = remember(context) {
        MapLibre.getInstance(context)
        MapView(context).also { it.onCreate(null) }
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val lifecycle = lifecycleOwner.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { map ->
            map.setStyle(Style.Builder().fromUri(OMNI_ADMIN_MAP_STYLE)) {
                map.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(-38.4161, -63.6167))
                    .zoom(3.8)
                    .build()
                map.uiSettings.isCompassEnabled = true
                map.uiSettings.isRotateGesturesEnabled = true
                map.uiSettings.isTiltGesturesEnabled = false
                mapStatus = "Cartografía conectada"
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF060B16))) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize()
        )

        Column(
            Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Card(
                Modifier.fillMaxWidth().border(1.dp, Color(0xFF00DDF5), RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = Color(0xEE0A1729)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Map, contentDescription = null, tint = Color(0xFF00E5FF), modifier = Modifier.size(22.dp))
                    Spacer(Modifier.size(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("MAPA EN TIEMPO REAL", color = Color(0xFF00E5FF), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.4.sp)
                        Text("OMNI ADMIN · Cobertura geográfica", color = Color(0xFFF4F7FC), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(if (mapStatus == "Cartografía conectada") Color(0xFF38E7A5) else Color(0xFFFFC15A)))
                }
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xE6101E32)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    mapStatus,
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    color = if (mapStatus == "Cartografía conectada") Color(0xFF8FFFE1) else Color(0xFFFFD28A),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Card(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp)
                .border(1.dp, Color(0xFF15516A), RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = Color(0xF0091728)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(Icons.Default.MyLocation, contentDescription = null, tint = Color(0xFF00E5FF), modifier = Modifier.size(20.dp))
                Column {
                    Text("Base cartográfica activa", color = Color(0xFFF4F7FC), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Mapa navegable. Las posiciones de usuarios y dispositivos se mostrarán al conectar la telemetría de ubicación.",
                        color = Color(0xFF9EADC3),
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )
                }
            }
        }
    }
}
