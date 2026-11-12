# Espacio de trabajo del titular DEMO

## Alcance de esta etapa

El módulo existente `MerchantHubScreen` ya ofrece edición de datos operativos del comercio, catálogo, precios, disponibilidad/stock, combos, novedades, horarios y política de cashback. Esta etapa no duplica esa interfaz: refuerza el perímetro de autorización para que esas operaciones solo puedan persistirse sobre el comercio DEMO asignado al usuario autenticado.

## Reglas aplicadas

- El documento debe ser un comercio `DEMO` provisionado (`seedVersion == 1`) y su `ownerUid` debe coincidir con el UID autenticado.
- El titular no puede cambiar `ownerUid`, `storeId`, `environment`, `seedVersion`, tamaño/capacidad ni metadatos de provisión.
- Solo se aceptan cambios en campos operativos explícitos: datos de atención, horarios, catálogo, combos, novedades, promociones y cashback.
- La creación y eliminación directa de perfiles sigue bloqueada; la provisión y asignación se realizan mediante los flujos confiables ya existentes.
- El circuito de pedidos ya existía; esta etapa endurece la autorización del titular y añade el motivo obligatorio y visible al rechazar.
- La política de cashback conserva validación de porcentaje y topes.
- El titular puede configurar retiro, delivery, radio máximo, costo de envío, umbral de envío gratis, compra mínima y tiempo estimado de preparación.
- Firestore valida los rangos y tipos de esa configuración. La creación de pedidos respeta la modalidad habilitada y la compra mínima. El envío se calcula según la tarifa y el umbral de gratuidad configurados; Firestore y la callable de pago vuelven a validar los importes. El cashback se calcula sobre los productos, no sobre el costo de envío.
- Se limitan las colecciones de catálogo según tamaño DEMO: MICRO 20, PEQUEÑO 50, MEDIANO 100, GRANDE 250, ESPECIALIZADO 50 productos; hasta 30 combos y 50 novedades.
- El catálogo se guarda mediante la callable `saveDemoMerchantCatalog`: el servidor valida titularidad, tamaño del comercio, límites, campos, precios, disponibilidad e identificadores duplicados de productos y novedades. La app conserva una validación previa para dar mensajes rápidos.
- La gestión de pedidos exige que el perfil siga siendo DEMO seedVersion 1 y que el UID titular y el storeId coincidan. Las transiciones operativas se verifican también en Firestore.
- El rechazo de un pedido recibido exige un motivo de 10 a 240 caracteres, que queda visible en el seguimiento del comprador.

## Límites y validación pendiente

- Las reglas y la callable son cambios de código; no se desplegaron a Firebase.
- No se ejecutó Emulator Suite ni pruebas instrumentadas en esta etapa.
- Firestore Rules ya no permite que el cliente escriba directamente `inventory`, `comboDeals` o `news`; esas tres colecciones embebidas solo se actualizan mediante la callable validada.
- Los cambios no convierten comercios reales en DEMO y no habilitan escritura en perfiles `REAL`.

## Próximo paso

Completar la revisión de transiciones, preparación, despacho y entrega. Mantener la billetera y el cashback en modo DEMO, sin acreditaciones de dinero real.


## Cobertura geográfica del delivery DEMO

- El titular configura las coordenadas GPS del comercio junto con el radio de cobertura. La configuración se guarda en el perfil DEMO existente; no se reemplazan perfiles ni catálogos.
- El comprador comparte su ubicación al confirmar un pedido con delivery mediante permiso explícito de Android. La ubicación se guarda solo en ese pedido y no se escribe en `geoPresence`, mapa ni perfil.
- `createDemoStoreOrder` crea pedidos mediante Cloud Functions. Firestore bloquea la creación directa desde clientes; el servidor valida titularidad, catálogo, precios, mínimo, tarifa y distancia Haversine.
- La distancia se compara con `deliveryRadiusKm`, con un margen técnico de 50 metros por imprecisión GPS.
- Tanto `payStoreOrderDemo` como `simulateDemoCardPayment` vuelven a comprobar la cobertura antes de procesar el pago o aprobar la simulación.
- La pasarela de tarjeta DEMO también se movió a Cloud Functions: no acepta datos de tarjeta reales y el cliente ya no actualiza directamente el estado de pago.
- Los pedidos históricos y los comercios reales permanecen intactos.
- Las coordenadas del comercio se ingresan en la configuración logística. No se usa geocodificación paga.


## Ciclo de estados y trazabilidad del pedido

Las transiciones de pedidos DEMO se ejecutan mediante la callable `updateDemoStoreOrderStatus`; el cliente ya no escribe directamente en `storeOrders`. El servidor vuelve a comprobar el comercio DEMO, su versión de provisión, la titularidad y el rol del actor antes de cada cambio.

- Comprador: puede cancelar únicamente un pedido propio que siga en `RECIBIDO` y con pago pendiente.
- Comercio: `RECIBIDO → ACEPTADO → EN_PREPARACION → LISTO`.
- Retiro: desde `LISTO` pasa a `ENTREGADO`.
- Delivery: desde `LISTO` pasa a `EN_CAMINO` y luego a `ENTREGADO`.
- Rechazo: solo desde `RECIBIDO`, con motivo de 10 a 240 caracteres.
- Cancelación del comercio: solo antes del pago y en `ACEPTADO` o `EN_PREPARACION`.
- Pedidos pagados no se rechazan ni cancelan hasta contar con un circuito DEMO de devolución.

Cada transición confirmada genera un documento en `storeOrders/{orderId}/statusHistory` con estado anterior/nuevo, actor, rol, comercio, entorno y fecha del servidor. La subcolección es de solo escritura del servidor; los pedidos y su historial existente no se migran ni se sobrescriben.
