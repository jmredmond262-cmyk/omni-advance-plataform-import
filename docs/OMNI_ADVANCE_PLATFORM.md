# Organización de Omni Advance Plataform

## 1. Base integrada 1.4

Rama de desarrollo: `omni-advance-1.4`.

Origen Principal: `omni-v1.4-integral` (commit base `c49b7be1455286bb38e363444214abf9daf7a5c9`).

Origen Admin: `omni-admin-v1.4-control-center` (commit base `b3c98b17db3abd625cabecd7c8d62c64c53938e9`).

La rama integrada conserva el árbol completo de Principal y aplica las piezas específicas de Admin Control Center:
- `admin/build.gradle.kts`
- `admin/src/main/java/com/omni/admin/MainActivity.kt`
- `functions/src/adminApi.ts`

Se conserva el workflow combinado de Android y se habilita para la rama integrada. Las ramas importadas no se eliminan ni se reescriben.

## 2. Organización de los módulos

| Componente | Ruta | Application ID |
|---|---|---|
| OMNI Principal | `:app` | `com.aistudio.omnihub.qtzmvd` |
| OMNI Admin | `:admin` | `com.omni.adminpanel` |
| API administrativa | `functions/` | Servicio backend |

El archivo `settings.gradle.kts` ya incluye `:app` y `:admin`. No se duplican proyectos Android ni se mezclan los identificadores de paquete.

## 3. Flujo de versiones

La base permanece en 1.4.0. Los próximos paquetes se numeran 1.4.1, 1.4.2 y sucesivos.

Para cada actualización:
1. Registrar el cambio en la rama `omni-advance-1.4`.
2. Actualizar `versionName` de ambos módulos y las comprobaciones de versión del workflow en el mismo commit o conjunto coordinado.
3. Mantener `versionCode` creciente para instalaciones actualizables.
4. Ejecutar el workflow combinado.
5. Revisar compilación, firma, package IDs y artefactos de las dos APK.
6. Probar las funciones afectadas antes de declarar la entrega estable.

No se crean etiquetas de release ni se declara una 1.4.x estable hasta completar las verificaciones correspondientes.

## 4. Autenticación Admin: revisión pendiente

La rama Control Center importada usa actualmente inicio de sesión Email/Password y un verificador de tokens de un proyecto Firebase de autenticación administrativa separado, según `docs/OMNI_ADMIN_REBUILD.md` y `functions/src/adminApi.ts`.

Esto **no satisface todavía** el objetivo indicado por el propietario de reutilizar la misma identidad Google/Firebase de OMNI Principal. No se cambia el flujo de autenticación en esta etapa para evitar bloquear el acceso a Principal ni invalidar sesiones o permisos.

Antes de corregirlo hay que revisar el flujo completo de ambos clientes, los proyectos Firebase, el verificador backend, la colección `systemAdmins`, las reglas y la estrategia de migración de UID. La solución deberá conservar la autorización de servidor y no ampliar privilegios en el cliente.

## 5. Secretos y compilación

El workflow combinado valida secretos de Firebase y una identidad de firma persistente. Los secretos se administran en GitHub Actions; nunca deben copiarse a archivos versionados ni compartirse en el chat.

La primera compilación de la rama integrada sirve como prueba de integración. Si falla por secretos ausentes, configuración externa o dependencias, se documenta la causa; no se reemplazan credenciales ni se genera una APK presentada como verificada.

## 6. Límites de esta organización

- La rama `main` importada se conserva intacta; la rama de trabajo indicada es `omni-advance-1.4`.
- Las ramas fuente Principal y Admin se mantienen como referencia.
- No se modificaron datos, reglas ni proyectos Firebase.
- No se alteraron los application IDs ni la firma.
- No se realizó todavía una modificación funcional de OMNI.
