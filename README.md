# Dash Launcher

Dash Launcher es un launcher para Android diseñado para descargar, instalar y ejecutar distintas versiones de Geometry Dash desde una sola aplicación.

Permite explorar el catálogo de versiones, consultar sus metadatos, verificar los APK descargados y conservar una copia local por versión. Cada versión puede instalarse como un paquete Android independiente, por lo que varias versiones pueden coexistir sin reemplazar la instalación original ni las demás versiones.

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

## Instalaciones paralelas

Al instalar una versión, Dash Launcher prepara una copia independiente del APK y le asigna un identificador Android derivado de su versión. Por ejemplo:

- Geometry Dash 2.2081: `com.hestalang.dashlauncher.gd.v2_2081`
- Geometry Dash 2.2091: `com.hestalang.dashlauncher.gd.v2_2091`

Android las trata como aplicaciones distintas. Cada una tiene su propio espacio de datos y puede iniciarse desde Dash Launcher con el botón **Jugar versión**. La instalación original `com.robtopx.geometryjump` permanece intacta.

El APK original verificado se conserva en `game.apk`. La copia de instalación se guarda en `profile/`, cambia la identidad binaria del manifiesto y de la tabla de recursos, y se firma con la clave local de Dash Launcher. El clonador informa de referencias al paquete original encontradas en DEX o bibliotecas nativas, pero no reescribe esas secciones. Por eso, algunas versiones reales podrían no funcionar con el nuevo identificador.

Las instalaciones derivadas no conservan la firma original de RobTop y no deben considerarse actualizaciones oficiales. El progreso tampoco se copia entre paquetes: cada instalación usa un sandbox distinto. La integración de respaldo y restauración del progreso sigue siendo un trabajo separado.

Dash Launcher no distribuye APK de Geometry Dash sin autorización: las referencias externas del catálogo no se presentan como descargas verificadas.
