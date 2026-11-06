package com.omni.admin

import android.graphics.Color as AndroidColor
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView

private val MapNavy = Color(0xFF06101F)
private val MapCyan = Color(0xFF00E5FF)
private val MapMuted = Color(0xFF91A7C1)

@Composable
internal fun LiveMapScreen() {
  Column(
    Modifier.fillMaxSize().background(MapNavy).padding(10.dp),
    verticalArrangement = Arrangement.spacedBy(9.dp)
  ) {
    Row(
      Modifier.fillMaxWidth()
        .background(Brush.horizontalGradient(listOf(Color(0xFF102B43), Color(0xFF0A172A))))
        .border(1.dp, Color(0xFF12617A), RoundedCornerShape(14.dp))
        .padding(horizontal = 13.dp, vertical = 11.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      Icon(Icons.Default.Public, contentDescription = null, tint = MapCyan)
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("OMNI LIVE MAP", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 1.3.sp)
        Text("Cartografía interactiva · Vista inicial Santa Fe", color = MapMuted, fontSize = 9.sp)
      }
      Text("BASE ACTIVA", color = Color(0xFF65E9B7), fontSize = 8.sp, fontWeight = FontWeight.ExtraBold)
    }
    Box(
      Modifier.weight(1f).fillMaxWidth()
        .border(1.dp, Color(0xFF08718A), RoundedCornerShape(16.dp))
    ) {
      AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
          WebView(context).apply {
            setBackgroundColor(AndroidColor.rgb(6, 16, 31))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
            loadDataWithBaseURL("https://omni-map.local/", MAP_HTML, "text/html", "UTF-8", null)
          }
        }
      )
    }
    Row(
      Modifier.fillMaxWidth().padding(horizontal = 3.dp, vertical = 2.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
      Icon(Icons.Default.MyLocation, contentDescription = null, tint = MapCyan)
      Text("Mapa navegable. El seguimiento de dispositivos se incorporará con la telemetría de OMNI.", color = MapMuted, fontSize = 9.sp)
    }
  }
}

private val MAP_HTML = """
<!doctype html>
<html>
<head>
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
<link href="https://unpkg.com/maplibre-gl@4.7.1/dist/maplibre-gl.css" rel="stylesheet">
<style>
html,body,#map{margin:0;padding:0;width:100%;height:100%;overflow:hidden;background:#06101f}
.maplibregl-ctrl-group{background:#0b2034!important;border:1px solid #00bcd4!important;box-shadow:0 0 12px #00bcd455!important}
.maplibregl-ctrl button span{filter:invert(1) sepia(1) saturate(5) hue-rotate(140deg)}
.maplibregl-ctrl-attrib{background:#071426dd!important;color:#8faec4!important}
.maplibregl-ctrl-attrib a{color:#00e5ff!important}
#status{position:absolute;left:10px;top:10px;z-index:2;padding:7px 10px;border:1px solid #00dfff;border-radius:9px;background:#061526e8;color:#65e9b7;font:700 10px Arial;letter-spacing:1px;box-shadow:0 0 14px #00dfff44}
</style>
</head>
<body>
<div id="map"></div><div id="status">CARGANDO CARTOGRAFÍA…</div>
<script src="https://unpkg.com/maplibre-gl@4.7.1/dist/maplibre-gl.js"></script>
<script>
(function(){
 const status=document.getElementById('status');
 if(!window.maplibregl){status.textContent='NO SE PUDO CARGAR MAPLIBRE';return;}
 const map=new maplibregl.Map({
   container:'map',
   style:'https://tiles.openfreemap.org/styles/dark',
   center:[-60.7000,-31.6333],
   zoom:12,
   pitch:32,
   bearing:-12,
   attributionControl:true
 });
 map.addControl(new maplibregl.NavigationControl({visualizePitch:true}),'top-right');
 map.addControl(new maplibregl.ScaleControl({maxWidth:100,unit:'metric'}),'bottom-left');
 map.on('load',function(){
   try {
     const layers=map.getStyle().layers||[];
     const before=(layers.find(l=>l.type==='symbol')||{}).id;
     const filter=['in',['get','class'],['literal',['motorway','trunk','primary','secondary','tertiary']]];
     map.addLayer({id:'omni-road-glow',type:'line',source:'openmaptiles','source-layer':'transportation',filter:filter,
       paint:{'line-color':'#00dfff','line-width':['interpolate',['linear'],['zoom'],8,1.5,14,5,18,10],'line-opacity':0.32,'line-blur':3}},before);
     map.addLayer({id:'omni-road-core',type:'line',source:'openmaptiles','source-layer':'transportation',filter:filter,
       paint:{'line-color':'#28dfff','line-width':['interpolate',['linear'],['zoom'],8,0.35,14,1.2,18,2.2],'line-opacity':0.88}},before);
   } catch(e) {}
   status.textContent='MAPA CONECTADO · OMNI';
 });
 map.on('error',function(e){
   if(status.textContent==='CARGANDO CARTOGRAFÍA…') status.textContent='CONECTANDO CON EL SERVICIO DE MAPAS…';
 });
 window.addEventListener('resize',function(){map.resize();});
})();
</script>
</body>
</html>
""".trimIndent()
