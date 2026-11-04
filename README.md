# Omni Advance Plataform

Repositorio de trabajo para OMNI Principal y OMNI Admin, línea 1.4.

## Estructura de aplicaciones

- **OMNI Principal**: módulo Android `:app`, package `com.aistudio.omnihub.qtzmvd`.
- **OMNI Admin – Control Center**: módulo Android `:admin`, package `com.omni.adminpanel`.
- **Backend administrativo**: `functions/`.
- **Documentación y automatizaciones**: `docs/` y `.github/workflows/`.

Ambas aplicaciones se mantienen en el mismo proyecto Gradle, pero se compilan y publican como APK independientes. El workflow combinado también permite obtener ambas APK desde una misma ejecución de GitHub Actions.

## Rama de trabajo

La rama integrada de desarrollo es `omni-advance-1.4`.

Se construyó tomando OMNI Principal desde `omni-v1.4-integral` y aplicando únicamente los cuatro archivos específicos de la versión OMNI Admin Control Center seleccionada: configuración Gradle del módulo Admin, pantalla principal Admin, API administrativa y workflow administrativo de referencia. El workflow combinado de Principal se conserva y se habilitó para esta rama.

Las ramas importadas de origen se conservan como respaldo y referencia. No trabajar directamente sobre ellas para nuevas mejoras.

## Versionado

- La base importada conserva `versionName 1.4.0`.
- Las mejoras se entregarán como `1.4.1`, `1.4.2`, etc.
- Cada incremento de versión debe actualizar de forma coordinada los dos módulos Android y las verificaciones de versión de GitHub Actions.
- El `versionCode` de CI utiliza el número de ejecución de GitHub Actions.
- No cambiar application IDs ni identidad de firma sin una decisión explícita y una verificación de compatibilidad de actualización.

## Compilación

Workflow principal: `.github/workflows/android-build.yml`.

En cada push a `omni-advance-1.4`, GitHub Actions intenta compilar y verificar OMNI Principal y OMNI Admin, y publica ambos APK como artefactos si la ejecución finaliza correctamente.

La compilación de CI requiere los secretos que el workflow valida, incluyendo las configuraciones Firebase de ambas aplicaciones y la clave de firma persistente. No se deben subir archivos de credenciales, claves privadas, tokens ni archivos `.env` al repositorio.

No se considera una entrega terminada hasta revisar el resultado de GitHub Actions, los dos APK y las pruebas funcionales de los cambios.

## Reglas de trabajo

1. Mantener intactos los repositorios originales y las ramas importadas.
2. Desarrollar las mejoras nuevas en `omni-advance-1.4`.
3. No reemplazar ni borrar datos de Firebase.
4. No cambiar autenticación, application IDs, firma o persistencia sin revisar impacto y autorización.
5. No incorporar servicios pagos ni credenciales pagas sin aprobación.
6. Registrar cada entrega y sus pruebas en la documentación de cambios.

Ver `docs/OMNI_ADVANCE_PLATFORM.md` para la organización, los límites conocidos y el plan de revisión inicial.
