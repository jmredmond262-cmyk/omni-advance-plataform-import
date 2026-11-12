# Espacio de trabajo del titular DEMO

## Alcance de esta etapa

El módulo existente `MerchantHubScreen` ya ofrece edición de datos operativos del comercio, catálogo, precios, disponibilidad/stock, combos, novedades, horarios y política de cashback. Esta etapa no duplica esa interfaz: refuerza el perímetro de autorización para que esas operaciones solo puedan persistirse sobre el comercio DEMO asignado al usuario autenticado.

## Reglas aplicadas

- El documento debe ser un comercio `DEMO` provisionado (`seedVersion == 1`) y su `ownerUid` debe coincidir con el UID autenticado.
- El titular no puede cambiar `ownerUid`, `storeId`, `environment`, `seedVersion`, tamaño/capacidad ni metadatos de provisión.
- Solo se aceptan cambios en campos operativos explícitos: datos de atención, horarios, catálogo, combos, novedades, promociones y cashback.
- La creación y eliminación directa de perfiles sigue bloqueada; la provisión y asignación se realizan mediante los flujos confiables ya existentes.
- La política de cashback conserva validación de porcentaje y topes.
- Se limitan las colecciones de catálogo según tamaño DEMO: MICRO 20, PEQUEÑO 50, MEDIANO 100, GRANDE 250, ESPECIALIZADO 50 productos; hasta 30 combos y 50 novedades.

## Límites y validación pendiente

- Estas reglas son una protección de servidor; no se desplegaron a Firebase.
- No se ejecutó Emulator Suite ni pruebas instrumentadas en esta etapa.
- La validación detallada de cada campo de producto (nombre, precio, unidad y categoría) debe completarse antes de habilitar operaciones de catálogo fuera del entorno de prueba. El límite de cantidad no sustituye esa validación.
- Los cambios no convierten comercios reales en DEMO y no habilitan escritura en perfiles `REAL`.

## Próximo paso

Conectar y validar las acciones de catálogo del panel con mensajes claros de guardado/error; después abordar el flujo de pedidos del titular. Mantener la billetera y el cashback en modo DEMO, sin acreditaciones de dinero real.
