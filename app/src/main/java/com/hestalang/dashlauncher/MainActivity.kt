package com.hestalang.dashlauncher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

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
                            if (selectedDestination == 0) "Dash Launcher" else "Configuración",
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
                title = "Configuración",
                iconId = R.drawable.ic_settings,
                selected = selectedDestination == 1,
                onClick = { onDestinationSelected(1) }
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
                    repository = repository
                )
            }
        }
        Spacer(Modifier.height(8.dp))
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
    repository: RemoteVersionCatalogRepository
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
                        VersionDownloadAction(resolution)
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
    }
}

@Composable
private fun VersionDownloadAction(resolution: DownloadResolution) {
    val context = LocalContext.current

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
                    statusMessage = "Verificando tamaño y SHA-256…"
                    val verification = withContext(Dispatchers.IO) {
                        coordinator.verifyCompletedDownload(id, item)
                    }
                    when (verification) {
                        is DownloadVerification.Verified -> {
                            isVerified = true
                            statusMessage = "APK verificado: SHA-256, paquete, certificado y ABI coinciden. El archivo está guardado en Dash Launcher; todavía no se ha instalado."
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

        statusMessage?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
