# OMNI DEMO online — aislamiento de infraestructura

## Objetivo

Permitir pruebas entre teléfonos externos con cuentas, comercios, pedidos, inventario y saldos ficticios, sin conectar el Worker DEMO a la base Firebase de producción.

## Cambios incluidos en esta rama

- El Worker resuelve el proyecto Firestore mediante `OMNI_FIREBASE_PROJECT_ID`.
- La credencial de servicio se valida contra ese mismo proyecto antes de solicitar un token de Google.
- La autenticación de usuarios OMNI Principal valida tokens contra el proyecto indicado por `OMNI_FIREBASE_PROJECT_ID`.
- La autenticación administrativa conserva un proyecto independiente configurable mediante `OMNI_ADMIN_AUTH_PROJECT_ID`.
- Si `OMNI_ENVIRONMENT=DEMO`, el Worker falla cerrado si falta el proyecto Firebase DEMO o si se intenta usar `omni-57f69`.
- Se agrega una configuración Wrangler separada con nombre de Worker `omni-demo-api`; no modifica `workers/wrangler.toml`.

## Configuración pendiente para desplegar

1. Crear un proyecto Firebase independiente, por ejemplo `omni-demo-lab`, en el plan Spark.
2. Habilitar Authentication con los mismos proveedores que utiliza Principal y crear Firestore.
3. Aplicar al proyecto DEMO las reglas necesarias para las colecciones que usa el circuito DEMO. No copiar reglas de producción a ciegas: validar primero las dependencias de OMNI Pay, comercios, pedidos, historial, catálogo y Admin.
4. Crear una cuenta de servicio limitada al proyecto DEMO y guardarla como secreto de Cloudflare `FIREBASE_SERVICE_ACCOUNT_JSON` en el Worker `omni-demo-api`.
5. Configurar `OMNI_FIREBASE_PROJECT_ID` con el ID real del proyecto DEMO y `OMNI_ENVIRONMENT=DEMO`.
6. Configurar el secreto `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID` y `OMNI_DEMO_FIREBASE_SERVICE_ACCOUNT_BASE64` en GitHub Actions si se desea usar el workflow dedicado.
7. Cambiar la URL base del Worker en la variante de la app destinada a pruebas DEMO. La URL productiva no debe modificarse.

## Controles de seguridad

- Nunca usar el proyecto `omni-57f69` para el Worker DEMO.
- No desplegar el Worker DEMO con la cuenta de servicio principal.
- No publicar secretos, archivos JSON de credenciales, keystores ni tokens en Git.
- No compartir el Worker productivo con el Worker DEMO.
- No habilitar pagos bancarios ni dinero real. Los saldos y pagos siguen siendo ficticios.
- Mantener producción sin cambios y validar el entorno DEMO antes de invitar testers.

## Estado

La separación por configuración y el bloqueo fail-closed están implementados en esta rama. La infraestructura externa todavía no está creada ni desplegada. La validación funcional contra un Firebase DEMO real y la compilación Android apuntando al Worker DEMO siguen pendientes.
