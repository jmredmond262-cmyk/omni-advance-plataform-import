# Firma Android de OMNI

## Identidad original validada

- Huella SHA-256 del certificado: `486513DCA524F8C09134DB97A7D5650A2F2461E4306DB181B7C2DFE2D40E495B`
- Se considera la identidad de firma existente que debe preservarse para las actualizaciones.
- No generar otra clave, no sustituir el certificado y no cambiar el package/application ID.

## Ubicación protegida comprobada

- Repositorio que ejecuta el puente de firma: `jmredmond262-cmyk/omni-android-v2`
- Workflow: `.github/workflows/omni-admin-1.4-signing-bridge.yml`
- Secreto utilizado por el workflow: `OMNI_DEBUG_KEYSTORE_BASE64`
- El workflow decodifica el secreto durante la ejecución y comprueba que la huella coincide con la identidad original antes de compilar y firmar.
- El valor del secreto no está documentado ni debe copiarse a este repositorio.

## Evidencia de validación

- Ejecución exitosa: [GitHub Actions run 42149454761](https://github.com/jmredmond262-cmyk/omni-android-v2/actions/runs/42149454761)
- Artefacto publicado por esa ejecución: `omni-admin-v1.4.5-original-signature`
- La ejecución confirmó la validación de la identidad original y la firma del APK.

## Otros workflows

El workflow `.github/workflows/android-build.yml` del repositorio `omni-android-v2` también consulta `OMNI_DEBUG_KEYSTORE_BASE64`. Su ruta alternativa puede generar una clave debug temporal si el secreto no está disponible; **esa alternativa no debe utilizarse para actualizaciones que deban conservar la identidad instalada**.

## Restricciones obligatorias

- No subir `.jks`, `.keystore`, contraseñas ni contenido Base64 de la clave al repositorio.
- No mostrar el valor del secreto en logs.
- No crear una clave nueva para resolver un error de acceso.
- No instalar una APK de otra identidad sobre la instalación existente.
- Antes de construir desde una rama nueva, confirmar que el workflow puente apunta a esa rama y que mantiene la validación de huella.

## Nota de integración

El workflow puente actualmente consulta la referencia `omni-advance-1.4` del repositorio principal. Si se necesita compilar una rama de pruebas, el cambio de referencia debe ser explícito, revisado y aislado; no debe modificar silenciosamente la rama de producción.
