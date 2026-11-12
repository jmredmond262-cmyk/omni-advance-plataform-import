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
- Se limitan las colecciones de catálogo según tamaño DEMO: MICRO 20, PEQUEÑO 50, MEDIANO 100, GRANDE 250, ESPECIALIZADO 50 productos; hasta 30 combos y 50 novedades.
- El repositorio valida antes de guardar nombres, precios, precio anterior, unidad, código de barras, descripción, identificadores duplicados y longitudes de combos/novedades.
- La gestión de pedidos exige que el perfil siga siendo DEMO seedVersion 1 y que el UID titular y el storeId coincidan. Las transiciones operativas se verifican también en Firestore.
- El rechazo de un pedido recibido exige un motivo de 10 a 240 caracteres, que queda visible en el seguimiento del comprador.

## Límites y validación pendiente

- Estas reglas son una protección de servidor; no se desplegaron a Firebase.
- No se ejecutó Emulator Suite ni pruebas instrumentadas en esta etapa.
- La validación de campos de catálogo añadida en esta etapa corre en el cliente antes del guardado. Las reglas de Firestore validan los límites de cantidad, pero todavía no inspeccionan cada mapa de producto individual. Para una garantía anti-manipulación completa hace falta trasladar la escritura del catálogo a una callable que valide cada campo en servidor.
- Los cambios no convierten comercios reales en DEMO y no habilitan escritura en perfiles `REAL`.

## Próximo paso

Conectar y validar las acciones de catálogo del panel con mensajes claros de guardado/error; después abordar el flujo de pedidos del titular. Mantener la billetera y el cashback en modo DEMO, sin acreditaciones de dinero real.
