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

## Estado de la arquitectura

La carpeta por versión y su almacenamiento de APK ya forman parte del launcher. La carpeta `profile/` es la base para separar los datos de cada versión, pero todavía se necesita una integración con Geometry Dash —por ejemplo, un mecanismo companion de Geode— para exportar e importar el progreso, los ajustes, los niveles y la configuración de mods.

Guardar varios APK en `versions/` no equivale a mantener varias instalaciones Android ejecutándose en paralelo. Android normalmente trata las instalaciones con el mismo identificador de paquete como una sola aplicación. La futura capa de aislamiento tendrá que resolver esa restricción sin acceder directamente a los datos privados de otra aplicación.

Dash Launcher no distribuye APK de Geometry Dash sin autorización: las referencias externas del catálogo no se presentan como descargas verificadas.
