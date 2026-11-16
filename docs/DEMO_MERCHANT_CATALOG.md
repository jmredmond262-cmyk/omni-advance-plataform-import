# Catálogo de comercios DEMO OMNI

Este catálogo pertenece exclusivamente al entorno `DEMO` y se almacena en `merchantProfiles` con `environment = DEMO` y `seedVersion = 1`.

## Comercios provisionados

| Comercio ficticio | Perfil | Catálogo inicial | Modalidad de prueba |
|---|---:|---:|---|
| Punto 24 · Kiosco DEMO | MICRO | 8 productos | Retiro y delivery |
| Almacén La Esquina · DEMO | PEQUEÑO | 15 productos | Retiro y delivery |
| Panadería Trigal · DEMO | PEQUEÑO | 12 productos | Retiro y delivery programado |
| Supermercado Norte · DEMO | GRANDE | 31 productos | Retiro y delivery |
| Pixel Lab · Tecnología DEMO | ESPECIALIZADO | 10 productos | Retiro y delivery coordinado |

Total inicial: 5 comercios ficticios y 76 productos con precios e inventario de prueba.

## Provisionamiento

La callable `provisionDemoMerchants` crea los perfiles de forma idempotente. Puede invocarla cualquier cuenta autenticada porque solo escribe el catálogo fijo ficticio; no asigna titulares ni modifica comercios reales. El Mercado DEMO y el Panel de Comercio solicitan el provisionamiento al detectar que todavía no hay perfiles sembrados.

- No modifica perfiles `REAL`.
- No sobrescribe un comercio DEMO que ya tenga titular.
- No reemplaza un catálogo que ya tenga `seedVersion = 1`.
- Los comercios nuevos comienzan sin titular, sin cashback y con datos ficticios.
- Cada producto inicia con 25 unidades de stock de laboratorio.
- Los cinco comercios habilitan retiro y delivery DEMO, con tarifa, umbral de envío gratis y una zona de cobertura de laboratorio ajustable por el titular.
- Los clientes solo consultan perfiles DEMO provisionados; el mercado ya no utiliza `SampleData.sampleStores` como respaldo.

## Titularidad por código único

La callable `issueDemoMerchantClaimCode` emite un código secreto de un solo uso y con vencimiento de 30 días para un comercio DEMO libre. El panel de comercio muestra la acción de emisión únicamente a cuentas con rol `owner` o `admin` habilitado en `systemAdmins`. El tester ingresa el código en OMNI Principal y el servidor asigna el comercio a su UID; un comercio ya reclamado no puede volver a asignarse.

## Despliegue requerido

La implementación fuente prepara el provisionamiento automático, la emisión y el canje de códigos, el circuito de pedidos y sus reglas de lectura. Para que se active en Firebase todavía es necesario publicar las Cloud Functions y las reglas Firestore de esta rama.

Todos los importes son ficticios y se expresan en OM$ de prueba. No representan precios comerciales verificados.