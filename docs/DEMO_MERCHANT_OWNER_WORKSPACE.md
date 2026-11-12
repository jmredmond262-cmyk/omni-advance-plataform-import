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

Completar la validación geográfica del radio de cobertura (requiere ubicación del cliente y del comercio); después cerrar las transiciones, preparación, despacho y entrega. Mantener la billetera y el cashback en modo DEMO, sin acreditaciones de dinero real.
