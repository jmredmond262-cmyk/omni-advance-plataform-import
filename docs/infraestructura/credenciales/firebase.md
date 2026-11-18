# Firebase de OMNI

## Aplicaciones identificadas

| Aplicación | Application ID / package | Uso |
|---|---|---|
| OMNI principal | `com.aistudio.omnihub.qtzmvd` | Aplicación principal |
| OMNI Admin | `com.omni.adminpanel` | Aplicación administrativa |

## Configuración y dependencias conocidas

- El proyecto utiliza Firebase Authentication y Cloud Firestore.
- Google Sign-In está integrado en OMNI principal.
- El alta de huellas SHA para las aplicaciones Android forma parte de la configuración de autenticación.
- Firestore contiene datos de módulos de OMNI; las reglas publicadas son parte de la configuración operativa y no deben reemplazarse sin revisión.
- App Check y su flujo de depuración han tenido incidencias previas. No regenerar tokens ni cambiar proveedores/configuración como parte de una optimización de consumo sin autorización y pruebas específicas.

## Ubicación de credenciales

Los archivos de configuración públicos de Firebase para clientes Android no equivalen a secretos privados. En cambio, tokens de depuración, credenciales de cuentas de servicio y claves privadas no deben versionarse.

| Elemento | Ubicación segura | Estado |
|---|---|---|
| Configuración cliente Firebase Android | Configuración del proyecto/aplicaciones Android | Pendiente de inventario de rutas exactas |
| Credenciales de cuentas de servicio | GitHub Secrets o Secret Manager, según workflow | Pendiente de identificar consumidores |
| Tokens de App Check de depuración | Entorno local/dispositivo de desarrollo; nunca en Git | No registrar valores |
| Huellas SHA de Android | Configuración de Firebase Console | Deben conservarse al cambiar builds |

## Precauciones

1. No cambiar los application IDs.
2. No borrar ni recrear el proyecto Firebase.
3. No reemplazar reglas de Firestore ni modificar permisos sin una revisión independiente.
4. No publicar archivos de cuenta de servicio ni tokens.
5. Antes de actualizar una app, verificar que conserva el acceso a la misma configuración Firebase y que la actualización no obliga a reinstalar ni pierde datos locales.

## Pendiente de completar

- Identificador del proyecto Firebase (solo el ID no secreto).
- Rutas de los archivos de configuración cliente presentes en cada módulo.
- Lista exacta de workflows que consumen credenciales de Firebase/Google Cloud.
- Proveedores y estado de App Check por aplicación.
