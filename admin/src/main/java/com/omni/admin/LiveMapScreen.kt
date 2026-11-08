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
import android.view.MotionEvent
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

private val MapNavy = Color(0xFF06101F)
private val MapCyan = Color(0xFF00E5FF)
private val MapMuted = Color(0xFF91A7C1)

@Composable
internal fun LiveMapScreen(payload: JSONObject, loading: Boolean, error: String, onRefresh: () -> Unit) {
  val points = remember(payload) { payload.optJSONArray("geoActivity") ?: JSONArray() }
  val summary = payload.optJSONObject("geoActivitySummary")
  val pointsJson = points.toString()
  val activeUsers = summary?.optInt("activeUsers", points.length()) ?: points.length()

  Column(
    Modifier.fillMaxSize().background(MapNavy).padding(10.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    Row(
      Modifier.fillMaxWidth()
        .background(Brush.horizontalGradient(listOf(Color(0xFF102B43), Color(0xFF0A172A))))
        .border(1.dp, Color(0xFF12617A), RoundedCornerShape(14.dp))
        .padding(horizontal = 13.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      Icon(Icons.Default.Public, contentDescription = null, tint = MapCyan)
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("OMNI LIVE MAP · ACTIVIDAD REAL", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 1.1.sp)
        Text("Ubicaciones GPS compartidas con consentimiento", color = MapMuted, fontSize = 9.sp)
      }
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Text("$activeUsers USUARIOS", color = MapCyan, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold)
        IconButton(onClick = onRefresh) {
          Icon(Icons.Default.Refresh, contentDescription = "Actualizar ubicaciones", tint = MapCyan)
        }
      }
    }
    Box(
      Modifier.weight(1f).fillMaxWidth()
        .border(1.dp, Color(0xFF08718A), RoundedCornerShape(16.dp))
    ) {
      AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
          WebView(context).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
              android.view.ViewGroup.LayoutParams.MATCH_PARENT,
              android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
            // Evita que un contenedor Android padre intercepte el gesto de arrastre del mapa.
            setOnTouchListener { view, event ->
              when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                  view.parent?.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                  view.parent?.requestDisallowInterceptTouchEvent(false)
              }
              false
            }
            addOnLayoutChangeListener { view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
              val width = right - left
              val height = bottom - top
              if (width > 0 && height > 0 && (width != oldRight - oldLeft || height != oldBottom - oldTop)) {
                view.post {
                  (view as? WebView)?.evaluateJavascript("if(window.omniForceResize){window.omniForceResize('android-layout');}", null)
                }
              }
            }
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
                  "(function(){var s=document.getElementById('status');if(!s)return;if(!window.maplibregl){s.textContent='ERROR: MOTOR MAPLIBRE NO DESCARGADO';s.style.color='#ff9b9b';s.style.borderColor='#ff6b6b';}else if(!window.omniMap){if(s.textContent.indexOf('CARGANDO')===0||s.textContent.indexOf('MOTOR ACTIVO')===0){s.textContent='ERROR: EL SCRIPT NO COMPLETÓ LA INICIALIZACIÓN. '+(window.omniInitError||'Revisar consola JavaScript/WebGL');s.style.color='#ff9b9b';s.style.borderColor='#ff6b6b';}}else{window.omniMap.resize();window.omniSetPresence && window.omniSetPresence($pointsJson);setTimeout(function(){window.omniMap.resize();window.omniMap.triggerRepaint();},350);setTimeout(function(){window.omniMap.resize();window.omniMap.triggerRepaint();},1200);}})();",
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
            loadDataWithBaseURL("https://tiles.openfreemap.org/", MAP_HTML, "text/html", "UTF-8", null)
          }
        },
        update = { view ->
          view.evaluateJavascript("if(window.omniSetPresence){window.omniSetPresence($pointsJson);}", null)
        }
      )
    }
    Row(
      Modifier.fillMaxWidth().padding(horizontal = 3.dp, vertical = 2.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
      Icon(Icons.Default.MyLocation, contentDescription = null, tint = MapCyan)
      Column(Modifier.weight(1f)) {
        Text(
          if (error.isNotBlank()) error
          else if (loading) "Consultando posiciones GPS reales…"
          else if (activeUsers > 0) "$activeUsers usuarios con ubicación real y reciente"
          else "No hay usuarios compartiendo ubicación ahora. Cada persona debe autorizar y activar el uso compartido.",
          color = if (error.isNotBlank()) Color(0xFFFF9B9B) else MapMuted,
          fontSize = 9.sp
        )
        Text("Solo se muestran ubicaciones recientes de cuentas que aceptaron compartir su GPS.", color = MapMuted, fontSize = 8.sp)
      }
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
html,body{display:block;margin:0;padding:0;width:100vw;height:100vh;min-width:100%;min-height:100%;overflow:hidden;background:#06101f}
#map{position:fixed;top:0;right:0;bottom:0;left:0;display:block;width:100vw;height:100vh;min-width:100%;min-height:100%;overflow:hidden;background:#06101f;isolation:isolate}
#map .maplibregl-canvas-container{position:absolute!important;inset:0!important;width:100%!important;height:100%!important;z-index:1!important;pointer-events:auto!important;touch-action:none!important;-webkit-user-select:none!important;user-select:none!important}
#map .maplibregl-canvas{position:absolute!important;inset:0!important;display:block!important;width:100%!important;height:100%!important;opacity:1!important;visibility:visible!important;z-index:1!important;pointer-events:auto!important;touch-action:none!important}
#map .maplibregl-control-container{position:absolute;inset:0;z-index:3;pointer-events:none!important}
#map .maplibregl-ctrl-top-right,#map .maplibregl-ctrl-top-left,#map .maplibregl-ctrl-bottom-right,#map .maplibregl-ctrl-bottom-left,#map .maplibregl-ctrl-group{pointer-events:auto!important}
.maplibregl-ctrl-group{background:#0b2034!important;border:1px solid #00bcd4!important;box-shadow:0 0 12px #00bcd455!important}
.maplibregl-ctrl button span{filter:invert(1) sepia(1) saturate(5) hue-rotate(140deg)}
.maplibregl-ctrl-attrib{background:#071426dd!important;color:#8faec4!important}
.maplibregl-ctrl-attrib a{color:#00e5ff!important}
#map .maplibregl-marker-container{position:absolute!important;inset:0!important;width:100%!important;height:100%!important;z-index:4!important;pointer-events:none!important}
#map .maplibregl-marker{z-index:5!important;pointer-events:auto!important;visibility:visible!important;opacity:1!important}
#map .maplibregl-popup{z-index:7!important}
.omni-user-marker{width:20px;height:20px;border-radius:50%;background:radial-gradient(circle at 35% 30%,#ffffff 0 10%,#8cffff 18%,#00e5ff 52%,#007caa 100%);border:2px solid #e8fdff;box-shadow:0 0 0 5px #00e5ff35,0 0 12px #00e5ff,0 0 25px #00dfff;position:relative;box-sizing:border-box;cursor:pointer}.omni-user-marker:after{content:'';position:absolute;inset:-8px;border:1.5px solid #00e5ff;border-radius:50%;animation:omniPulse 1.8s ease-out infinite;pointer-events:none}.omni-cluster-marker{width:38px;height:38px;border-radius:50%;border:2px solid #d9ffff;background:radial-gradient(circle at 35% 28%,#36faff 0,#00aeca 45%,#064c70 100%);color:#fff;font:900 13px Arial;display:flex;align-items:center;justify-content:center;box-shadow:0 0 0 7px #00e5ff35,0 0 16px #00e5ff,0 0 32px #00cfff;cursor:pointer;position:relative;text-shadow:0 0 7px #002b42;outline:none;padding:0}.omni-cluster-marker:after{content:'';position:absolute;inset:-9px;border:1px solid #00e5ff;border-radius:50%;animation:omniPulse 2s ease-out infinite;pointer-events:none}.omni-cluster-marker:active{transform:scale(.94)}@keyframes omniPulse{0%{transform:scale(.55);opacity:.9}100%{transform:scale(1.8);opacity:0}}.maplibregl-popup-content{background:#071a2c;color:#eafaff;border:1px solid #00cde8;border-radius:10px;box-shadow:0 0 18px #00dfff55;font:12px Arial;padding:10px 12px}.maplibregl-popup-tip{border-top-color:#071a2c!important;border-bottom-color:#071a2c!important}.omni-popup-title{font-weight:700;color:#00e5ff;margin-bottom:5px}.omni-popup-meta{color:#c4d7e7;line-height:1.5}#status{position:absolute;left:10px;top:10px;z-index:5;max-width:calc(100% - 40px);padding:7px 10px;border:1px solid #00dfff;border-radius:9px;background:#061526ee;color:#65e9b7;font:700 10px Arial;letter-spacing:.5px;box-shadow:0 0 14px #00dfff44;white-space:normal}
</style>
</head>
<body>
<div id="map"></div><div id="status">CARGANDO MOTOR CARTOGRÁFICO…</div>
<script src="https://unpkg.com/maplibre-gl@4.7.1/dist/maplibre-gl.js"></script>
<script>
(function(){
 const status=document.getElementById('status');
 window.omniInitError='';
 window.onerror=function(message,source,line,column,error){
   window.omniInitError=String(message||'Error JavaScript')+' @ '+String(line||'?')+':'+String(column||'?');
   console.error('OMNI INIT JS ERROR: '+window.omniInitError);
   show('ERROR JAVASCRIPT: '+window.omniInitError,true);
   return false;
 };
 window.addEventListener('unhandledrejection',function(event){
   window.omniInitError=String(event.reason&&event.reason.message?event.reason.message:event.reason||'Promesa rechazada');
   console.error('OMNI INIT PROMISE ERROR: '+window.omniInitError);
   show('ERROR DE INICIALIZACIÓN: '+window.omniInitError,true);
 });
 const show=(message,failed)=>{status.textContent=message;status.style.color=failed?'#ff9b9b':'#65e9b7';status.style.borderColor=failed?'#ff6b6b':'#00dfff';};
 if(!window.maplibregl){show('ERROR: no se pudo descargar MapLibre GL JS desde unpkg.com',true);return;}
 // MapLibre GL JS builds do not all expose the legacy supported() helper.
 // Never call it unless the loaded build actually exports it; let Map report GPU errors.
 if(typeof maplibregl.Map!=='function'){
   show('ERROR: la librería cargó, pero no expone maplibregl.Map. Versión/API incompatible.',true);return;
 }
 if(typeof maplibregl.supported==='function'){
   try{if(!maplibregl.supported()){show('ERROR: WebView no dispone de WebGL compatible para dibujar el mapa',true);return;}}
   catch(checkError){console.warn('MapLibre supported() check failed; constructor will perform GPU validation:',checkError);}
 }
 if(typeof maplibregl.NavigationControl!=='function'||typeof maplibregl.ScaleControl!=='function'){
   show('ERROR: faltan controles requeridos en la distribución de MapLibre.',true);return;
 }
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
     interactive:true,
     dragPan:true,
     dragRotate:false,
     touchZoomRotate:true,
     touchPitch:false,
     scrollZoom:true,
     doubleClickZoom:true,
     attributionControl:true,
     fadeDuration:0,
     preserveDrawingBuffer:true,
     maxTileCacheSize:128
   });
   window.omniMap=map;
   window.omniPresenceMarkers=new Map();
   window.omniClusterMarkers=[];
   window.omniPresenceData=[];
   window.omniSpiderfyKey='';
   window.omniEscapeHtml=function(value){return String(value==null?'':value).replace(/[&<>"']/g,function(ch){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[ch];});};
   window.omniClearRenderedMarkers=function(){
     window.omniPresenceMarkers.forEach(function(entry){entry.marker.remove();});
     window.omniPresenceMarkers.clear();
     window.omniClusterMarkers.forEach(function(marker){marker.remove();});
     window.omniClusterMarkers=[];
   };
   window.omniPopupHtml=function(point){
     const label=window.omniEscapeHtml(point.userLabel||'Cuenta OMNI');
     const locality=window.omniEscapeHtml(point.locality||'Localidad no informada');
     const updated=window.omniEscapeHtml(point.updatedAt||'');
     const timeText=updated?new Date(updated).toLocaleString('es-AR'):'Sin hora informada';
     const lat=Number(point.latitude),lon=Number(point.longitude),accuracy=Number(point.accuracyMeters);
     return '<div class="omni-popup-title">'+label+'</div><div class="omni-popup-meta">'+locality+'<br>Coordenadas: '+lat.toFixed(6)+', '+lon.toFixed(6)+'<br>Precisión GPS: '+(Number.isFinite(accuracy)?Math.round(accuracy)+' m':'No informada')+'<br>Actualizado: '+timeText+'<br><span style="color:#65e9b7">● UBICACIÓN REAL · EN LÍNEA</span></div>';
    };
    window.omniAddUserMarker=function(point,offset){
     const uid=String(point.uid||'');
     const element=document.createElement('div');
     element.className='omni-user-marker';
     element.setAttribute('aria-label',point.userLabel||'Usuario OMNI');
     const popup=new maplibregl.Popup({offset:18,closeButton:true,closeOnClick:false}).setHTML(window.omniPopupHtml(point));
     const marker=new maplibregl.Marker({element:element,anchor:'center',offset:offset||[0,0]})
       .setLngLat([Number(point.longitude),Number(point.latitude)]).setPopup(popup).addTo(map);
     window.omniPresenceMarkers.set(uid,{marker:marker,popup:popup});
   };
   window.omniRenderPresence=function(){
     if(!map)return;
     window.omniClearRenderedMarkers();
     const valid=window.omniPresenceData.filter(function(p){
       const lat=Number(p.latitude),lon=Number(p.longitude);
       return String(p.uid||'')&&p.status==='online'&&p.source!=='DEMO'&&Number.isFinite(lat)&&Number.isFinite(lon)&&Math.abs(lat)<=90&&Math.abs(lon)<=180;
     });
     // Agrupar todos los puntos por proximidad visual, incluidos los DEMO.
     // Así la prueba reproduce el mismo comportamiento que tendrá el mapa real.
     const projected=valid.map(function(point){return {point:point,pixel:map.project([Number(point.longitude),Number(point.latitude)])};});
     const groups=[];
     const radius=48;
     projected.forEach(function(item){
       let target=null,best=radius;
       groups.forEach(function(group){
         const dx=item.pixel.x-group.cx,dy=item.pixel.y-group.cy;
         const distance=Math.sqrt(dx*dx+dy*dy);
         if(distance<best){best=distance;target=group;}
       });
       if(!target){groups.push({items:[item],cx:item.pixel.x,cy:item.pixel.y});}
       else{
         target.items.push(item);
         target.cx=target.items.reduce(function(sum,x){return sum+x.pixel.x;},0)/target.items.length;
         target.cy=target.items.reduce(function(sum,x){return sum+x.pixel.y;},0)/target.items.length;
       }
     });
     groups.forEach(function(group,index){
       if(group.items.length===1){
         window.omniAddUserMarker(group.items[0].point,[0,0]);
         return;
       }
       const clusterKey=group.items.map(function(x){return String(x.point.uid);}).sort().join('|');
       if(window.omniSpiderfyKey===clusterKey && map.getZoom()>=16.5){
         const count=group.items.length,ringRadius=Math.max(27,Math.min(54,18+count*2));
         group.items.forEach(function(item,i){
           const angle=(Math.PI*2*i/count)-Math.PI/2;
           const offset=[Math.cos(angle)*ringRadius,Math.sin(angle)*ringRadius];
           window.omniAddUserMarker(item.point,offset);
         });
         return;
       }
       const center=group.items.reduce(function(sum,item){return [sum[0]+Number(item.point.longitude),sum[1]+Number(item.point.latitude)];},[0,0]);
       center[0]/=group.items.length;center[1]/=group.items.length;
       const element=document.createElement('button');
       element.type='button';
       element.className='omni-cluster-marker';
       element.textContent=String(group.items.length);
       element.setAttribute('aria-label',group.items.length+' usuarios agrupados');
       element.title=group.items.length+' usuarios detectados · acercar para separar';
       const clusterMarker=new maplibregl.Marker({element:element,anchor:'center'}).setLngLat(center).addTo(map);
       element.addEventListener('click',function(event){
         event.preventDefault();event.stopPropagation();
         if(map.getZoom()<16.5){
           map.easeTo({center:center,zoom:Math.min(18,map.getZoom()+2.5),duration:650});
         }else{
           window.omniSpiderfyKey=clusterKey;
           window.omniRenderPresence();
         }
       });
       window.omniClusterMarkers.push(clusterMarker);
     });
   };
   window.omniSetPresence=function(points){
     points=Array.isArray(points)?points.filter(function(point){return point&&point.source!=='DEMO'&&point.status==='online';}):[];
      window.omniPresenceData=points.slice();
     if(!map)return;
     window.omniRenderPresence();
     if(!window.omniHasFitPresence && window.omniPresenceData.length){
       const valid=window.omniPresenceData.filter(function(p){return Number.isFinite(Number(p.latitude))&&Number.isFinite(Number(p.longitude));});
       if(valid.length===1){
         map.easeTo({center:[Number(valid[0].longitude),Number(valid[0].latitude)],zoom:14,duration:900});
       }else if(valid.length>1){
         const bounds=new maplibregl.LngLatBounds();
         valid.forEach(function(p){bounds.extend([Number(p.longitude),Number(p.latitude)]);});
         map.fitBounds(bounds,{padding:70,maxZoom:14,duration:900});
       }
       window.omniHasFitPresence=true;
     }
   };
   map.on('moveend',function(){window.omniSpiderfyKey='';window.omniRenderPresence();});
   map.on('zoomend',function(){window.omniSpiderfyKey='';window.omniRenderPresence();});
   // Wait for Compose/WebView to receive its measured height before resizing MapLibre.
   let resizeObserver=null;
   let resizePoll=null;
   let resizePollCount=0;
   window.omniForceResize=function(reason){
     const host=document.getElementById('map');
     if(!host||!window.omniMap)return;
     const r=host.getBoundingClientRect();
     const vw=document.documentElement.clientWidth;
     const vh=document.documentElement.clientHeight;
     console.log('OMNI RESIZE CHECK',JSON.stringify({reason:reason||'observer',hostWidth:r.width,hostHeight:r.height,viewportWidth:vw,viewportHeight:vh,innerWidth:window.innerWidth,innerHeight:window.innerHeight}));
     if(r.width>1&&r.height>1&&vw>1&&vh>1){window.omniMap.resize();window.omniMap.triggerRepaint();}
   };
   if(typeof ResizeObserver==='function'){
     resizeObserver=new ResizeObserver(function(entries){
       for(const entry of entries){const r=entry.contentRect;if(r.width>1&&r.height>1)window.omniForceResize('ResizeObserver');}
     });
     [document.documentElement,document.body,document.getElementById('map')].forEach(function(el){if(el)resizeObserver.observe(el);});
   }
   window.addEventListener('resize',function(){window.omniForceResize('window-resize');});
   if(window.visualViewport)window.visualViewport.addEventListener('resize',function(){window.omniForceResize('visual-viewport');});
   // Bounded fallback: stop after 12 seconds or as soon as the canvas gets a real area.
   resizePoll=setInterval(function(){
     resizePollCount++;window.omniForceResize('bounded-poll-'+resizePollCount);
     const c=window.omniMap.getCanvas();
     if(c&&c.clientWidth>1&&c.clientHeight>1){clearInterval(resizePoll);resizePoll=null;show('ÁREA DE DIBUJO RECUPERADA · '+c.clientWidth+'×'+c.clientHeight,false);}
     else if(resizePollCount>=120){clearInterval(resizePoll);resizePoll=null;console.error('OMNI RESIZE: timeout; WebView did not provide a positive map area');}
   },100);
   map.addControl(new maplibregl.NavigationControl({visualizePitch:true}),'top-right');
   map.addControl(new maplibregl.ScaleControl({maxWidth:100,unit:'metric'}),'bottom-left');
 }catch(e){window.omniInitError=(e&&e.message)?e.message:String(e);show('ERROR AL INICIAR EL MAPA: '+window.omniInitError,true);console.error('OMNI MAP CONSTRUCTOR ERROR:',e);return;}
 let firstError='';
 map.on('error',function(e){
   const message=(e&&e.error&&e.error.message)?e.error.message:'fallo de red o tesela';
   if(!firstError) firstError=message;
   console.error('OMNI MAP ERROR: '+message);
   show('ERROR DE CARTOGRAFÍA: '+message,true);
 });
 map.on('styledata',function(){
   if(status.textContent.indexOf('ERROR')!==0) show('ESTILO RECIBIDO · CARGANDO CALLES Y TESELAS…',false);
 });
 map.on('load',function(){
   if(window.omniPresenceData)window.omniSetPresence(window.omniPresenceData);
   window.omniForceResize('map-load');
   setTimeout(function(){window.omniForceResize('map-load-250ms');},250);
   setTimeout(function(){window.omniForceResize('map-load-1000ms');},1000);
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
   if(firstError){show('ERROR DE TESSELAS: '+firstError,true);return;}
   try{
     const canvas=map.getCanvas();
     const rect=canvas.getBoundingClientRect();
     const features=map.queryRenderedFeatures();
     const style=map.getStyle();
     const gl=map.painter&&map.painter.context&&map.painter.context.gl;
     const canvasStyle=window.getComputedStyle(canvas);
     const glLost=gl?gl.isContextLost():null;
     const sourceCount=Object.keys(style.sources||{}).length;
     const layerCount=(style.layers||[]).length;
     if(rect.width<2||rect.height<2||canvas.width<2||canvas.height<2){
       show('MAPA SIN ÁREA DE DIBUJO · lienzo '+Math.round(rect.width)+'×'+Math.round(rect.height),true);
     }else if(!map.isStyleLoaded()||sourceCount===0||layerCount===0){
       show('ESTILO INCOMPLETO · fuentes '+sourceCount+' · capas '+layerCount,true);
     }else if(features.length===0){
       show('ESTILO CARGADO, PERO 0 ELEMENTOS DIBUJADOS · revisar teselas/WebGL',true);
     }else{
       show('DIBUJO CONFIRMADO · '+features.length+' ELEMENTOS · '+Math.round(rect.width)+'×'+Math.round(rect.height),false);
     }
     console.log('OMNI MAP RENDER CHECK',JSON.stringify({width:rect.width,height:rect.height,canvasWidth:canvas.width,canvasHeight:canvas.height,features:features.length,sources:sourceCount,layers:layerCount,styleLoaded:map.isStyleLoaded(),webgl:!!gl,webglContextLost:glLost,canvasDisplay:canvasStyle.display,canvasVisibility:canvasStyle.visibility,canvasOpacity:canvasStyle.opacity,canvasZIndex:canvasStyle.zIndex,canvasRect:{x:rect.x,y:rect.y,width:rect.width,height:rect.height}}));
   }catch(checkError){show('ERROR DE DIAGNÓSTICO VISUAL: '+(checkError.message||checkError),true);}
 });
 setTimeout(function(){
   if(status.textContent.indexOf('ERROR')===0 || status.textContent.indexOf('DIBUJO CONFIRMADO')===0 || status.textContent.indexOf('MAPA SIN ÁREA')===0) return;
   try{
     const loaded=map.areTilesLoaded();
     if(loaded && map.isStyleLoaded()) show('ESTILO LISTO · ESPERANDO DIBUJO DE ELEMENTOS',false);
     else show('TIEMPO AGOTADO: el estilo o las teselas no terminaron de cargar. Revisar red, worker y WebGL.',true);
   }catch(timeoutError){show('ERROR AL COMPROBAR LA CARGA: '+(timeoutError.message||timeoutError),true);}
 },25000);
 window.addEventListener('pageshow',function(){window.omniForceResize('pageshow');});
 window.addEventListener('orientationchange',function(){setTimeout(function(){window.omniForceResize('orientationchange');},250);});
})();
</script>
</body>
</html>
""".trimIndent()
