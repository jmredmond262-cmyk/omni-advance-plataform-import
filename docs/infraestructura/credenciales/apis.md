# APIs e integraciones externas de OMNI

Este documento sirve como registro de servicios externos y de la ubicación de sus credenciales. No contiene valores secretos.

## Registro

| Servicio / integración | Uso previsto o conocido | Ubicación del secreto | Estado |
|---|---|---|---|
| Google Maps / servicios de mapas | Mapas, rutas y búsqueda geográfica, según módulo | GitHub Secrets o configuración segura del entorno que compile/ejecute el módulo | Pendiente de identificar nombre exacto y consumidores |
| Google Sign-In | Autenticación de usuarios Android | Configuración Firebase/Google Cloud; huellas SHA en Firebase | Integración conocida; revisar cada aplicación |
| Gemini / Google AI | Funciones de IA de OMNI y herramientas de comercio | Secreto del entorno/servicio que lo consuma | Pendiente de identificar nombre exacto y consumidores |
| OpenStreetMap / proveedores compatibles | Fuente cartográfica alternativa, si está habilitada | No asumir que requiere una clave; verificar proveedor y endpoint | Pendiente de inventario |
| Servicios meteorológicos | Datos meteorológicos, si están conectados | GitHub Secrets o configuración segura del servicio | Pendiente de identificar proveedor |

## Cómo registrar una integración nueva

Para cada servicio, completar:

- Nombre del proveedor y producto exacto.
- Módulo de OMNI que lo utiliza.
- Nombre del secreto (solo el identificador, nunca su valor).
- Repositorio y entorno donde está configurado.
- Workflow, función backend o aplicación que lo consume.
- Restricciones de cuota, dominio, paquete Android o permisos.
- Estado de prueba y fecha de última verificación.

## Reglas

1. No pegar claves API, tokens OAuth, refresh tokens ni credenciales de servicio en Markdown, código de ejemplo o logs.
2. Preferir claves restringidas por API, aplicación, paquete, firma y entorno cuando el proveedor lo permita.
3. Separar credenciales de desarrollo/prueba de las de producción.
4. No habilitar servicios pagos ni crear credenciales facturables sin autorización expresa.
5. Antes de retirar o rotar una clave, localizar todos sus consumidores en repositorios, workflows y funciones backend.
6. No asumir que una variable con nombre parecido es la misma credencial: verificar su consumidor.

## Pendiente de completar

El inventario de nombres de secretos y consumidores debe cerrarse mediante inspección de workflows y configuración no sensible de los repositorios. Los valores secretos no se consultan ni se copian a este documento.
