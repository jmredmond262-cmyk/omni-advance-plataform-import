# OMNI — Sincronización de datos personales

## Alcance

La sincronización de esta etapa cubre únicamente los datos personales que el usuario crea o personaliza:

- Tareas diarias.
- Elementos de la lista de compras.
- Contactos personalizados.
- Favoritos de contactos del directorio de muestra.

Los contactos públicos/de muestra no se copian a la cuenta del usuario. Solo se sincronizan sus identificadores favoritos. Los contactos personalizados sí se guardan completos porque fueron ingresados por el usuario.

## Estructura Firestore

`users/{uid}/dailyTasks/{taskId}`

`users/{uid}/shoppingListItems/{itemId}`

`users/{uid}/customContacts/{contactId}`

`users/{uid}/preferences/contactFavorites`

Cada registro contiene `ownerUid` y una marca `updatedAt`. Las reglas limitan lectura, creación, modificación y borrado al UID propietario. No se concede acceso a Admin a estas subcolecciones personales.

## Comportamiento

- La app abre listeners cuando recibe la identidad autenticada.
- Las altas, cambios de estado y eliminaciones se reflejan en Firestore.
- Si hay datos locales en memoria antes del primer inicio de sesión y la cuenta todavía no tiene registros, se intenta migrarlos.
- Si cambia la cuenta autenticada, se limpia el estado personal en memoria antes de conectar la nueva cuenta; no se copian datos de un usuario a otro.
- Las listas de ejemplo se guardan solo cuando el usuario decide cargarlas.
- Los listeners incluyen metadatos de Firestore (origen caché/servidor y escrituras pendientes).
- Una colección vacía proveniente únicamente de caché no se interpreta como una cuenta vacía: se espera la confirmación del servidor antes de migrar datos locales o reemplazar el estado.
- Los callbacks verifican que el UID siga siendo el de la sesión activa; respuestas tardías de una cuenta anterior no pueden sobrescribir el estado de la cuenta nueva.
- La sustitución completa de la lista de compras obtiene primero la colección desde el servidor. Si no hay conexión, falla sin borrar elementos remotos basándose en una caché incompleta.
- La sustitución valida IDs vacíos/duplicados y conserva el límite de 450 escrituras por lote.
- El historial de pedidos ahora mantiene escucha Firestore también en el recorrido DEMO, para que comprador y titular recuperen pedidos existentes al volver a abrir la pantalla; el historial se consulta por UID autenticado y los cambios se reciben con metadatos de caché/servidor.
- Los errores de sincronización se registran en Logcat. La interfaz todavía no muestra un indicador visible de sincronización ni una confirmación de guardado.

## Límites y pendientes

Esta etapa modifica el código y las reglas del repositorio. Las reglas todavía no se publicaron en Firebase y no se ejecutó una prueba de integración/emulador. La sincronización debe probarse con dos cuentas distintas y con el dispositivo offline/online antes de habilitarla en una compilación de distribución. La lista no se reemplaza offline para priorizar la integridad de datos sobre una escritura potencialmente destructiva.
