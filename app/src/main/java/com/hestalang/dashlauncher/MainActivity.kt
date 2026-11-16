package com.hestalang.dashlauncher

import android.content.Context
import android.content.Intent
import android.app.DownloadManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hestalang.dashlauncher.install.GeometryDashInstallManager
import com.hestalang.dashlauncher.install.InstallRequestResult
import com.hestalang.dashlauncher.install.InstalledGeometryDash
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.hestalang.dashlauncher.data.CatalogVersionMetadata
import com.hestalang.dashlauncher.data.CatalogVersionRecord
import com.hestalang.dashlauncher.data.RemoteVersionCatalogRepository
import com.hestalang.dashlauncher.data.VersionEntry
import com.hestalang.dashlauncher.download.ApkDownloadCoordinator
import com.hestalang.dashlauncher.download.CatalogDirectDownloadProvider
import com.hestalang.dashlauncher.download.ExternalCatalogPageProvider
import com.hestalang.dashlauncher.download.DownloadProviderRegistry
import com.hestalang.dashlauncher.download.DownloadResolution
import com.hestalang.dashlauncher.download.DownloadStatus
import com.hestalang.dashlauncher.download.DownloadVerification
import com.hestalang.dashlauncher.storage.VersionStorageManager
import com.hestalang.dashlauncher.storage.VersionStorageSnapshot
import com.hestalang.dashlauncher.storage.GameProfileManager
import com.hestalang.dashlauncher.security.GitHubSessionStore
import com.hestalang.dashlauncher.private_storage.GitHubPrivateStorageRepository
import com.hestalang.dashlauncher.private_storage.PrivateVersionInfo
import com.hestalang.dashlauncher.private_storage.GitHubOAuthPkce
import com.hestalang.dashlauncher.private_storage.PrivateApkDownloadManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

private fun formatDownloadMegabytes(bytes: Long): Long =
    bytes.coerceAtLeast(0L) / (1024L * 1024L)

private const val SETTINGS_FILE = "dash_launcher_settings"
private const val THEME_PREFERENCE = "app_theme"

private val LightColors = lightColorScheme()
private val DarkColors = darkColorScheme()

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DashLauncherRoot()
        }
    }
}

private enum class AppThemeOption(
    val preferenceValue: String,
    val title: String,
    val description: String
) {
    SYSTEM("system", "Sistema", "Se adapta al modo claro u oscuro del teléfono."),
    LIGHT("light", "Claro", "Mantiene Dash Launcher en modo claro."),
    DARK("dark", "Oscuro", "Mantiene Dash Launcher en modo oscuro.");

    companion object {
        fun fromPreference(value: String?): AppThemeOption =
            entries.firstOrNull { it.preferenceValue == value } ?: SYSTEM
    }
}

private enum class CatalogChannel(val key: String, val title: String) {
    Stable("stable", "Stable"),
    Beta("beta", "Beta"),
    Archived("archived", "Archived")
}

private val catalogChannels = CatalogChannel.entries

@Composable
private fun DashLauncherRoot() {
    val context = LocalContext.current
    val preferences = remember(context) {
        context.applicationContext.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)
    }
    var selectedTheme by rememberSaveable { mutableStateOf(AppThemeOption.SYSTEM) }

    LaunchedEffect(preferences) {
        val savedValue = withContext(Dispatchers.IO) {
            preferences.getString(THEME_PREFERENCE, AppThemeOption.SYSTEM.preferenceValue)
        }
        selectedTheme = AppThemeOption.fromPreference(savedValue)
    }

    val darkTheme = when (selectedTheme) {
        AppThemeOption.SYSTEM -> isSystemInDarkTheme()
        AppThemeOption.LIGHT -> false
        AppThemeOption.DARK -> true
    }

    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors) {
        DashLauncherApp(
            selectedTheme = selectedTheme,
            onThemeSelected = { theme ->
                selectedTheme = theme
                preferences.edit().putString(THEME_PREFERENCE, theme.preferenceValue).apply()
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashLauncherApp(
    selectedTheme: AppThemeOption,
    onThemeSelected: (AppThemeOption) -> Unit
) {
    var selectedDestination by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            when (selectedDestination) {
                                0 -> "Dash Launcher"
                                1 -> "Perfiles"
                                else -> "Configuración"
                            },
                            fontWeight = FontWeight.Bold
                        )
                        if (selectedDestination == 0) {
                            Text(
                                "Geometry Dash version manager",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            SimpleBottomBar(
                selectedDestination = selectedDestination,
                onDestinationSelected = { selectedDestination = it }
            )
        }
    ) { insets ->
        when (selectedDestination) {
            0 -> VersionsScreen(Modifier.fillMaxSize().padding(insets))
            1 -> ProfilesScreen(Modifier.fillMaxSize().padding(insets))
            else -> SettingsScreen(
                modifier = Modifier.fillMaxSize().padding(insets),
                selectedTheme = selectedTheme,
                onThemeSelected = onThemeSelected
            )
        }
    }
}

@Composable
private fun SimpleBottomBar(
    selectedDestination: Int,
    onDestinationSelected: (Int) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BottomBarItem(
                modifier = Modifier.weight(1f),
                title = "Versiones",
                iconId = R.drawable.ic_versions,
                selected = selectedDestination == 0,
                onClick = { onDestinationSelected(0) }
            )
            BottomBarItem(
                modifier = Modifier.weight(1f),
                title = "Perfiles",
                iconId = R.drawable.ic_profiles,
                selected = selectedDestination == 1,
                onClick = { onDestinationSelected(1) }
            )
            BottomBarItem(
                modifier = Modifier.weight(1f),
                title = "Configuración",
                iconId = R.drawable.ic_settings,
                selected = selectedDestination == 2,
                onClick = { onDestinationSelected(2) }
            )
        }
    }
}

@Composable
private fun BottomBarItem(
    modifier: Modifier,
    title: String,
    iconId: Int,
    selected: Boolean,
    onClick: () -> Unit
) {
    val container = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val content = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = modifier
            .height(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            color = container,
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(
                painter = painterResource(iconId),
                contentDescription = null,
                tint = content,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp)
            )
        }
        Text(
            title,
            color = content,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1
        )
    }
}

@Composable
private fun VersionsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repository = remember(context) { RemoteVersionCatalogRepository(context) }
    val storageManager = remember(context) { VersionStorageManager(context) }

    var storageRefreshCount by rememberSaveable { mutableIntStateOf(0) }
    var storageSnapshot by remember { mutableStateOf<VersionStorageSnapshot?>(null) }
    var storageError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(storageManager, storageRefreshCount) {
        runCatching {
            withContext(Dispatchers.IO) { storageManager.initialize() }
        }.onSuccess { snapshot ->
            storageSnapshot = snapshot
            storageError = null
        }.onFailure { error ->
            storageError = error.message ?: "No se pudo inicializar el almacenamiento local."
        }
    }

    var selectedChannel by rememberSaveable { mutableIntStateOf(0) }
    var searchText by rememberSaveable { mutableStateOf("") }
    var refreshCounter by rememberSaveable { mutableIntStateOf(0) }
    var forceNextRefresh by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var cacheWarning by remember { mutableStateOf<String?>(null) }
    var versions by remember { mutableStateOf<List<CatalogVersionRecord>>(emptyList()) }

    LaunchedEffect(selectedChannel, refreshCounter) {
        isLoading = true
        loadError = null
        cacheWarning = null
        val forceRefresh = forceNextRefresh
        forceNextRefresh = false

        runCatching {
            withContext(Dispatchers.IO) {
                repository.loadChannel(catalogChannels[selectedChannel].key, forceRefresh)
            }
        }.onSuccess { snapshot ->
            versions = snapshot.versions
            cacheWarning = snapshot.warning
        }.onFailure { error ->
            versions = emptyList()
            loadError = error.message ?: "No se pudo cargar el catálogo."
        }
        isLoading = false
    }

    val filteredVersions = remember(versions, searchText) {
        versions.filter { version ->
            version.displayName.contains(searchText, ignoreCase = true) ||
                version.version.contains(searchText, ignoreCase = true) ||
                version.changelog.orEmpty().contains(searchText, ignoreCase = true)
        }
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PrivateStorageVersionsCard(onApkStored = { storageRefreshCount++ })

        LocalVersionStorageCard(
            snapshot = storageSnapshot,
            error = storageError,
            onRefresh = { storageRefreshCount++ }
        )

        OutlinedTextField(
            value = searchText,
            onValueChange = { searchText = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = {
                Icon(
                    painter = painterResource(R.drawable.ic_search),
                    contentDescription = null
                )
            },
            placeholder = { Text("Buscar versión…") },
            shape = RoundedCornerShape(14.dp)
        )

        ChannelSelector(
            selectedChannel = selectedChannel,
            onChannelSelected = { selectedChannel = it }
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Catálogo de versiones",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    catalogChannels[selectedChannel].title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(
                onClick = {
                    forceNextRefresh = true
                    refreshCounter++
                },
                enabled = !isLoading
            ) {
                Text("Actualizar")
            }
        }

        if (isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                "Consultando el catálogo…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        cacheWarning?.let { warning ->
            Text(
                warning,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        loadError?.let { error ->
            Text(
                error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }

        when {
            isLoading -> Unit
            loadError != null -> Unit
            filteredVersions.isEmpty() -> {
                Text(
                    if (versions.isEmpty()) {
                        "Este canal todavía no tiene versiones publicadas en el catálogo."
                    } else {
                        "No hay versiones que coincidan con la búsqueda."
                    },
                    modifier = Modifier.padding(vertical = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            else -> filteredVersions.forEach { version ->
                CatalogVersionCard(
                    record = version,
                    repository = repository,
                    onApkStored = { storageRefreshCount++ }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun LocalVersionStorageCard(
    snapshot: VersionStorageSnapshot?,
    error: String?,
    onRefresh: () -> Unit
) {
    val context = LocalContext.current
    val installManager = remember(context) { GeometryDashInstallManager(context) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    var installedGame by remember { mutableStateOf<InstalledGeometryDash?>(null) }
    var actionMessage by remember { mutableStateOf<String?>(null) }

    val refreshInstalledGame: () -> Unit = {
        scope.launch {
            installedGame = withContext(Dispatchers.IO) {
                installManager.installedGameInfo()
            }
        }
    }

    LaunchedEffect(installManager) { refreshInstalledGame() }

    DisposableEffect(lifecycleOwner, installManager) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshInstalledGame()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Almacenamiento local",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Biblioteca de APK originales guardados por versión",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(
                    onClick = {
                        onRefresh()
                        refreshInstalledGame()
                    },
                    enabled = snapshot != null
                ) { Text("Escanear") }
            }

            Text(
                if (installedGame == null) {
                    "La instalación original de Geometry Dash no está instalada."
                } else {
                    "Instalación original detectada • " +
                        (installedGame?.versionName ?: "versión desconocida") +
                        " • código " + installedGame?.versionCode
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (installedGame != null) {
                Button(
                    onClick = {
                        runCatching { installManager.launchInstalledGame() }
                            .onFailure {
                                actionMessage = it.message ?: "No se pudo iniciar Geometry Dash."
                            }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Jugar instalación original") }
            }

            when {
                error != null -> Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                snapshot == null -> Text(
                    "Preparando la carpeta de versiones…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> {
                    Text(
                        snapshot.rootDirectory.absolutePath,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        if (snapshot.versions.isEmpty()) {
                            "Carpeta lista. Todavía no hay APK guardados."
                        } else {
                            snapshot.versions.size.toString() +
                                " versión(es) guardadas. Cada perfil puede generar su propia instalación."
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                    snapshot.versions.forEach { localVersion ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .padding(vertical = 5.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                localVersion.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                localVersion.version + " • " +
                                    if (localVersion.hasApk) "APK original guardado" else "APK pendiente",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Text(
                "Las instalaciones independientes se crean desde Perfiles. La instalación original de Geometry Dash no se reemplaza.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            actionMessage?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun PrivateStorageVersionsCard(onApkStored: () -> Unit) {
    val context = LocalContext.current
    val sessionStore = remember(context) { GitHubSessionStore(context) }
    val repository = remember(context) { GitHubPrivateStorageRepository(context) }
    val downloadManager = remember(context) { lazy(LazyThreadSafetyMode.NONE) { PrivateApkDownloadManager(context) } }
    val downloadScope = androidx.compose.runtime.rememberCoroutineScope()

    var sessionToken by remember { mutableStateOf<String?>(null) }
    var versions by remember { mutableStateOf<List<PrivateVersionInfo>>(emptyList()) }
    var isRefreshing by remember { mutableStateOf(false) }
    var refreshCounter by rememberSaveable { mutableIntStateOf(0) }
    var downloading by remember { mutableStateOf<PrivateVersionInfo?>(null) }
    var activeDownloadId by remember { mutableStateOf<Long?>(null) }
    var downloadedBytes by remember { mutableStateOf(0L) }
    var downloadTotalBytes by remember { mutableStateOf(0L) }
    var isVerifyingDownload by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }

    val lifecycleOwner = LocalLifecycleOwner.current
    var resumeRefresh by remember { mutableIntStateOf(0) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeRefresh++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(sessionStore, resumeRefresh) {
        runCatching {
            withContext(Dispatchers.IO) { sessionStore.readSession() }
        }.onSuccess {
            sessionToken = it
        }.onFailure {
            sessionToken = null
            loadError = it.message ?: "No se pudo leer la sesión del almacenamiento privado."
        }
    }

    LaunchedEffect(refreshCounter, sessionToken) {
        val accessToken = sessionToken ?: return@LaunchedEffect
        isRefreshing = true
        loadError = null
        runCatching {
            withContext(Dispatchers.IO) { repository.loadVersions(accessToken) }
        }.onSuccess {
            versions = it
        }.onFailure {
            loadError = it.message ?: "No se pudo consultar el catálogo privado."
        }
        isRefreshing = false
    }

    LaunchedEffect(Unit) {
        // Restoring DownloadManager state must never take down the first screen.
        val restored = withContext(Dispatchers.IO) {
            runCatching {
                val active = downloadManager.value.activeDownload()
                val message = if (active == null) downloadManager.value.resultMessage() else null
                active to message
            }
        }
        restored.onSuccess { (active, message) ->
            if (active != null) {
                downloading = active.version
                activeDownloadId = active.id
                statusMessage = "Descarga en curso: ${active.version.displayName}."
            } else {
                statusMessage = message
                if (statusMessage?.startsWith("APK verificado y guardado") == true) onApkStored()
            }
        }.onFailure { error ->
            downloading = null
            activeDownloadId = null
            statusMessage = "No se pudo restaurar el estado de descargas: " +
                (error.message ?: "error del gestor de Android")
        }
    }

    LaunchedEffect(activeDownloadId) {
        val id = activeDownloadId ?: return@LaunchedEffect
        var fallbackVerificationScheduled = false
        while (true) {
            val snapshot = withContext(Dispatchers.IO) { downloadManager.value.query(id) }
            val active = withContext(Dispatchers.IO) { downloadManager.value.activeDownload() }
            if (active?.id != id) {
                statusMessage = withContext(Dispatchers.IO) { downloadManager.value.resultMessage() }
                if (statusMessage?.startsWith("APK verificado y guardado") == true) onApkStored()
                downloading = null
                activeDownloadId = null
                isVerifyingDownload = false
                break
            }
            if (snapshot == null) {
                statusMessage = "Android está preparando el estado de la descarga…"
            } else {
                downloadedBytes = snapshot.downloadedBytes.coerceAtLeast(0L)
                downloadTotalBytes = if (snapshot.totalBytes > 0L) snapshot.totalBytes else downloading?.sizeBytes ?: 0L
                when (snapshot.status) {
                    DownloadManager.STATUS_SUCCESSFUL,
                    DownloadManager.STATUS_FAILED -> {
                        isVerifyingDownload = true
                        if (!fallbackVerificationScheduled) {
                            withContext(Dispatchers.IO) {
                                downloadManager.value.scheduleVerification(id)
                            }
                            fallbackVerificationScheduled = true
                        }
                        statusMessage = withContext(Dispatchers.IO) {
                            downloadManager.value.verificationStage(id)
                        } ?: if (snapshot.status == DownloadManager.STATUS_SUCCESSFUL) {
                            "Descarga completa. Esperando que comience la verificación…"
                        } else {
                            "Descarga terminada con error. Comprobando el resultado…"
                        }
                    }
                    DownloadManager.STATUS_PAUSED -> statusMessage = "Descarga pausada por Android; se reanudará cuando sea posible."
                    DownloadManager.STATUS_PENDING,
                    DownloadManager.STATUS_RUNNING -> statusMessage = "Descargando en segundo plano…"
                }
            }
            delay(700)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    "Dash Launcher Private Storage",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Catálogo de APK privado, servido por el backend seguro de Dash Launcher.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (sessionToken == null) {
                Text(
                    "Inicia sesión con GitHub desde Configuración → Almacenamiento privado para consultar el repositorio privado.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Button(
                    onClick = { refreshCounter++ },
                    enabled = !isRefreshing && downloading == null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isRefreshing) "Consultando…" else "Consultar almacenamiento privado")
                }
                if (isRefreshing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                loadError?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                versions.forEach { item ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(item.displayName, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Versión ${item.version} • ${item.sizeBytes / (1024L * 1024L)} MiB • ${item.supportedAbis.joinToString()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (!item.officialSignatureVerified) {
                                Text(
                                    "Firma válida, pero NO verificada como firma oficial de RobTop.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            Text(item.warning, style = MaterialTheme.typography.bodySmall)
                            Button(
                                onClick = {
                                    val accessToken = sessionToken
                                    if (accessToken == null) {
                                        statusMessage = "Conecta el almacenamiento privado antes de descargar."
                                    } else {
                                        downloading = item
                                        downloadedBytes = 0L
                                        downloadTotalBytes = item.sizeBytes
                                        isVerifyingDownload = false
                                        statusMessage = "Preparando descarga segura…"
                                        downloadScope.launch {
                                            runCatching {
                                                withContext(Dispatchers.IO) {
                                                    downloadManager.value.enqueue(accessToken, item)
                                                }
                                            }.onSuccess { id ->
                                                activeDownloadId = id
                                                statusMessage = "Descarga iniciada por Android. Puedes salir de Dash Launcher."
                                            }.onFailure { error ->
                                                downloading = null
                                                statusMessage = error.message ?: "No se pudo iniciar la descarga."
                                            }
                                        }
                                    }
                                },
                                enabled = activeDownloadId == null && downloading == null &&
                                    Build.VERSION.SDK_INT >= item.minAndroidApi &&
                                    item.supportedAbis.any { Build.SUPPORTED_ABIS.contains(it) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (downloading?.id == item.id) "Descargando…" else "Descargar y guardar")
                            }
                            if (downloading?.id == item.id) {
                                val fraction = if (downloadTotalBytes > 0L) {
                                    (downloadedBytes.toFloat() / downloadTotalBytes.toFloat()).coerceIn(0f, 1f)
                                } else {
                                    0f
                                }
                                LinearProgressIndicator(
                                    progress = fraction,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    "${formatDownloadMegabytes(downloadedBytes)} MB / ${formatDownloadMegabytes(downloadTotalBytes)} MB",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    if (isVerifyingDownload) "Descarga completa. Verificando el APK…"
                                    else "Descargando…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                if (versions.isEmpty() && !isRefreshing && loadError == null) {
                    Text(
                        "El catálogo privado no contiene versiones todavía.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            statusMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PrivateStorageSettingsCard() {
    val context = LocalContext.current
    val sessionStore = remember(context) { GitHubSessionStore(context) }
    val repository = remember(context) { GitHubPrivateStorageRepository(context) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    var connected by remember { mutableStateOf(false) }
    var isConnecting by remember { mutableStateOf(false) }
    var refreshEpoch by remember { mutableIntStateOf(0) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshEpoch++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(sessionStore, refreshEpoch) {
        connected = withContext(Dispatchers.IO) { sessionStore.hasSession() }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Text(
                "Almacenamiento privado",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "Conecta Dash Launcher con tu cuenta de GitHub. El acceso al repositorio se realiza desde un backend seguro; no necesitas crear ni pegar un token.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = {
                    isConnecting = true
                    statusMessage = "Preparando el inicio de sesión seguro…"
                    scope.launch {
                        runCatching {
                            val authUrl = withContext(Dispatchers.IO) {
                                val verifier = GitHubOAuthPkce.newVerifier()
                                sessionStore.savePendingVerifier(verifier)
                                repository.createLoginUrl(GitHubOAuthPkce.challenge(verifier))
                            }
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)))
                        }.onSuccess {
                            statusMessage = "Completa el inicio de sesión de GitHub en el navegador y vuelve a Dash Launcher."
                        }.onFailure {
                            sessionStore.clearPendingVerifier()
                            statusMessage = it.message ?: "No se pudo iniciar el inicio de sesión."
                        }
                        isConnecting = false
                    }
                },
                enabled = !isConnecting,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isConnecting) "Preparando…" else if (connected) "Volver a conectar GitHub" else "Conectar con GitHub")
            }

            if (connected) {
                Text(
                    "Sesión de Dash Launcher guardada de forma cifrada en Android Keystore.",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall
                )
                Button(
                    onClick = {
                        runCatching { sessionStore.clearSession() }
                            .onSuccess {
                                connected = false
                                statusMessage = "Sesión desconectada de este dispositivo."
                            }
                            .onFailure { statusMessage = it.message }
                    },
                    enabled = !isConnecting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Desconectar")
                }
            } else {
                Text(
                    "No hay una sesión conectada. El servidor solo autoriza las cuentas incluidas en su lista de acceso.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                "La clave privada de la GitHub App, el secreto OAuth y los tokens de instalación permanecen en el servidor. Android solo conserva una sesión temporal de Dash Launcher.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            statusMessage?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ProfilesScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val storageManager = remember(context) { VersionStorageManager(context) }
    val profileManager = remember(context) { GameProfileManager(storageManager) }
    val installManager = remember(context) { GeometryDashInstallManager(context) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var instanceCheckEpoch by rememberSaveable { mutableIntStateOf(0) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) instanceCheckEpoch++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var localVersions by remember { mutableStateOf(emptyList<com.hestalang.dashlauncher.storage.LocalVersionRecord>()) }
    var selectedVersion by rememberSaveable { mutableStateOf("") }
    var profiles by remember { mutableStateOf(emptyList<com.hestalang.dashlauncher.storage.GameProfileRecord>()) }
    var activeProfileId by remember { mutableStateOf<String?>(null) }
    var newProfileName by rememberSaveable { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isBusy by remember { mutableStateOf(false) }
    var refreshCounter by rememberSaveable { mutableIntStateOf(0) }
    var importTargetProfileId by remember { mutableStateOf<String?>(null) }
    var exportTargetProfileId by remember { mutableStateOf<String?>(null) }
    val selectedVersionRecord = localVersions.firstOrNull { it.version == selectedVersion }

    val importBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        val targetId = importTargetProfileId
        if (uri != null && targetId != null && selectedVersion.isNotBlank()) {
            scope.launch {
                isBusy = true
                runCatching {
                    withContext(Dispatchers.IO) {
                        val input = context.contentResolver.openInputStream(uri)
                            ?: throw IllegalStateException("No se pudo abrir el archivo seleccionado.")
                        profileManager.importGameDataBackup(selectedVersion, targetId, input)
                    }
                }.onSuccess {
                    statusMessage = "Archivo ZIP guardado en el perfil. No se ha aplicado al progreso activo del juego."
                    refreshCounter++
                }.onFailure {
                    statusMessage = it.message ?: "No se pudo importar el archivo ZIP."
                }
                isBusy = false
                importTargetProfileId = null
            }
        } else {
            importTargetProfileId = null
        }
    }

    val exportBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val targetId = exportTargetProfileId
        if (uri != null && targetId != null && selectedVersion.isNotBlank()) {
            scope.launch {
                isBusy = true
                runCatching {
                    withContext(Dispatchers.IO) {
                        val output = context.contentResolver.openOutputStream(uri)
                            ?: throw IllegalStateException("No se pudo crear el archivo de destino.")
                        output.use {
                            profileManager.exportGameDataBackup(selectedVersion, targetId, it)
                        }
                    }
                }.onSuccess {
                    statusMessage = "Archivo ZIP exportado."
                }.onFailure {
                    statusMessage = it.message ?: "No se pudo exportar el archivo ZIP."
                }
                isBusy = false
                exportTargetProfileId = null
            }
        } else {
            exportTargetProfileId = null
        }
    }

    LaunchedEffect(refreshCounter, selectedVersion) {
        runCatching {
            withContext(Dispatchers.IO) { storageManager.initialize() }
        }.onSuccess { snapshot ->
            localVersions = snapshot.versions
            val resolvedVersion = selectedVersion.takeIf { value ->
                snapshot.versions.any { it.version == value }
            } ?: snapshot.versions.firstOrNull()?.version.orEmpty()
            if (resolvedVersion != selectedVersion) selectedVersion = resolvedVersion
            if (resolvedVersion.isNotBlank()) {
                val initializedProfiles = withContext(Dispatchers.IO) {
                    profileManager.initialize(resolvedVersion, "Geometry Dash $resolvedVersion")
                }
                profiles = initializedProfiles
                activeProfileId = withContext(Dispatchers.IO) {
                    profileManager.activeProfileId(resolvedVersion)
                }
            } else {
                profiles = emptyList()
                activeProfileId = null
            }
        }.onFailure {
            statusMessage = it.message ?: "No se pudieron cargar los perfiles."
        }
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Perfiles de Geometry Dash", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "Cada perfil puede tener su propia instalación de Geometry Dash, con identificador Android y datos de aplicación independientes.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("Versión seleccionada", fontWeight = FontWeight.SemiBold)
                if (localVersions.isEmpty()) {
                    Text(
                        "Primero descarga y guarda un APK desde la sección Versiones para preparar su espacio de perfiles.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    localVersions.forEach { version ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { selectedVersion = version.version }
                                .padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedVersion == version.version,
                                onClick = { selectedVersion = version.version }
                            )
                            Column(Modifier.weight(1f)) {
                                Text(version.displayName, fontWeight = FontWeight.Medium)
                                Text(version.version, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }

        if (selectedVersion.isNotBlank()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Crear perfil", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = newProfileName,
                        onValueChange = { newProfileName = it.take(32) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Nombre del perfil") },
                        placeholder = { Text("Ej. Principal, Desafíos, Pruebas") },
                        singleLine = true,
                        enabled = !isBusy,
                        shape = RoundedCornerShape(12.dp)
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                isBusy = true
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        profileManager.createProfile(selectedVersion, newProfileName)
                                    }
                                }.onSuccess {
                                    newProfileName = ""
                                    statusMessage = "Perfil creado."
                                    refreshCounter++
                                }.onFailure {
                                    statusMessage = it.message ?: "No se pudo crear el perfil."
                                }
                                isBusy = false
                            }
                        },
                        enabled = !isBusy && newProfileName.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Crear perfil")
                    }
                }
            }

            Text(
                "Perfiles guardados para Geometry Dash $selectedVersion",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            profiles.forEach { profile ->
                var renameDraft by rememberSaveable(profile.id, profile.name) { mutableStateOf(profile.name) }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = activeProfileId == profile.id,
                                onClick = {
                                    scope.launch {
                                        isBusy = true
                                        runCatching {
                                            withContext(Dispatchers.IO) {
                                                profileManager.activateProfile(selectedVersion, profile.id)
                                            }
                                        }.onSuccess {
                                            activeProfileId = profile.id
                                            statusMessage = "Perfil seleccionado en Dash Launcher."
                                        }.onFailure {
                                            statusMessage = it.message ?: "No se pudo seleccionar el perfil."
                                        }
                                        isBusy = false
                                    }
                                },
                                enabled = !isBusy
                            )
                            Column(Modifier.weight(1f)) {
                                Text(profile.name, fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (activeProfileId == profile.id) "Seleccionado en Dash Launcher" else "Perfil disponible",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    if (profile.hasGameDataBackup) {
                                        "Respaldo ZIP: ${profile.backupSizeBytes ?: 0L} bytes"
                                    } else {
                                        "Sin archivo ZIP de respaldo"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        val instancePackageName = profileManager.packageNameForProfile(
                            selectedVersion,
                            profile.id
                        )
                        val instanceIsInstalled = remember(instancePackageName, instanceCheckEpoch) {
                            installManager.isPackageInstalled(instancePackageName)
                        }
                        Text(
                            if (instanceIsInstalled) "Instancia instalada en Android" else "Instancia todavía no instalada",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Paquete de esta instancia: " + instancePackageName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = {
                                scope.launch {
                                    isBusy = true
                                    runCatching {
                                        val sourceApk = selectedVersionRecord
                                            ?.takeIf { it.hasApk }
                                            ?.apkFile
                                            ?: throw IllegalStateException(
                                                "Primero guarda el APK de esta versión en Almacenamiento local."
                                            )
                                        val outputApk = profile.directory.resolve("instance.apk")
                                        val displayLabel = "Geometry Dash " + selectedVersion + " - " + profile.name
                                        val result = withContext(Dispatchers.IO) {
                                            installManager.buildProfileInstance(
                                                sourceApk = sourceApk,
                                                outputApk = outputApk,
                                                packageName = instancePackageName,
                                                displayLabel = displayLabel
                                            )
                                        }
                                        val installResult = installManager.openInstallerForPackage(
                                            result.outputApk,
                                            instancePackageName
                                        )
                                        result to installResult
                                    }.onSuccess { pair ->
                                        val result = pair.first
                                        val installResult = pair.second
                                        statusMessage = when (installResult) {
                                            InstallRequestResult.PERMISSION_REQUIRED ->
                                                "Autoriza la instalación de aplicaciones para Dash Launcher y vuelve a pulsar Crear / actualizar."
                                            InstallRequestResult.INSTALLER_OPENED ->
                                                "Instalador de Android abierto. Confirma esta instancia para conservarla junto a las demás."
                                        }
                                        if (result.warnings.isNotEmpty()) {
                                            statusMessage = statusMessage.orEmpty() +
                                                "\nAvisos del análisis: " + result.warnings.joinToString(" ")
                                        }
                                    }.onFailure {
                                        statusMessage = it.message
                                            ?: "No se pudo preparar la instalación independiente."
                                    }
                                    isBusy = false
                                }
                            },
                            enabled = !isBusy && selectedVersionRecord?.hasApk == true,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Crear / actualizar instalación independiente")
                        }
                        Button(
                            onClick = {
                                runCatching {
                                    installManager.launchGame(instancePackageName)
                                }.onFailure {
                                    statusMessage = it.message ?: "No se pudo iniciar esta instancia."
                                }
                            },
                            enabled = !isBusy && instanceIsInstalled,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Jugar esta instancia")
                        }
                        OutlinedTextField(
                            value = renameDraft,
                            onValueChange = { renameDraft = it.take(32) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Cambiar nombre") },
                            singleLine = true,
                            enabled = !isBusy,
                            shape = RoundedCornerShape(12.dp)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        isBusy = true
                                        runCatching {
                                            withContext(Dispatchers.IO) {
                                                profileManager.renameProfile(selectedVersion, profile.id, renameDraft)
                                            }
                                        }.onSuccess {
                                            statusMessage = "Nombre del perfil actualizado."
                                            refreshCounter++
                                        }.onFailure {
                                            statusMessage = it.message ?: "No se pudo renombrar el perfil."
                                        }
                                        isBusy = false
                                    }
                                },
                                enabled = !isBusy && renameDraft.isNotBlank(),
                                modifier = Modifier.weight(1f)
                            ) { Text("Renombrar") }
                            Button(
                                onClick = {
                                    scope.launch {
                                        isBusy = true
                                        runCatching {
                                            withContext(Dispatchers.IO) {
                                                profileManager.deleteProfile(selectedVersion, profile.id)
                                            }
                                        }.onSuccess {
                                            statusMessage = "Perfil eliminado."
                                            refreshCounter++
                                        }.onFailure {
                                            statusMessage = it.message ?: "No se pudo eliminar el perfil."
                                        }
                                        isBusy = false
                                    }
                                },
                                enabled = !isBusy && profiles.size > 1 && !instanceIsInstalled,
                                modifier = Modifier.weight(1f)
                            ) { Text("Eliminar") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    importTargetProfileId = profile.id
                                    importBackupLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                                },
                                enabled = !isBusy,
                                modifier = Modifier.weight(1f)
                            ) { Text("Importar ZIP") }
                            Button(
                                onClick = {
                                    exportTargetProfileId = profile.id
                                    exportBackupLauncher.launch("Geometry-Dash-$selectedVersion-${profile.id}.zip")
                                },
                                enabled = !isBusy && profile.hasGameDataBackup,
                                modifier = Modifier.weight(1f)
                            ) { Text("Exportar ZIP") }
                        }
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Instancias y compatibilidad", fontWeight = FontWeight.SemiBold)
                Text(
                    "Cada perfil genera un APK con un identificador Android distinto y una firma local de Dash Launcher; Android reserva un espacio de datos separado para cada paquete. Esta primera fase modifica el manifiesto y la tabla de recursos, pero no reescribe código DEX ni bibliotecas nativas. Si el análisis detecta referencias al paquete original, se mostrarán avisos y algunas funciones podrían no funcionar. La firma del clon no es la firma original de RobTop. Importar o exportar ZIP todavía solo guarda respaldos; no los aplica al progreso del juego. Para eliminar un perfil, primero desinstala su instancia desde los ajustes de Android.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        statusMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ChannelSelector(
    selectedChannel: Int,
    onChannelSelected: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        catalogChannels.forEachIndexed { index, channel ->
            val selected = selectedChannel == index
            val background = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
            val foreground = if (selected) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(11.dp))
                    .clickable(role = Role.Tab) { onChannelSelected(index) },
                color = background,
                shape = RoundedCornerShape(11.dp)
            ) {
                Box(
                    modifier = Modifier.padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        channel.title,
                        color = foreground,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun CatalogVersionCard(
    record: CatalogVersionRecord,
    repository: RemoteVersionCatalogRepository,
    onApkStored: () -> Unit
) {
    var expanded by rememberSaveable(record.id) { mutableStateOf(false) }
    var metadata by remember(record.id) { mutableStateOf<CatalogVersionMetadata?>(null) }
    var isLoadingMetadata by remember(record.id) { mutableStateOf(false) }
    var metadataError by remember(record.id) { mutableStateOf<String?>(null) }
    var metadataWarning by remember(record.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(expanded, record.metadataUrl) {
        if (expanded && metadata == null && !isLoadingMetadata) {
            isLoadingMetadata = true
            metadataError = null
            runCatching {
                withContext(Dispatchers.IO) {
                    repository.loadMetadata(record)
                }
            }.onSuccess { result ->
                metadata = result.metadata
                metadataWarning = result.warning
            }.onFailure { error ->
                metadataError = error.message ?: "No se pudieron cargar los metadatos."
            }
            isLoadingMetadata = false
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(record.displayName, fontWeight = FontWeight.SemiBold)
                    Text(
                        record.version,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Dash Launcher requiere Android API 26 o superior",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    record.changelog?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        record.channel,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            Button(
                onClick = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (expanded) "Ocultar detalles" else "Detalles y descarga")
            }

            if (expanded) {
                when {
                    isLoadingMetadata -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            "Cargando metadatos de esta versión…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    metadataError != null -> {
                        Text(
                            metadataError.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Button(
                            onClick = {
                                metadata = null
                                metadataError = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Reintentar")
                        }
                    }
                    metadata != null -> {
                        val details = metadata!!
                        metadataWarning?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        if (details.description.isNotBlank()) {
                            Text(
                                details.description,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Text(
                            if (details.supportedAbis.isEmpty()) {
                                "ABI: todavía no verificada; consulta la ficha del proveedor"
                            } else {
                                "ABI: " + details.supportedAbis.joinToString(", ")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        val apiCompatible = Build.VERSION.SDK_INT >= details.minAndroidApi
                        val abiCompatible = details.supportedAbis.isEmpty() ||
                            details.supportedAbis.any { supported ->
                                Build.SUPPORTED_ABIS.contains(supported)
                            }
                        val entry = VersionEntry(
                            id = record.id,
                            displayName = record.displayName,
                            version = record.version,
                            channel = record.channel,
                            description = details.description,
                            minAndroidApi = details.minAndroidApi,
                            supportedAbis = details.supportedAbis,
                            downloadUrl = details.artifact?.url,
                            sizeBytes = details.artifact?.sizeBytes,
                            sha256 = details.artifact?.sha256,
                            providerId = details.artifact?.providerId ?: "direct",
                            providerName = details.artifact?.providerName,
                            packageName = details.artifact?.packageName,
                            signingCertificateSha256 = details.artifact?.signingCertificateSha256
                        )
                        val resolution = when {
                            !apiCompatible -> DownloadResolution.Unavailable(
                                "Esta versión requiere Android API " + details.minAndroidApi + " o superior."
                            )
                            !abiCompatible -> DownloadResolution.Unavailable(
                                "Este APK no tiene una arquitectura compatible con este dispositivo."
                            )
                            else -> remember(entry) {
                                DownloadProviderRegistry(
                                    listOf(
                                        CatalogDirectDownloadProvider(),
                                        ExternalCatalogPageProvider()
                                    )
                                ).resolve(entry)
                            }
                        }
                        VersionDownloadAction(resolution, onApkStored)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    modifier: Modifier = Modifier,
    selectedTheme: AppThemeOption,
    onThemeSelected: (AppThemeOption) -> Unit
) {
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Personaliza Dash Launcher",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "Configura el aspecto y el comportamiento de la aplicación.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Apariencia",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Tema de la aplicación",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    "Selecciona cómo quieres que se vea Dash Launcher.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))

                AppThemeOption.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onThemeSelected(option) }
                            .padding(horizontal = 4.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedTheme == option,
                            onClick = { onThemeSelected(option) }
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(option.title, fontWeight = FontWeight.Medium)
                            Text(
                                option.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        PrivateStorageSettingsCard()
    }
}

@Composable
private fun VersionDownloadAction(
    resolution: DownloadResolution,
    onApkStored: () -> Unit
) {
    val context = LocalContext.current
    val storageManager = remember(context) { VersionStorageManager(context) }

    if (resolution is DownloadResolution.OpenExternalPage) {
        Button(
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(resolution.url))
                runCatching { context.startActivity(intent) }
                    .onFailure {
                        Toast.makeText(
                            context,
                            "No se pudo abrir la página de " + resolution.providerName,
                            Toast.LENGTH_LONG
                        ).show()
                    }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Abrir " + resolution.providerName)
        }
        Text(
            "Dash Launcher abrirá la ficha externa. La descarga y la comprobación del archivo se realizan fuera de la aplicación.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    val coordinator = remember(context) { ApkDownloadCoordinator(context) }
    val artifact = (resolution as? DownloadResolution.Available)?.artifact

    var downloadId by rememberSaveable(artifact?.fileName) { mutableStateOf<Long?>(null) }
    var progressPercent by remember(artifact?.fileName) { mutableIntStateOf(0) }
    var statusMessage by rememberSaveable(artifact?.fileName) { mutableStateOf<String?>(null) }
    var isVerified by rememberSaveable(artifact?.fileName) { mutableStateOf(false) }
    var verifiedUriString by rememberSaveable(artifact?.fileName) { mutableStateOf<String?>(null) }

    LaunchedEffect(downloadId, artifact) {
        val id = downloadId ?: return@LaunchedEffect
        val item = artifact ?: return@LaunchedEffect

        while (true) {
            val snapshot = withContext(Dispatchers.IO) {
                coordinator.query(id)
            }

            if (snapshot == null) {
                statusMessage = "No se pudo consultar el estado de la descarga."
                downloadId = null
                break
            }

            if (snapshot.totalBytes > 0L) {
                progressPercent = ((snapshot.downloadedBytes * 100L) / snapshot.totalBytes)
                    .toInt()
                    .coerceIn(0, 100)
            }

            when (snapshot.status) {
                DownloadStatus.COMPLETED -> {
                    statusMessage = "Verificando tamaño, SHA-256, paquete, firma y ABI…"
                    val verification = withContext(Dispatchers.IO) {
                        coordinator.verifyCompletedDownload(id, item)
                    }
                    when (verification) {
                        is DownloadVerification.Verified -> {
                            isVerified = true
                            verifiedUriString = verification.uri.toString()
                            try {
                                val storedVersion = withContext(Dispatchers.IO) {
                                    val source = context.contentResolver.openInputStream(verification.uri)
                                        ?: throw IllegalStateException("No se pudo abrir el APK verificado para guardarlo.")
                                    storageManager.storeVerifiedApk(
                                        version = item.versionLabel,
                                        displayName = "Geometry Dash " + item.versionLabel,
                                        input = source,
                                        expectedSizeBytes = item.expectedSizeBytes,
                                        expectedSha256 = item.expectedSha256,
                                        packageName = item.expectedPackageName,
                                        signingCertificateSha256 = item.expectedSigningCertificateSha256
                                    )
                                }
                                onApkStored()
                                statusMessage = "APK verificado y guardado en " +
                                    storedVersion.apkFile.absolutePath +
                                    ". Android todavía debe confirmar la instalación."
                            } catch (error: Exception) {
                                statusMessage = "El APK fue verificado, pero no se pudo guardar en versions/: " +
                                    (error.message ?: "error de almacenamiento") +
                                    ". Todavía puedes instalarlo desde la descarga verificada."
                            }
                        }
                        is DownloadVerification.Rejected -> {
                            statusMessage = verification.reason
                        }
                    }
                    downloadId = null
                    break
                }
                DownloadStatus.FAILED -> {
                    statusMessage = "Android no pudo completar la descarga (código " +
                        (snapshot.reasonCode ?: 0) + ")."
                    downloadId = null
                    break
                }
                else -> delay(500)
            }
        }
    }

    if (artifact == null) {
        Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
            Text("Descarga no disponible")
        }
        val reason = (resolution as? DownloadResolution.Unavailable)?.reason
            ?: "No hay un artefacto descargable configurado."
        Text(
            reason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        Button(
            onClick = {
                runCatching { coordinator.enqueue(artifact) }
                    .onSuccess { id ->
                        progressPercent = 0
                        statusMessage = "Descarga iniciada por el gestor de Android."
                        downloadId = id
                    }
                    .onFailure { error ->
                        statusMessage = error.message ?: "No se pudo iniciar la descarga."
                    }
            },
            enabled = downloadId == null && !isVerified,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                when {
                    isVerified -> "Descarga verificada"
                    downloadId != null -> "Descargando…"
                    else -> "Descargar"
                }
            )
        }

        if (downloadId != null) {
            LinearProgressIndicator(
                progress = progressPercent / 100f,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Progreso: $progressPercent%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (isVerified && !verifiedUriString.isNullOrBlank()) {
            Button(
                onClick = {
                    val apkUri = Uri.parse(verifiedUriString)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                        !context.packageManager.canRequestPackageInstalls()
                    ) {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + context.packageName)
                                )
                            )
                        }.onSuccess {
                            statusMessage = "Autoriza a Dash Launcher para instalar aplicaciones y vuelve a pulsar Instalar / actualizar."
                        }.onFailure {
                            statusMessage = "No se pudo abrir el permiso de instalación de Android."
                        }
                    } else {
                        runCatching {
                            val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE)
                                .setDataAndType(
                                    apkUri,
                                    "application/vnd.android.package-archive"
                                )
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            context.startActivity(installIntent)
                        }.onSuccess {
                            statusMessage = "Se abrió el instalador de Android. Revisa que el paquete y la versión sean correctos y confirma la actualización."
                        }.onFailure { error ->
                            statusMessage = error.message ?: "No se pudo abrir el instalador de Android."
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Instalar / actualizar")
            }
        }

        statusMessage?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
