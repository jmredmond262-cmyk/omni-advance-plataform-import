# Comprobantes digitales OMNI DEMO

- Cada pago aprobado por OMNI Pay DEMO, tarjeta DEMO o efectivo DEMO genera un comprobante en `storeReceipts/{orderId}` dentro de la misma transacción que confirma el pago y el inventario.
- El identificador del comprobante coincide con el pedido y el número legible es determinista: `OMNI-DEMO-{sufijo del pedido}`. Los reintentos de pago no crean comprobantes adicionales.
- El documento conserva snapshot de comprador, comercio, artículos, cantidades, precios unitarios, subtotal, envío, total, modalidad, medio de pago, fecha de emisión del servidor y entorno DEMO.
- Los comprobantes son inmutables desde clientes. Solo pueden leerlos el comprador, el titular del comercio y administradores OMNI.
- Son comprobantes informativos de una simulación. No son factura, ticket fiscal ni documento con validez tributaria.
- Los pedidos anteriores a esta incorporación no reciben comprobante retroactivo automáticamente. No se ejecuta migración ni seed.
- La interfaz de consulta y los reportes comerciales quedan pendientes de integración/validación en el bloque de persistencia y analíticas; esta etapa incorpora la emisión confiable del registro.
