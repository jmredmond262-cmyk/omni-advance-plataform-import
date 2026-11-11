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
- Los errores de sincronización se registran en Logcat. La interfaz todavía no muestra un indicador visible de sincronización ni una confirmación de guardado.

## Límites y pendientes

Esta etapa modifica el código y las reglas del repositorio. Las reglas todavía no se publicaron en Firebase y no se ejecutó una prueba de integración/emulador. La sincronización debe probarse con dos cuentas distintas antes de habilitarla en una compilación de distribución.
