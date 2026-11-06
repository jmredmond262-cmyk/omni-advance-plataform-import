package com.omni.admin

import android.graphics.Color as AndroidColor
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.os.Handler
import android.os.Looper
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
      Text("BASE VECTORIAL", color = Color(0xFF65E9B7), fontSize = 8.sp, fontWeight = FontWeight.ExtraBold)
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
            settings.loadsImagesAutomatically = true
            settings.blockNetworkLoads = false
            webChromeClient = object : WebChromeClient() {
              override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                android.util.Log.d("OMNI_LIVE_MAP", "${message.message()} (${message.sourceId()}:${message.lineNumber()})")
                return true
              }
              override fun onProgressChanged(view: WebView, newProgress: Int) {
                android.util.Log.d("OMNI_LIVE_MAP", "WebView carga: $newProgress%")
              }
            }
            webViewClient = object : WebViewClient() {
              override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                Handler(Looper.getMainLooper()).postDelayed({
                  view.evaluateJavascript(
                    "(function(){var s=document.getElementById('status');if(s&&s.textContent.indexOf('CARGANDO MOTOR')===0){s.textContent='EL MOTOR EXTERNO NO RESPONDE · revisando unpkg.com';s.style.color='#ffcf70';s.style.borderColor='#ffcf70';}})();",
                    null
                  )
                }, 10000)
              }
              override fun onPageFinished(view: WebView, url: String) {
                view.evaluateJavascript(
                  "(function(){var s=document.getElementById('status');if(!s)return;if(!window.maplibregl){s.textContent='ERROR: MOTOR MAPLIBRE NO DESCARGADO';s.style.color='#ff9b9b';s.style.borderColor='#ff6b6b';}else if(!window.omniMap){s.textContent='ERROR: MOTOR CARGADO, PERO EL MAPA NO SE INICIALIZÓ';s.style.color='#ff9b9b';s.style.borderColor='#ff6b6b';}else{window.omniMap.resize();}})();",
                  null
                )
              }
              override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                android.util.Log.e("OMNI_LIVE_MAP", "Web error ${error.errorCode}: ${error.description} URL=${request.url}")
                if (request.url.toString().contains("unpkg.com") || request.isForMainFrame) {
                  view.evaluateJavascript(
                    "(function(){var s=document.getElementById('status');if(s){s.textContent='ERROR DE RED AL CARGAR EL MOTOR: ${error.description.toString().replace("'", "")}';s.style.color='#ff9b9b';s.style.borderColor='#ff6b6b';}})();",
                    null
                  )
                }
              }
            }
            // Use the tile host as the document origin so WebView workers and same-host tile requests
            // run under the cartography provider origin rather than a fabricated local hostname.
            loadDataWithBaseURL("https://tiles.openfreemap.org/", MAP_HTML, "text/html", "UTF-8", null)
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
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
<link href="https://unpkg.com/maplibre-gl@4.7.1/dist/maplibre-gl.css" rel="stylesheet">
<style>
html,body{margin:0;padding:0;width:100%;height:100%;overflow:hidden;background:#06101f}
#map{position:absolute;inset:0;width:100%;height:100%;background:#06101f}
.maplibregl-ctrl-group{background:#0b2034!important;border:1px solid #00bcd4!important;box-shadow:0 0 12px #00bcd455!important}
.maplibregl-ctrl button span{filter:invert(1) sepia(1) saturate(5) hue-rotate(140deg)}
.maplibregl-ctrl-attrib{background:#071426dd!important;color:#8faec4!important}
.maplibregl-ctrl-attrib a{color:#00e5ff!important}
#status{position:absolute;left:10px;top:10px;z-index:5;max-width:calc(100% - 40px);padding:7px 10px;border:1px solid #00dfff;border-radius:9px;background:#061526ee;color:#65e9b7;font:700 10px Arial;letter-spacing:.5px;box-shadow:0 0 14px #00dfff44;white-space:normal}
</style>
</head>
<body>
<div id="map"></div><div id="status">CARGANDO MOTOR CARTOGRÁFICO…</div>
<script src="https://unpkg.com/maplibre-gl@4.7.1/dist/maplibre-gl.js"></script>
<script>
(function(){
 const status=document.getElementById('status');
 const show=(message,failed)=>{status.textContent=message;status.style.color=failed?'#ff9b9b':'#65e9b7';status.style.borderColor=failed?'#ff6b6b':'#00dfff';};
 if(!window.maplibregl){show('ERROR: no se pudo descargar MapLibre GL JS desde unpkg.com',true);return;}
 if(!maplibregl.supported()){show('ERROR: WebView no dispone de WebGL compatible para dibujar el mapa',true);return;}
 show('MOTOR ACTIVO · DESCARGANDO CARTOGRAFÍA…',false);
 let map;
 try{
   map=new maplibregl.Map({
     container:'map',
     style:'https://tiles.openfreemap.org/styles/dark',
     center:[-60.7000,-31.6333],
     zoom:12,
     pitch:25,
     bearing:-12,
     attributionControl:true,
     fadeDuration:0,
     maxTileCacheSize:128
   });
   window.omniMap=map;
   map.addControl(new maplibregl.NavigationControl({visualizePitch:true}),'top-right');
   map.addControl(new maplibregl.ScaleControl({maxWidth:100,unit:'metric'}),'bottom-left');
 }catch(e){show('ERROR AL INICIAR EL MAPA: '+(e.message||e),true);return;}
 let firstError='';
 map.on('error',function(e){
   const message=(e&&e.error&&e.error.message)?e.error.message:'fallo de red o tesela';
   if(!firstError) firstError=message;
   console.error('OMNI MAP ERROR: '+message);
   show('ERROR DE CARTOGRAFÍA: '+message,true);
 });
 map.on('styledata',function(){
   if(status.textContent.indexOf('ERROR')!==0) show('ESTILO RECIBIDO · CARGANDO CALLES Y TESelas…',false);
 });
 map.on('load',function(){
   show('ESTILO CARGADO · ESPERANDO DIBUJO DEL MAPA…',false);
   try{
     const style=map.getStyle();
     const sourceId=Object.keys(style.sources||{}).find(id=>style.sources[id].type==='vector');
     const hasTransport=(style.layers||[]).some(layer=>layer['source-layer']==='transportation');
     if(sourceId && hasTransport){
       const before=(style.layers.find(layer=>layer.type==='symbol')||{}).id;
       const filter=['in',['get','class'],['literal',['motorway','trunk','primary','secondary','tertiary']]];
       map.addLayer({id:'omni-road-glow',type:'line',source:sourceId,'source-layer':'transportation',filter,
         paint:{'line-color':'#00dfff','line-width':['interpolate',['linear'],['zoom'],8,1.5,14,5,18,10],'line-opacity':0.28,'line-blur':2.5}},before);
       map.addLayer({id:'omni-road-core',type:'line',source:sourceId,'source-layer':'transportation',filter,
         paint:{'line-color':'#28dfff','line-width':['interpolate',['linear'],['zoom'],8,0.35,14,1.2,18,2.2],'line-opacity':0.72}},before);
     }
   }catch(e){console.warn('OMNI neon road overlay unavailable: '+e.message);}
 });
 map.on('idle',function(){
   if(firstError){show('MAPA PARCIAL · '+firstError,true);return;}
   if(map.areTilesLoaded()) show('MAPA VISIBLE · OMNI',false);
 });
 setTimeout(function(){
   if(status.textContent.indexOf('ERROR')===0 || status.textContent==='MAPA VISIBLE · OMNI') return;
   const loaded=map.areTilesLoaded();
   if(loaded) show('MAPA VISIBLE · OMNI',false);
   else show('SIN TESelas VISIBLES. Revisar conexión al servicio de mapas o WebGL.',true);
 },18000);
 window.addEventListener('resize',function(){map.resize();});
})();
</script>
</body>
</html>
""".trimIndent()
