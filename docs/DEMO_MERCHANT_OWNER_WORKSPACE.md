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

## Inventario por unidades y trazabilidad

- Cada producto DEMO mantiene `stockQuantity` (entero entre 0 y 100.000); `inStock` se deriva de si quedan unidades. Los perfiles anteriores que todavía no tienen cantidad explícita se interpretan como stock 0 hasta que el titular lo configure; no se inventan existencias.
- El titular puede definir el stock inicial al crear un producto y ajustar la cantidad disponible desde la tarjeta del catálogo. Cada alta o ajuste queda registrado en `merchantProfiles/{storeId}/inventoryMovements`.
- La creación del pedido vuelve a comprobar la disponibilidad y la cantidad solicitada. El descuento definitivo se ejecuta únicamente al confirmar un pago DEMO, ya sea OMNI Pay o tarjeta DEMO.
- El descuento, los movimientos de inventario y el cambio de estado de pago se guardan en la misma transacción Firestore. La actualización concurrente del documento del comercio impide vender más unidades que las disponibles cuando dos compradores intentan comprar el último stock.
- Los movimientos de venta usan identificadores deterministas por pedido y producto; los reintentos no vuelven a descontar unidades.
- Una devolución de pedido pagado restituye las unidades una sola vez y genera movimientos inversos. Los pedidos históricos anteriores a este control no reciben una reposición automática porque no existe evidencia de que se les hubiera descontado inventario.
- El titular ve los últimos movimientos desde el espacio de trabajo del comercio. Solo el titular asignado y OMNI Admin pueden leer el historial; el cliente no puede escribir movimientos directamente.

## Límites y validación pendiente

- Las reglas y la callable son cambios de código; no se desplegaron a Firebase.
- No se ejecutó Emulator Suite ni pruebas instrumentadas en esta etapa.
- Firestore Rules ya no permite que el cliente escriba directamente `inventory`, `comboDeals` o `news`; esas tres colecciones embebidas solo se actualizan mediante la callable validada.
- Los cambios no convierten comercios reales en DEMO y no habilitan escritura en perfiles `REAL`.

## Próximo paso

Continuar con comprobantes digitales y módulos comerciales, reutilizando el pedido, su identificador, el historial y los movimientos DEMO ya existentes. La compilación y validación ejecutable quedan reservadas para la etapa 7.


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
- Retiro: desde `LISTO` el comercio informa la entrega y pasa a `ENTREGADO_POR_COMERCIO`; el comprador confirma la recepción y recién entonces pasa a `ENTREGADO`.
- Delivery: desde `LISTO` pasa a `EN_CAMINO`; al entregar, el comercio informa la entrega (`ENTREGADO_POR_COMERCIO`) y el comprador confirma la recepción (`ENTREGADO`).
- La confirmación final solo la puede realizar el comprador autenticado del pedido y exige que el pago DEMO esté confirmado. El servidor registra `merchantHandoffAt` y `customerReceivedAt`.
- Rechazo: solo desde `RECIBIDO`, con motivo de 10 a 240 caracteres.
- Cancelación del comercio: solo antes del pago y en `ACEPTADO` o `EN_PREPARACION`.
- Los pedidos pagados pueden entrar al circuito de devolución mientras no exista confirmación final del comprador; una vez en `ENTREGADO`, la devolución automática queda bloqueada.
- Los reintentos de una transición ya aplicada devuelven el estado existente sin duplicar eventos.

Cada transición confirmada genera un documento en `storeOrders/{orderId}/statusHistory` con estado anterior/nuevo, actor, rol, comercio, entorno y fecha del servidor. Los eventos distinguen aviso de entrega (`DELIVERY_HANDOFF`) y confirmación de recepción (`DELIVERY_CONFIRMED`). La subcolección es de solo escritura del servidor; los pedidos y su historial existente no se migran ni se sobrescriben.


## Ajustes de uso y consulta de pedidos

- Comprador y titular pueden desplegar la trazabilidad reciente desde la tarjeta del pedido; se muestran estado, actor, fecha y motivo de rechazo cuando corresponde. La lectura está limitada por reglas al comprador, titular o administrador.
- El rechazo desde la lista del comercio abre el diálogo de motivo obligatorio (10–240 caracteres), igual que el rechazo desde el QR; no se permite intentar rechazar sin motivo.
- La captura GPS del comprador aplica el mismo umbral de precisión (hasta 50 m) a latitud y longitud antes de enviar la solicitud.


## Devoluciones de pedidos DEMO pagados

- El titular puede iniciar una devolución con motivo obligatorio (10–240 caracteres) desde el panel del comercio para pedidos pagados aún no entregados, incluso si siguen en estado `RECIBIDO`.
- `OMNI_PAY_DEMO`: una transacción atómica debita el importe total de la billetera del comercio, acredita al comprador el importe neto y revierte el cashback acreditado (saldo, total acumulado, registro de cashback y tope mensual). Si el comercio no tiene saldo suficiente o el cashback ya no está disponible íntegramente para revertir, se bloquea la operación completa sin modificar cuentas.
- `TARJETA_DEMO`: se marca como reversado el registro de autorización ficticia y se cierra el pedido; no se mueven saldos porque la simulación de tarjeta nunca debitó ni acreditó billeteras.
- Se crea una operación de devolución idempotente, movimientos identificados en ambas billeteras cuando corresponde y un evento en `statusHistory`. El pedido queda `CANCELADO` con pago `REEMBOLSADA · DEMO`.
- No se permite la devolución automática de pedidos ya entregados, de pagos con métodos desconocidos ni de comercios reales.
