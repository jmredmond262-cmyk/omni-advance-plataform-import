# OMNI pedidos QR y ventas DEMO — contrato 1.4.7

## Alcance

Flujo comercial completo dentro de OMNI Principal. OMNI Pay sigue siendo una sección interna de Principal. Todos los saldos, débitos y créditos son ficticios; no se usa dinero real ni una pasarela externa.

## Ciclo obligatorio del cliente y comercio

1. El cliente arma el pedido desde Mercado OMNI y confirma el envío. Se crea un documento único en `storeOrders` con QR de identificación, nonce, artículos, cantidades, precios, comercio, cliente y modalidad de entrega.
2. El comercio escanea el QR y valida que el pedido corresponde a su comercio y a su cuenta titular. El QR no autoriza ni ejecuta pagos.
3. El comercio acepta el pedido y pasa a **Revisión de disponibilidad**. En esta etapa verifica cada artículo.
4. Si todos los artículos están disponibles, confirma disponibilidad y comienza la preparación.
5. Si faltan artículos, informa cuáles no están disponibles y envía al cliente una propuesta con los productos restantes y el nuevo total. El cliente debe aceptar expresamente esa propuesta o cancelar. Mientras espera la decisión, OMNI Pay permanece bloqueado.
6. Una vez confirmada la disponibilidad por el cliente y el comercio, el comercio comienza la preparación y marca el pedido como **LISTO**.
7. Recién cuando el pedido está LISTO, la disponibilidad fue revisada y el comercio solicita el pago, OMNI Pay se habilita en la cuenta del cliente.
8. El cliente selecciona OMNI Pay DEMO y autoriza el pago. El servidor valida pedido, titular, comercio, total definitivo, disponibilidad revisada y solicitud de pago.
9. Una transacción atómica crea un único movimiento `orderpay_<orderId>`, registra el débito ficticio del cliente y el crédito ficticio del comercio. Los reintentos no generan un segundo cobro.
10. Cliente y comercio reciben la confirmación dentro de OMNI. Se emite un ticket de compra para el cliente y un ticket de cobro para el comercio; ambos quedan almacenados en las transacciones de sus billeteras y en `walletOperations`.
11. El comercio registra el despacho o retiro y el cierre del pedido. No se permite entregar antes de que el pago DEMO figure acreditado.

## Estados del pedido

`RECIBIDO → ACEPTADO → EN_REVISION → DISPONIBILIDAD_CONFIRMADA → EN_PREPARACION → LISTO → EN_CAMINO (delivery) → ENTREGADO`

Si faltan artículos:

`EN_REVISION → ESPERA_CONFIRMACION_CLIENTE → CONFIRMADO_CLIENTE → EN_PREPARACION`

También se contemplan `RECHAZADO` y `CANCELADO`. El cliente puede cancelar mientras el pedido está recibido o cuando debe responder una propuesta de disponibilidad.

## Tickets y trazabilidad

- El ticket contiene identificador, pedido, comercio y datos de contacto, cliente, artículos, cantidades, precios, total definitivo, modalidad de entrega, dirección cuando corresponda, método y entorno de pago.
- El ticket del cliente se guarda en la transacción de débito de su billetera.
- El ticket del comercio se guarda en la transacción de crédito de su billetera.
- La operación central `walletOperations` conserva la misma copia de los datos y el identificador de correlación.
- Los cambios de estado, revisión, faltantes, aceptación del cliente y pago generan eventos de trazabilidad en `usageEvents`, visibles desde OMNI Admin.
- OMNI Admin consulta `storeOrders` y `walletOperations` para supervisar estado, importe, ticket y correlación. No duplica OMNI Pay ni procesa pagos.

## Seguridad y compatibilidad

- El servidor y las reglas de Firestore validan los actores y las transiciones permitidas.
- El pago se bloquea hasta que `status=LISTO`, `availabilityReviewed=true`, `paymentRequested=true` y `paymentStatus=PENDIENTE`.
- El ID estable `orderpay_<orderId>` y la escritura atómica evitan dobles débitos y créditos.
- Los tickets se guardan como copias inmutables de los datos comerciales al momento del pago.
- Se mantiene el entorno exclusivamente DEMO, con el saldo ficticio de OMNI Pay.
