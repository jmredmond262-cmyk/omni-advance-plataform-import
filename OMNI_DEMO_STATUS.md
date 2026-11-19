# OMNI DEMO — seguimiento del trabajo

**Actualizado:** 2026-11-19  
**Rama:** `feat/omni-demo-online-isolated-complete`

## Objetivo
Generar dos APK independientes de prueba: **OMNI Principal DEMO** y **OMNI Admin DEMO**, manteniendo intactas las aplicaciones de producción.

## Estado comprobado

| Área | Estado |
|---|---|
| Configuración Firebase Principal DEMO | Recibida y validada para el proyecto `omni-demo-33675` y el paquete DEMO previsto |
| Configuración Firebase Admin | Recibida; corresponde al proyecto `omni-admin-52e3f` |
| Variantes DEMO de Principal y Admin | Preparadas en la rama |
| Configuración del Worker DEMO | Preparada en la rama |
| Workflow de compilación de APK | Existe; ejecución pendiente |
| APK Principal DEMO | **No generado todavía** |
| APK Admin DEMO | **No generado todavía** |
| Worker DEMO desplegado | **No** |

## Qué falta para generar los APK

El workflow requiere configurar/verificar en GitHub las variables y secretos DEMO que consume, incluida la configuración Firebase de ambas aplicaciones, el proyecto de Admin Auth, la URL del Worker DEMO y la clave de firma esperada por el workflow.

Después hay que ejecutar manualmente **Build OMNI DEMO APKs** desde GitHub Actions. Hasta que esa ejecución termine correctamente, no habrá APK ni artefactos para descargar.

## Qué falta para habilitar el backend DEMO

El despliegue del Worker es un paso separado. Requiere credenciales de servicio Firebase DEMO y credenciales de Cloudflare. **No se desplegará ni se publicará nada sin autorización expresa.**

## Seguimiento

- Rama de trabajo: https://github.com/jmredmond262-cmyk/omni-advance-plataform-import/tree/feat/omni-demo-online-isolated-complete
- Ejecuciones y resultados: https://github.com/jmredmond262-cmyk/omni-advance-plataform-import/actions
- Workflow de compilación: `.github/workflows/build-omni-demo-apks.yml`

## Protección de producción

No se ha hecho merge a `main`, no se ha desplegado el Worker, no se han publicado reglas de Firebase y no se han modificado secretos de producción.
