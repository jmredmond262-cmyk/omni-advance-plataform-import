# OMNI Admin Android

OMNI Admin es una aplicación Android independiente del cliente OMNI.

## Compilación
- Módulo: `:admin`
- Application ID: `com.omni.adminpanel`
- APK debug: `admin/build/outputs/apk/debug/admin-debug.apk`
- La firma de depuración se comparte con la identidad persistente de OMNI para permitir actualizaciones sobre la instalación existente.
- Firebase Authentication usa el proyecto administrativo independiente configurado por `OMNI_ADMIN_AUTH_PROJECT_ID`.
- GitHub Actions genera `admin/google-services.json` desde `OMNI_ADMIN_GOOGLE_SERVICES_JSON_BASE64` y valida que el paquete sea `com.omni.adminpanel`.

## Arquitectura de acceso
1. El administrador inicia sesión con Email/Password en el proyecto Firebase administrativo.
2. El Cloudflare Worker `omni-admin-api` verifica el ID token contra ese proyecto.
3. El Worker consulta `systemAdmins/{UID}` en el Firestore principal `omni-57f69` mediante la cuenta de servicio almacenada en Cloudflare.
4. La autorización exige `enabled: true` y rol `owner` o `admin`.
5. El cliente Android no incluye credenciales de servicio ni acceso directo a Firestore.

## Permisos del panel
- Panel ejecutivo, finanzas DEMO, cuentas, operaciones, comercios, pedidos, eventos, perfiles, opiniones, telemetría y auditoría: solo lectura.
- Módulos, parámetros globales e invitaciones: solo lectura.
- Recuperación de contraseña: se realiza mediante Firebase Authentication.
- No se crean invitaciones, no se editan módulos ni se modifica configuración desde OMNI Admin.
- Las cuentas de autorización `systemAdmins` se aprovisionan manualmente por el propietario.

## API
- Endpoint usado por Android: `https://omni-admin-api.jmredmond262.workers.dev`.
- El Worker de Cloudflare es la API canónica del panel.
- `functions/src/adminApi.ts` se conserva como endpoint legado de solo lectura; Android no lo invoca. No desplegar esa API en paralelo.
- El endpoint `/geo-presence` del Worker es independiente del panel y recibe únicamente la presencia aproximada autorizada por OMNI Principal.

## Límites
- No se habilitan operaciones de dinero real.
- Los saldos y pagos de OMNI Pay siguen siendo DEMO.
- La ubicación administrativa utiliza la información aproximada que el usuario autorizó compartir; no concede al panel acceso a la ubicación precisa del dispositivo.
- Los cambios de reglas y servicios requieren publicación explícita; esta rama solo prepara el código.
