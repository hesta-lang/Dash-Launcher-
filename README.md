# Dash Launcher

Dash Launcher es un launcher para Android diseñado para organizar distintas versiones de Geometry Dash desde una sola aplicación.

Permite explorar un catálogo de versiones, consultar sus metadatos, verificar APK descargados y conservar los archivos locales bajo una biblioteca común. Las versiones se guardan una sola vez; cada instancia mantiene su identidad, configuración y respaldos de forma independiente.

## Almacenamiento local estilo Prism Launcher

Dash Launcher separa los archivos compartidos de las instancias:

```text
files/
├── versions/
│   ├── 2.2081/
│   │   ├── game.apk
│   │   ├── metadata.json
│   │   └── active-profile.txt
│   └── 2.2091/
│       ├── game.apk
│       └── metadata.json
├── instances/
│   └── <version-token>--<instance-id>/
│       ├── instance.json
│       ├── instance.apk
│       └── backups/
│           └── game-data.zip
└── cache/
```

- `versions/` es la biblioteca compartida: un APK base verificado por versión.
- `instances/` contiene una carpeta por combinación de versión e instancia.
- `instance.apk` es el artefacto con identidad Android propia que se instala para esa instancia.
- Android mantiene un sandbox privado por paquete instalado.
- `cache/` queda reservada para archivos temporales que pueden reconstruirse.

Consulta [la documentación del almacenamiento local](docs/local-version-storage.md) para conocer las rutas, la migración del formato anterior y los límites actuales.

## Almacenamiento privado centralizado

El acceso al repositorio privado se realiza mediante una GitHub App y un backend
Cloudflare Worker. Dash Launcher inicia sesión en GitHub desde el navegador y
recibe una sesión temporal; no requiere pegar un PAT ni incluye claves privadas
de GitHub dentro del APK.

Consulta [la configuración del backend](backend/cloudflare-worker/README.md) y
[la arquitectura del almacenamiento privado](docs/private-storage-and-profiles.md).
La integración requiere desplegar el Worker y configurar su URL en
`DashBackendConfiguration.kt` antes de compilar una versión funcional.

## Instancias independientes

La implementación experimental crea un paquete Android diferente para cada combinación de versión e instancia. La identidad de paquete se mantiene estable aunque se cambie el nombre visible. Al volver a instalar una instancia con la misma clave local, Android puede actualizar ese paquete y conservar sus datos.

El APK base compartido no se modifica. Dash Launcher genera `instance.apk` dentro de la carpeta de la instancia, actualiza el manifiesto y la tabla de recursos y firma el resultado con una clave persistente de Android Keystore. Android solicita la confirmación de instalación; Dash Launcher no desinstala automáticamente la aplicación original ni las otras instancias.

La firma del clon no es la clave de RobTop. Mientras Dash Launcher conserve su clave de Android Keystore, las reconstrucciones de una misma instancia pueden actualizar el paquete ya instalado. Borrar los datos o desinstalar Dash Launcher puede eliminar esa clave; no se debe hacer mientras se dependa de actualizar instancias existentes.

### Límites de esta fase

- El análisis busca referencias al paquete original dentro de DEX y bibliotecas nativas, pero todavía no las reescribe. Algunas funciones podrían depender de esas referencias y fallar.
- Cada paquete instalado tiene su propio sandbox Android. Los ZIP de `backups/` son respaldos opacos y todavía no restauran el progreso del juego.
- Un fixture de CI valida el mecanismo de clonación, pero no garantiza que una versión real de Geometry Dash funcione al cambiar su identidad de paquete.
- Los APK de origen se conservan sin modificar. Dash Launcher no ofrece ni redistribuye APK de Geometry Dash sin autorización.

La rama `feature/private-storage-profiles` contiene esta implementación experimental y su migración no destructiva desde el almacenamiento de perfiles anterior.
