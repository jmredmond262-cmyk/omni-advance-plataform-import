# Catálogo de comercios DEMO OMNI

Este catálogo pertenece exclusivamente al entorno `DEMO` y se almacena en `merchantProfiles` con `environment = DEMO` y `seedVersion = 1`.

## Comercios provisionados

| Comercio ficticio | Perfil | Catálogo inicial | Modalidad de prueba |
|---|---:|---:|---|
| Punto 24 · Kiosco DEMO | MICRO | 8 productos | Retiro |
| Almacén La Esquina · DEMO | PEQUEÑO | 15 productos | Retiro y delivery |
| Panadería Trigal · DEMO | PEQUEÑO | 12 productos | Retiro y delivery programado |
| Supermercado Norte · DEMO | GRANDE | 31 productos | Retiro y delivery |
| Pixel Lab · Tecnología DEMO | ESPECIALIZADO | 10 productos | Retiro y envío coordinado |

Total inicial: 5 comercios ficticios y 76 productos con precios e inventario de prueba.

## Provisionamiento

La callable `provisionDemoMerchants` crea los perfiles de forma idempotente y requiere una cuenta autenticada habilitada en `systemAdmins` con rol `owner` o `admin`.

- No modifica perfiles `REAL`.
- No sobrescribe un comercio DEMO que ya tenga titular.
- No reemplaza un catálogo que ya tenga `seedVersion = 1`.
- Los comercios nuevos comienzan sin titular, sin cashback y con datos ficticios.
- Los clientes solo consultan perfiles DEMO provisionados; el mercado ya no utiliza `SampleData.sampleStores` como respaldo.

## Límites de esta etapa

Los catálogos son una base inicial para pruebas. La emisión de códigos de titularidad, la administración de los comercios reclamados y la conexión de los pedidos con el titular se completan en los puntos siguientes. La callable y las reglas fuente todavía requieren despliegue aprobado para que los documentos aparezcan en Firebase.

Todos los importes son ficticios y se expresan en OM$ de prueba. No representan precios comerciales verificados.