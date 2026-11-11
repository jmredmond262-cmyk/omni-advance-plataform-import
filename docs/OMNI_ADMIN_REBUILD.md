# OMNI Admin — arquitectura de solo lectura

OMNI Admin es una aplicación Android independiente. Usa un proyecto de autenticación exclusivo y consulta el Firestore principal a través de un Cloudflare Worker. El panel no edita datos operativos.

## Arquitectura

- Android Application ID: `com.omni.adminpanel`.
- Firebase Authentication: proyecto independiente `omni-admin-52e3f`, Email/Password.
- Datos: Firestore principal del proyecto `omni-57f69`.
- API canónica: Cloudflare Worker `omni-admin-api`, publicado en `https://omni-admin-api.jmredmond262.workers.dev`.
- Autorización: el Worker valida el ID token del proyecto administrativo y consulta `systemAdmins/{UID}` en el Firestore principal. El documento exige `enabled: true` y rol `owner` o `admin`.
- El cliente Android no incorpora credenciales de servicio ni permisos directos de Firestore.

## Configuración de Firebase necesaria

1. Mantener el proyecto administrativo exclusivo con Email/Password.
2. Registrar la aplicación Android `com.omni.adminpanel`.
3. En GitHub Actions, mantener los secretos:
   - `OMNI_ADMIN_GOOGLE_SERVICES_JSON_BASE64`: configuración Android del proyecto administrativo.
   - `OMNI_ADMIN_AUTH_PROJECT_ID`: ID del proyecto de autenticación administrativa.
   - `OMNI_PRIMARY_FIREBASE_SERVICE_ACCOUNT_BASE64`: cuenta de servicio del Firestore principal, usada únicamente por el Worker de Cloudflare.
4. Crear la cuenta administrativa en el proyecto de autenticación y copiar su UID.
5. En el Firestore principal, el propietario crea manualmente `systemAdmins/{UID}`:
   ```json
   { "role": "owner", "enabled": true }
   ```

El UID debe pertenecer al proyecto de autenticación administrativa, no al proyecto de OMNI Principal.

## Despliegue del backend

- La API que usa Android es el Cloudflare Worker. El workflow manual correspondiente es **Deploy OMNI Admin API to Cloudflare**.
- El workflow debe ejecutarse seleccionando la rama que contiene el código que se desea publicar.
- No se debe desplegar en paralelo la antigua función HTTPS `omniAdminApi`. Se conserva en el código como legado de solo lectura, pero Android no la invoca.
- No publicar reglas ni servicios sin aprobación explícita del propietario.

## Construcción de la APK

El workflow **OMNI Admin Rebuild** valida la configuración Firebase administrativa, compila `:admin:assembleDebug`, verifica paquete, versión y firma, y publica el artefacto `omni-admin-rebuild-debug-apk`.

La versión declarada actualmente en `admin/build.gradle.kts` es `1.4.5`; el paso de verificación de APK debe coincidir con esa versión.

## Funciones incluidas

- Inicio de sesión Email/Password independiente y recuperación de contraseña.
- Panel ejecutivo y métricas de actividad.
- Lectura de usuarios, billeteras, operaciones, comercios, pedidos y comentarios.
- Lectura de eventos, momentos, perfiles profesionales, módulos e invitaciones.
- Lectura de parámetros globales y auditoría.
- Mapa administrativo de presencia aproximada autorizada.
- Sin botones de alta, edición, activación, desactivación o eliminación de datos operativos.

## Límites explícitos

- No hay operaciones de dinero real.
- La pasarela de tarjetas es una simulación DEMO y no mueve dinero.
- Los listados se muestran como muestras paginadas; no deben presentarse como conteos completos cuando la API informa límites.
- La configuración global se consulta, pero no controla todavía los límites ni el modo mantenimiento de OMNI Principal.
- El panel no accede a las subcolecciones personales de tareas, listas ni contactos.
