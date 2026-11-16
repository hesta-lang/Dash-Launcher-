# Dash Launcher

Dash Launcher es un launcher para Android diseñado para organizar distintas versiones de Geometry Dash desde una sola aplicación.

Permite explorar un catálogo de versiones, consultar sus metadatos, verificar APK descargados y conservar los archivos locales bajo un único directorio de almacenamiento. Cada versión guardada tiene su propio directorio y una carpeta reservada para su perfil.

## Almacenamiento local

Dash Launcher prepara una carpeta común:

```text
Android/data/com.hestalang.dashlauncher/files/versions/
├── 2.2081/
│   ├── game.apk
│   ├── metadata.json
│   └── profile/
└── 2.2091/
    ├── game.apk
    ├── metadata.json
    └── profile/
```

Los APK solo se guardan después de superar las comprobaciones de integridad e identidad configuradas en el catálogo. El almacenamiento es específico de la aplicación y no requiere permisos generales de archivos.

Consulta [la documentación del almacenamiento local](docs/local-version-storage.md) para conocer la ruta completa, el formato de metadatos y las limitaciones actuales.

## Instalaciones independientes por perfil

La rama de desarrollo incorpora una primera implementación experimental para crear una instalación Android independiente por cada combinación de versión y perfil.

Cada instancia recibe un identificador de paquete propio, por ejemplo:

```text
com.hestalang.dashlauncher.gd.v322e32303831.pdefault
```

Dash Launcher prepara una copia del APK guardado, actualiza el identificador del manifiesto y de la tabla de recursos, ajusta las autoridades de proveedores y otros identificadores del manifiesto, y firma el resultado con una clave persistente del Android Keystore. Android instala esa copia como otra aplicación, sin reemplazar la instalación original ni las demás instancias. El instalador de Android sigue solicitando la confirmación del usuario.

La clave de firma del clon no es la clave de RobTop. Mientras Dash Launcher conserve su clave de Android Keystore, las reconstrucciones de una misma instancia pueden actualizar esa instalación y conservar sus datos. Borrar los datos o desinstalar Dash Launcher puede eliminar esa clave; no se debe hacer mientras se dependa de actualizar instancias ya instaladas.

### Límites de esta primera fase

- La reescritura cubre el manifiesto binario y el nombre del paquete en la tabla de recursos.
- El análisis busca referencias al paquete original en archivos DEX y bibliotecas nativas y muestra advertencias. Todavía no reescribe esas referencias; algunas funciones podrían depender de ellas y fallar.
- Cada paquete instalado tiene su propio sandbox de datos Android. Los archivos ZIP importados/exportados por el gestor de perfiles siguen siendo respaldos y todavía no se aplican automáticamente al progreso dentro del juego.
- Los APK de origen se conservan sin modificar. Dash Launcher no ofrece ni redistribuye APK de Geometry Dash sin autorización.

Esta funcionalidad está en validación en la rama `feature/private-storage-profiles`; que el APK generado supere la verificación de firma no garantiza que cada versión del juego arranque correctamente después de cambiar su identidad.

