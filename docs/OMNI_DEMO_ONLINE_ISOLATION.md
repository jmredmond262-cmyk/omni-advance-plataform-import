# OMNI DEMO online — aislamiento de infraestructura

## Objetivo

Habilitar pruebas entre teléfonos externos con cuentas, comercios, pedidos, inventario y saldos ficticios, sin conectar el Worker DEMO a la base Firebase de producción.

## Cambios de esta rama

- El Worker resuelve Firestore mediante `OMNI_FIREBASE_PROJECT_ID`; la URL de Firestore ya no está fijada globalmente.
- La cuenta de servicio se valida contra el mismo proyecto seleccionado para Firestore.
- Los tokens de OMNI Principal se validan contra el proyecto Firebase seleccionado.
- La autenticación administrativa sigue siendo independiente y configurable con `OMNI_ADMIN_AUTH_PROJECT_ID`.
- Cuando `OMNI_ENVIRONMENT=DEMO`, el Worker falla cerrado si falta el proyecto DEMO o si se intenta usar `omni-57f69`.
- Existe un Worker Wrangler separado llamado `omni-demo-api`; el Worker productivo conserva su archivo de configuración actual.
- Se agregan pruebas automáticas de selección y bloqueo del proyecto.

## Preparación externa todavía necesaria

1. Proyecto Firebase DEMO creado: `omni-demo-33675`. Falta habilitar Authentication y Firestore en ese proyecto.
2. Habilitar Authentication con los proveedores que usa OMNI Principal y crear Firestore.
3. Aplicar reglas específicas al entorno DEMO. Deben contemplar wallet, comercios, catálogo, pedidos, movimientos, historial y lectura Admin. No publicar reglas de producción a ciegas.
4. Crear una cuenta de servicio restringida al proyecto DEMO.
5. Configurar en GitHub:
   - Variable `OMNI_DEMO_FIREBASE_PROJECT_ID`: ID real del nuevo proyecto.
   - Secret `OMNI_DEMO_FIREBASE_SERVICE_ACCOUNT_BASE64`: JSON de cuenta de servicio DEMO codificado en Base64.
   - Secrets existentes `CLOUDFLARE_API_TOKEN` y `CLOUDFLARE_ACCOUNT_ID`.
6. Ejecutar manualmente el workflow `Deploy isolated OMNI DEMO Worker` desde esta rama.
7. Configurar la variable `OMNI_DEMO_WORKER_BASE_URL` con la URL HTTPS `workers.dev` del Worker DEMO.
8. Registrar las apps Android DEMO en Firebase:
   - Principal: `com.aistudio.omnihub.qtzmvd.demo` dentro del proyecto Firebase DEMO.
   - Admin: `com.omni.adminpanel.demo` dentro del proyecto de autenticación administrativa existente.
9. Guardar sus archivos `google-services.json` codificados en Base64 como `OMNI_DEMO_GOOGLE_SERVICES_JSON_BASE64` y `OMNI_ADMIN_DEMO_GOOGLE_SERVICES_JSON_BASE64`.
10. Ejecutar manualmente `Build OMNI DEMO Android APKs`. El workflow verifica la firma persistente y genera Principal y Admin DEMO como apps separadas, instalables en paralelo con las actuales. La URL productiva no se modifica.

## Seguridad

- El Worker DEMO no puede usar el proyecto `omni-57f69`.
- La cuenta de servicio DEMO debe pertenecer al nuevo proyecto Firebase.
- No guardar claves, tokens, JSON de credenciales ni keystores en Git.
- No conectar pagos bancarios ni fondos reales.
- Mantener Worker, Firebase y datos DEMO separados de producción.

## Estado real

La separación de proyectos en el código, la configuración Wrangler dedicada y el bloqueo fail-closed están implementados. La validación automatizada del Worker está configurada en GitHub Actions. Las variantes Android `demo` para Principal y Admin y su workflow de compilación ya están preparados. La creación del proyecto Firebase, el registro de las dos apps Android, la configuración de secretos, el despliegue Cloudflare y la compilación final de APKs aún no se han realizado.
