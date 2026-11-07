# OMNI pedidos QR y ventas DEMO — contrato 1.4.5

## Alcance

Flujo integral de pedidos comerciales con OMNI Pay DEMO. No procesa dinero real, no usa pasarelas externas y no requiere credenciales pagas.

## Ciclo del cliente y comercio

1. El cliente arma un pedido en un comercio habilitado y confirma hasta OM$ 50.000 por pedido.
2. OMNI registra el documento en `storeOrders` y genera el QR protegido por nonce. El QR compacto contiene ID, nonce y cantidad de unidades; los artículos y el total se recuperan del pedido autoritativo. El QR no es una autorización de débito.
3. El comercio escanea el QR. OMNI verifica comercio, titular, pedido, nonce, total, artículos y vencimiento.
4. El cliente abre **Mis pedidos** y autoriza el pago con su propia billetera OMNI Pay DEMO. El comercio nunca puede debitar el saldo del cliente.
5. La transferencia ficticia actualiza ambas billeteras y crea una operación estable `orderpay_<orderId>` en `walletOperations`. La operación contiene `orderId`, `correlationId`, `kind=QR_PAYMENT`, `environment=DEMO` y `status=APROBADA · DEMO`.
6. El listener de operaciones actualiza el estado de pago para cliente y comercio.
7. El comercio acepta y prepara el pedido. El despacho (`EN_CAMINO`) y la entrega (`ENTREGADO`) quedan bloqueados en la interfaz hasta que el pago figure `PAGADA · DEMO`.

## Estados del pedido

`RECIBIDO → ACEPTADO → EN_PREPARACION → LISTO → EN_CAMINO (delivery) → ENTREGADO`

Para retiro, desde `LISTO` pasa a `ENTREGADO`. También se contemplan `RECHAZADO` y `CANCELADO` antes del cierre.

## Datos para OMNI Admin

OMNI Admin ya consume `storeOrders`, `walletOperations` y `usageEvents`. La versión 1.4.5 agrega:

- Correlación de telemetría mediante `data.correlationId = orderId` y `data.operationId`.
- Eventos: `STORE_ORDER_CREATED`, `ORDER_QR_GENERATED`, `ORDER_QR_SCAN_STARTED`, `ORDER_QR_VERIFIED`, `ORDER_PAYMENT_COMPLETED` y `ORDER_STATUS_<ESTADO>`.
- El Worker de OMNI Admin (`workers/src/index.js`) relaciona la lista `storeOrders` con operaciones `QR_PAYMENT` por `orderId` y devuelve `paymentStatus`, `paymentEnvironment` y `paymentOperationId`.
- Los importes y datos personales no se copian a la telemetría general; el detalle de venta se obtiene del pedido y de la operación financiera protegida.

## Firebase y despliegue

Se conserva el contrato actual de creación de pedidos de Firestore (`paymentMethod=PAGO_AL_RETIRAR`) por compatibilidad con las reglas ya publicadas; `paymentFlow=OMNI_PAY_DEMO` identifica el nuevo circuito ficticio. No se modifican ni despliegan reglas de Firestore en esta entrega.

El enriquecimiento de pedidos está implementado en el Worker que consume la app Admin. Para que el panel instalado lo muestre, hay que ejecutar manualmente el workflow `Deploy OMNI Admin API to Cloudflare` seleccionando esta rama. El workflow ahora despliega el código de la rama elegida. No se ejecutó ningún despliegue en esta entrega.
