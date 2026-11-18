# Inventario de infraestructura y credenciales de OMNI

Este directorio documenta **qué integraciones existen, dónde se configuran y qué proceso las utiliza**. No es un almacén de secretos.

## Reglas de seguridad

- No guardar claves privadas, contraseñas, tokens, keystores, archivos `.jks`/`.keystore`, JSON de cuentas de servicio ni valores de GitHub Secrets en este repositorio.
- Los valores sensibles deben permanecer en GitHub Actions Secrets, Firebase/Google Cloud Secret Manager o el gestor seguro correspondiente.
- Documentar solamente el nombre del secreto, el repositorio/entorno donde está configurado, su finalidad y el workflow que lo consume.
- Nunca imprimir valores secretos en logs, commits, issues o artefactos.
- Antes de cambiar una credencial, comprobar qué aplicaciones y workflows dependen de ella. No regenerar ni sustituir la identidad de firma de OMNI.

## Documentos

| Documento | Contenido |
|---|---|
| [Firebase](firebase.md) | Aplicaciones, paquetes y puntos de configuración Firebase conocidos |
| [Firma Android](android-signing.md) | Identidad de firma validada y ubicación protegida del material de firma |
| [APIs e integraciones](apis.md) | Inventario de servicios y registro seguro de sus credenciales |

## Repositorios identificados

- Aplicación y trabajo principal: `jmredmond262-cmyk/omni-advance-plataform-import`
- Construcción y puente de firma Android: `jmredmond262-cmyk/omni-android-v2`

## Estado de verificación

La información se limita a referencias comprobadas en workflows y contexto técnico del proyecto. Los campos indicados como **pendientes de inventario** deben completarse mediante lectura de configuración no sensible; no deben completarse adivinando valores.
