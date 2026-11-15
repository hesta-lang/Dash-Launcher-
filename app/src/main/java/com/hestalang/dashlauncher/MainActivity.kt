package com.hestalang.dashlauncher

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val SETTINGS_FILE = "dash_launcher_settings"
private const val THEME_PREFERENCE = "app_theme"

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
    val version: String,
    val description: String,
    val channel: String
)

private val catalogChannels = CatalogChannel.entries
private val sampleVersions = listOf(
    VersionPreview(
        "Geometry Dash",
        "Las versiones disponibles aparecerán aquí cuando el catálogo esté conectado.",
        "Stable"
    )
)

@Composable
private fun DashLauncherRoot() {
    val context = LocalContext.current
    val preferences = remember(context) {
        context.applicationContext.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)
    }
    var selectedTheme by rememberSaveable { mutableStateOf(AppThemeOption.SYSTEM) }

    // Read the saved setting off the main thread so first composition is not delayed by disk I/O.
    LaunchedEffect(preferences) {
        val savedValue = withContext(Dispatchers.IO) {
            preferences.getString(THEME_PREFERENCE, AppThemeOption.SYSTEM.preferenceValue)
        }
        selectedTheme = AppThemeOption.fromPreference(savedValue)
    }

    val useDarkTheme = when (selectedTheme) {
        AppThemeOption.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
        AppThemeOption.LIGHT -> false
        AppThemeOption.DARK -> true
    }

    DashLauncherTheme(darkTheme = useDarkTheme) {
        DashLauncherApp(
            selectedTheme = selectedTheme,
            onThemeSelected = { newTheme ->
                selectedTheme = newTheme
                preferences.edit()
                    .putString(THEME_PREFERENCE, newTheme.preferenceValue)
                    .apply()
            }
        )
    }
}

@Composable
private fun DashLauncherTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme(),
        content = content
    )
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
            NavigationBar {
                NavigationBarItem(
                    selected = selectedDestination == 0,
                    onClick = { selectedDestination = 0 },
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_versions),
                            contentDescription = null
                        )
                    },
                    label = { Text("Versiones") }
                )
                NavigationBarItem(
                    selected = selectedDestination == 1,
                    onClick = { selectedDestination = 1 },
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_settings),
                            contentDescription = null
                        )
                    },
                    label = { Text("Configuración") }
                )
            }
        }
    ) { innerPadding ->
        when (selectedDestination) {
            0 -> VersionsScreen(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            )
            else -> SettingsScreen(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                selectedTheme = selectedTheme,
                onThemeSelected = onThemeSelected
            )
        }
    }
}

@Composable
private fun VersionsScreen(modifier: Modifier = Modifier) {
    var selectedChannel by rememberSaveable { mutableIntStateOf(0) }
    var searchText by rememberSaveable { mutableStateOf("") }
    val filteredVersions = remember(searchText, selectedChannel) {
        sampleVersions.filter {
            it.version.contains(searchText, ignoreCase = true) &&
                it.channel == catalogChannels[selectedChannel].title
        }
    }

    Column(
        modifier = modifier
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
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

        TabRow(selectedTabIndex = selectedChannel) {
            catalogChannels.forEachIndexed { index, channel ->
                Tab(
                    selected = selectedChannel == index,
                    onClick = { selectedChannel = index },
                    text = { Text(channel.title) }
                )
            }
        }

        Text(
            "Catálogo de versiones",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (filteredVersions.isEmpty()) {
                item {
                    Text(
                        "No hay versiones en este canal todavía.",
                        modifier = Modifier.padding(vertical = 12.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                items(filteredVersions, key = { it.version }) { version ->
                    VersionCard(version)
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
                            Text(
                                option.title,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(version.version, fontWeight = FontWeight.SemiBold)
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
    }
}
