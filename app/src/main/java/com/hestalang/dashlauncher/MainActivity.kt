package com.hestalang.dashlauncher

import android.content.Context
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.hestalang.dashlauncher.data.VersionEntry
import com.hestalang.dashlauncher.download.ApkDownloadCoordinator
import com.hestalang.dashlauncher.download.CatalogDirectDownloadProvider
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

private enum class CatalogChannel(val title: String) {
    Stable("Stable"),
    Beta("Beta"),
    Archived("Archived")
}

private data class VersionPreview(
    val id: String,
    val displayName: String,
    val version: String,
    val description: String,
    val channel: String,
    val minAndroidApi: Int = 23,
    val supportedAbis: List<String> = listOf("arm64-v8a", "armeabi-v7a"),
    val downloadUrl: String? = null,
    val sizeBytes: Long? = null,
    val sha256: String? = null
)

private val catalogChannels = CatalogChannel.entries
private val sampleVersion = VersionPreview(
    id = "geometry-dash-preview",
    displayName = "Geometry Dash",
    version = "Versión pendiente de catálogo",
    description = "La fuente de descarga aún no está configurada. No se habilitará la descarga hasta verificar el archivo y su procedencia.",
    channel = "Stable"
)

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
    var selectedChannel by rememberSaveable { mutableIntStateOf(0) }
    var searchText by rememberSaveable { mutableStateOf("") }
    val showSample = remember(searchText, selectedChannel) {
        selectedChannel == 0 && (
            sampleVersion.displayName.contains(searchText, ignoreCase = true) ||
                sampleVersion.version.contains(searchText, ignoreCase = true)
            )
    }

    Column(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(Modifier.height(2.dp))
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

        Text(
            "Catálogo de versiones",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )

        if (showSample) {
            VersionCard(sampleVersion)
        } else {
            Text(
                "No hay versiones en este canal todavía.",
                modifier = Modifier.padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
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
private fun VersionCard(version: VersionPreview) {
    val entry = remember(version) {
        VersionEntry(
            id = version.id,
            displayName = version.displayName,
            version = version.version,
            channel = version.channel.lowercase(),
            description = version.description,
            minAndroidApi = version.minAndroidApi,
            supportedAbis = version.supportedAbis,
            downloadUrl = version.downloadUrl,
            sizeBytes = version.sizeBytes,
            sha256 = version.sha256
        )
    }
    val resolution = remember(entry) {
        CatalogDirectDownloadProvider().resolve(entry)
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
                    Text(version.displayName, fontWeight = FontWeight.SemiBold)
                    Text(
                        version.version,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        version.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        version.channel,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            VersionDownloadAction(resolution)
        }
    }
}

@Composable
private fun VersionDownloadAction(resolution: DownloadResolution) {
    val context = LocalContext.current
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
                            statusMessage = "APK descargado y verificado. Está guardado en el almacenamiento privado de Dash Launcher."
                        }
                        is DownloadVerification.Rejected -> {
                            statusMessage = verification.reason
                        }
                    }
                    downloadId = null
                    break
                }
                DownloadStatus.FAILED -> {
                    statusMessage = "Android no pudo completar la descarga (código ${snapshot.reasonCode ?: 0})."
                    downloadId = null
                    break
                }
                else -> {
                    delay(500)
                }
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
