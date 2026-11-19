# OMNI DEMO — seguimiento del trabajo

**Actualizado:** 2026-11-19  
**Rama:** `feat/omni-demo-online-isolated-complete`  
**Último commit de la rama:** `a10124d00df9c38c725befa16c10de6fede6d534`

## Objetivo
Generar dos APK independientes de prueba: **OMNI Principal DEMO** y **OMNI Admin DEMO**, manteniendo intactas las aplicaciones de producción.

## Estado comprobado

| Área | Estado |
|---|---|
| Firebase Principal DEMO | Configuración recibida; proyecto `omni-demo-33675` |
| Firebase Admin Auth | Configuración recibida; proyecto `omni-admin-52e3f` |
| Variantes DEMO de Principal y Admin | Preparadas en Gradle |
| Worker DEMO | Código y configuración aislada preparados; no desplegado |
| Pruebas Worker | **12/12 aprobadas** en GitHub Actions |
| Sintaxis JavaScript del Worker | Correcta |
| YAML de los workflows DEMO | Validado |
| Workflow de compilación Android | Preparado y ahora configurado para ejecutarse al actualizar esta rama |
| APK Principal DEMO | **No generado** |
| APK Admin DEMO | **No generado** |

## Evidencia de pruebas

Última ejecución de pruebas Worker:  
https://github.com/jmredmond262-cmyk/omni-advance-plataform-import/actions/runs/44531903675

Resultado: 12 pruebas aprobadas, 0 fallidas. Se comprobaron, entre otros puntos, la separación del proyecto Firebase DEMO, el rechazo de producción desde DEMO, la validación de pedidos y la idempotencia.

## Bloqueo de compilación Android

El workflow Android requiere secretos/variables de GitHub para las configuraciones Firebase de las dos apps, el proyecto Admin Auth, la URL HTTPS del Worker DEMO y la identidad de firma persistente. No puedo leer desde esta integración si esos secretos ya están cargados ni puedo iniciar manualmente el workflow con las herramientas disponibles. Por eso no afirmo que haya APK ni compilación exitosa.

## Backend

El Worker DEMO no está desplegado. El despliegue requiere credencial de servicio Firebase DEMO y credenciales Cloudflare; no se realizará sin autorización expresa.

## Protección de producción

No se hizo merge a `main`, no se desplegó el Worker, no se publicaron reglas Firebase y no se modificaron secretos de producción.
