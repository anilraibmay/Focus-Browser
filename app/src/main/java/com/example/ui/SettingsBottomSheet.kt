package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.BrowserPreferences
import com.example.model.FocusMode
import com.example.model.AppThemeMode
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.example.util.DownloadStorageHelper
import com.example.util.FolderPreset
import com.example.util.StoragePermissionInfo

import com.example.viewmodel.BrowserViewModel
import com.example.util.AdvancedDownloadItem
import com.example.util.DownloadState
import com.example.database.HistoryEntity
import com.example.database.VideoHistoryEntity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.BorderStroke
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsContent(
    isMaximized: Boolean,
    onToggleMaximize: () -> Unit,
    preferences: BrowserPreferences,
    onUpdatePreferences: ((BrowserPreferences) -> BrowserPreferences) -> Unit,
    onClearData: () -> Unit,
    onSimulateLowMemory: () -> Unit,
    downloads: List<AdvancedDownloadItem>,
    history: List<HistoryEntity>,
    viewModel: BrowserViewModel,
    tabSelected: Int,
    onTabSelected: (Int) -> Unit,
    onOpenUrl: (String) -> Unit = {}
) {
    val context = LocalContext.current
    var showFolderDialog by remember { mutableStateOf(false) }
    var showPermissionsCenterDialog by remember { mutableStateOf(false) }
    var permissionInfo by remember { mutableStateOf(DownloadStorageHelper.checkPermissions(context)) }

    val openDocumentTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (_: Exception) {}

            val friendlyName = DownloadStorageHelper.getDisplayName(context, uri.toString())
            onUpdatePreferences {
                it.copy(
                    downloadDirectoryUri = uri.toString(),
                    downloadDirectoryName = friendlyName
                )
            }
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        permissionInfo = DownloadStorageHelper.checkPermissions(context)
    }

    val writeStoragePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        permissionInfo = DownloadStorageHelper.checkPermissions(context)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isCompactWidth = maxWidth < 360.dp
            val horizontalPadding = if (isCompactWidth) 12.dp else 16.dp

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 760.dp)
                    .align(Alignment.TopCenter)
            ) {
                CenterAlignedTopAppBar(
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    title = { 
                        Text(
                            when (tabSelected) {
                                0 -> "Definições"
                                1 -> "Downloads"
                                else -> "Histórico"
                            }, 
                            fontWeight = FontWeight.Bold,
                            fontSize = if (isCompactWidth) 18.sp else 20.sp
                        ) 
                    },
                    navigationIcon = {
                        IconButton(onClick = onToggleMaximize) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                        }
                    }
                )
                TabRow(
                    selectedTabIndex = tabSelected,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Tab(
                        selected = tabSelected == 0,
                        onClick = { onTabSelected(0) },
                        icon = { Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(if (isCompactWidth) 18.dp else 20.dp)) },
                        text = { Text("Definições", fontSize = if (isCompactWidth) 11.sp else 13.sp, maxLines = 1) }
                    )
                    Tab(
                        selected = tabSelected == 1,
                        onClick = { onTabSelected(1) },
                        icon = { Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(if (isCompactWidth) 18.dp else 20.dp)) },
                        text = { Text("Downloads", fontSize = if (isCompactWidth) 11.sp else 13.sp, maxLines = 1) }
                    )
                    Tab(
                        selected = tabSelected == 2,
                        onClick = { onTabSelected(2) },
                        icon = { Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(if (isCompactWidth) 18.dp else 20.dp)) },
                        text = { Text("Histórico", fontSize = if (isCompactWidth) 11.sp else 13.sp, maxLines = 1) }
                    )
                }
                when (tabSelected) {
                    0 -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = horizontalPadding, vertical = 8.dp),
                            contentPadding = PaddingValues(bottom = 32.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            item {
                                Text("Aparência e Tema", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.height(8.dp))
                                Card(shape = RoundedCornerShape(12.dp)) {
                                    Column {
                                        ThemeOptionRow(
                                            title = "Padrão do Sistema",
                                            subtitle = "Adapta-se automaticamente ao tema do dispositivo",
                                            mode = AppThemeMode.SYSTEM,
                                            currentMode = preferences.themeMode
                                        ) { onUpdatePreferences { it.copy(themeMode = AppThemeMode.SYSTEM) } }
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                        ThemeOptionRow(
                                            title = "Modo Claro",
                                            subtitle = "Visual clássico de alto contraste e nitidez",
                                            mode = AppThemeMode.LIGHT,
                                            currentMode = preferences.themeMode
                                        ) { onUpdatePreferences { it.copy(themeMode = AppThemeMode.LIGHT) } }
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                        ThemeOptionRow(
                                            title = "Modo Escuro",
                                            subtitle = "Confortável para leitura e ambientes escuros",
                                            mode = AppThemeMode.DARK,
                                            currentMode = preferences.themeMode
                                        ) { onUpdatePreferences { it.copy(themeMode = AppThemeMode.DARK) } }
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                        ThemeOptionRow(
                                            title = "Preto Puro (OLED)",
                                            subtitle = "Fundo 100% preto com economia máxima de bateria",
                                            badgeText = "OLED",
                                            mode = AppThemeMode.OLED_DARK,
                                            currentMode = preferences.themeMode
                                        ) { onUpdatePreferences { it.copy(themeMode = AppThemeMode.OLED_DARK) } }
                                    }
                                }
                            }

                            item {
                                Text("Modo de Foco", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.height(8.dp))
                                Card(shape = RoundedCornerShape(12.dp)) {
                                    Column {
                                        FocusOptionRow("Sem Foco", "Navegação padrão sem restrições especiais", FocusMode.NORMAL, preferences.focusMode) { onUpdatePreferences { it.copy(focusMode = FocusMode.NORMAL) } }
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                        FocusOptionRow("Plano de Fundo", "Permite multimédia com o ecrã bloqueado", FocusMode.BACKGROUND, preferences.focusMode) { onUpdatePreferences { it.copy(focusMode = FocusMode.BACKGROUND) } }
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                        FocusOptionRow("Split-Screen", "Otimizado para ecrã dividido e multitarefas", FocusMode.SPLIT_SCREEN, preferences.focusMode) { onUpdatePreferences { it.copy(focusMode = FocusMode.SPLIT_SCREEN) } }
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                        FocusOptionRow("Bloqueio Total", "Silencia distrações fora do separador", FocusMode.TOTAL_BLOCK, preferences.focusMode) { onUpdatePreferences { it.copy(focusMode = FocusMode.TOTAL_BLOCK) } }
                                    }
                                }
                            }

                            item {
                                Text("Geral", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.height(8.dp))
                                Card(shape = RoundedCornerShape(12.dp)) {
                                    Column {
                                        SettingToggleRow("Modo Desktop", "Solicita versão de computador nos sites", preferences.desktopMode) { onUpdatePreferences { it.copy(desktopMode = !it.desktopMode) } }
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                        SettingToggleRow("Bloquear Pop-ups", "Impede janelas e pop-ups intrusivos", preferences.blockPopups) { onUpdatePreferences { it.copy(blockPopups = !it.blockPopups) } }
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                        SettingToggleRow("Bloquear Anúncios (AdBlock)", "Filtra rastreadores e publicidade", preferences.enableAdBlock) { onUpdatePreferences { it.copy(enableAdBlock = !it.enableAdBlock) } }
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                        SettingToggleRow("Limpar ao Sair", "Elimina ficheiros temporários ao sair", preferences.clearCacheOnExit) { onUpdatePreferences { it.copy(clearCacheOnExit = !it.clearCacheOnExit) } }
                                    }
                                }
                            }

                            item {
                                Text("Transferências e Armazenamento", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.height(8.dp))
                                Card(shape = RoundedCornerShape(12.dp)) {
                                    Column {
                                        DownloadFolderPreferenceRow(
                                            folderName = preferences.downloadDirectoryName,
                                            isCustom = preferences.downloadDirectoryUri != null,
                                            onClick = { showFolderDialog = true }
                                        )
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                        SettingToggleRow(
                                            title = "Perguntar onde guardar",
                                            subtitle = "Escolher local e nome antes de iniciar cada transferência",
                                            checked = preferences.askDownloadLocation
                                        ) {
                                            onUpdatePreferences { it.copy(askDownloadLocation = !it.askDownloadLocation) }
                                        }
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                        StoragePermissionsStatusRow(
                                            permissionInfo = permissionInfo,
                                            onCheckOrRequest = {
                                                showPermissionsCenterDialog = true
                                            }
                                        )
                                    }
                                }
                            }

                            item {
                                Text("Dados e Privacidade", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = onClearData,
                                    modifier = Modifier.fillMaxWidth().height(48.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Limpar Histórico e Cache", fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                    1 -> {
                        DownloadsScreen(downloads, viewModel, onOpenFolderDialog = { showFolderDialog = true })
                    }
                    2 -> {
                        HistoryScreen(
                            history = history,
                            viewModel = viewModel,
                            onOpenUrl = onOpenUrl,
                            onDeleteItem = { id -> viewModel.deleteHistoryEntry(id) },
                            onClearAll = { viewModel.clearHistory() }
                        )
                    }
                }
            }

            if (showFolderDialog) {
                FolderSelectionDialog(
                    currentUri = preferences.downloadDirectoryUri,
                    onSelectPreset = { preset ->
                        showFolderDialog = false
                        onUpdatePreferences {
                            it.copy(
                                downloadDirectoryUri = preset.uriOrPath,
                                downloadDirectoryName = preset.name
                            )
                        }
                    },
                    onChooseCustom = {
                        showFolderDialog = false
                        openDocumentTreeLauncher.launch(null)
                    },
                    onResetDefault = {
                        showFolderDialog = false
                        onUpdatePreferences {
                            it.copy(
                                downloadDirectoryUri = null,
                                downloadDirectoryName = "Downloads (Padrão)"
                            )
                        }
                    },
                    onDismiss = { showFolderDialog = false }
                )
            }

            if (showPermissionsCenterDialog) {
                PermissionsCenterDialog(
                    viewModel = viewModel,
                    onDismiss = {
                        showPermissionsCenterDialog = false
                        permissionInfo = DownloadStorageHelper.checkPermissions(context)
                    }
                )
            }
        }
    }
}

@Composable
fun ThemeOptionRow(
    title: String,
    subtitle: String? = null,
    badgeText: String? = null,
    mode: AppThemeMode,
    currentMode: AppThemeMode,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = mode == currentMode,
            onClick = null,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (badgeText != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.padding(vertical = 1.dp)
                    ) {
                        Text(
                            text = badgeText,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

@Composable
fun FocusOptionRow(
    title: String,
    subtitle: String? = null,
    mode: FocusMode,
    currentMode: FocusMode,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = mode == currentMode,
            onClick = null,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

@Composable
fun SettingToggleRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        ) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = null
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadsScreen(
    downloads: List<AdvancedDownloadItem>,
    viewModel: BrowserViewModel? = null,
    onOpenFolderDialog: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val currentPreferences = viewModel?.preferences?.collectAsState()?.value ?: BrowserPreferences()
    var previewItem by remember { mutableStateOf<AdvancedDownloadItem?>(null) }
    var itemToRename by remember { mutableStateOf<AdvancedDownloadItem?>(null) }
    var itemToDelete by remember { mutableStateOf<AdvancedDownloadItem?>(null) }
    var showClearAllDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(0) } // 0: Todos, 1: Em Curso, 2: Concluídos

    val completedCount = remember(downloads) {
        downloads.count { it.state == DownloadState.COMPLETED }
    }
    val activeCount = remember(downloads) {
        downloads.count { it.state == DownloadState.DOWNLOADING || it.state == DownloadState.PENDING || it.state == DownloadState.PROCESSING || it.state == DownloadState.PAUSED }
    }

    val filteredDownloads = remember(downloads, searchQuery, selectedFilter) {
        val query = searchQuery.trim().lowercase(Locale.ROOT)
        downloads.filter { item ->
            val matchesSearch = query.isEmpty() || item.fileName.lowercase(Locale.ROOT).contains(query)
            val matchesFilter = when (selectedFilter) {
                1 -> item.state == DownloadState.DOWNLOADING || item.state == DownloadState.PENDING || item.state == DownloadState.PROCESSING || item.state == DownloadState.PAUSED
                2 -> item.state == DownloadState.COMPLETED
                else -> true
            }
            matchesSearch && matchesFilter
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isCompactWidth = maxWidth < 360.dp
        val horizontalPadding = if (isCompactWidth) 10.dp else 16.dp
        val thumbSize = if (isCompactWidth) 44.dp else 52.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = horizontalPadding, vertical = 8.dp)
        ) {
            // Folder Location Banner
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = onOpenFolderDialog != null) { onOpenFolderDialog?.invoke() }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = "Pasta",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Pasta: ",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = currentPreferences.downloadDirectoryName,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (onOpenFolderDialog != null) {
                        Text(
                            text = "Alterar",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Search & Filter Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Pesquisar transferências...", fontSize = 13.sp) },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = "Pesquisar", modifier = Modifier.size(20.dp))
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Limpar", modifier = Modifier.size(18.dp))
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f).height(50.dp)
                )

                if (completedCount > 0) {
                    IconButton(
                        onClick = { showClearAllDialog = true },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = "Limpar histórico",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Filter Chips Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = selectedFilter == 0,
                    onClick = { selectedFilter = 0 },
                    label = { Text("Todos (${downloads.size})", fontSize = 12.sp) }
                )
                FilterChip(
                    selected = selectedFilter == 1,
                    onClick = { selectedFilter = 1 },
                    label = { Text("Em curso ($activeCount)", fontSize = 12.sp) }
                )
                FilterChip(
                    selected = selectedFilter == 2,
                    onClick = { selectedFilter = 2 },
                    label = { Text("Concluídos ($completedCount)", fontSize = 12.sp) }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (filteredDownloads.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = if (searchQuery.isNotEmpty()) Icons.Default.Search else Icons.Default.DownloadDone,
                            contentDescription = null,
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            if (searchQuery.isNotEmpty()) "Nenhuma transferência encontrada" else "Sem transferências nesta categoria",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(filteredDownloads.size, key = { filteredDownloads[it].id }) { index ->
                        val dl = filteredDownloads[index]
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                            ),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Thumbnail / Ícone do ficheiro
                                    DownloadThumbnailView(
                                        item = dl,
                                        size = thumbSize,
                                        onClick = if (dl.state == DownloadState.COMPLETED) {
                                            { previewItem = dl }
                                        } else null
                                    )

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = dl.fileName,
                                                fontWeight = FontWeight.Bold,
                                                style = MaterialTheme.typography.bodyMedium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            val (stateText, stateBadgeColor) = when (dl.state) {
                                                DownloadState.COMPLETED -> "Concluído" to MaterialTheme.colorScheme.primary
                                                DownloadState.DOWNLOADING -> "A Transferir" to MaterialTheme.colorScheme.tertiary
                                                DownloadState.PROCESSING -> "A Processar" to MaterialTheme.colorScheme.tertiary
                                                DownloadState.PAUSED -> "Pausado" to MaterialTheme.colorScheme.secondary
                                                DownloadState.PENDING -> "Pendente" to MaterialTheme.colorScheme.outline
                                                DownloadState.CANCELLED -> "Cancelado" to MaterialTheme.colorScheme.outline
                                                DownloadState.BLOCKED_EXTENSION -> "Bloqueado" to MaterialTheme.colorScheme.error
                                                DownloadState.FAILED -> "Falhou" to MaterialTheme.colorScheme.error
                                            }
                                            Surface(
                                                color = stateBadgeColor.copy(alpha = 0.15f),
                                                shape = RoundedCornerShape(6.dp)
                                            ) {
                                                Text(
                                                    text = stateText,
                                                    color = stateBadgeColor,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(4.dp))
                                        val sizeToShow = if (dl.totalBytes > 0) dl.totalBytes else (dl.file?.length() ?: dl.downloadedBytes)
                                        val progressText = if (dl.state == DownloadState.COMPLETED) {
                                            val dateStr = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(dl.createdAtMs))
                                            "${formatFileSize(sizeToShow)} • $dateStr"
                                        } else if (dl.totalBytes > 0) {
                                            val percent = ((dl.downloadedBytes.toDouble() / dl.totalBytes.toDouble()) * 100).toInt().coerceIn(0, 100)
                                            "${formatFileSize(dl.downloadedBytes)} de ${formatFileSize(dl.totalBytes)} ($percent%)"
                                        } else {
                                            "${formatFileSize(dl.downloadedBytes)} descarregados"
                                        }
                                        Text(
                                            text = progressText,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                if (dl.errorMessage != null) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = dl.errorMessage,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                                
                                if (dl.state == DownloadState.DOWNLOADING || dl.state == DownloadState.PENDING || dl.state == DownloadState.PROCESSING) {
                                    val progress = if (dl.totalBytes > 0) dl.downloadedBytes.toFloat() / dl.totalBytes.toFloat() else 0f
                                    Spacer(modifier = Modifier.height(8.dp))
                                    LinearProgressIndicator(
                                        progress = { progress },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }

                                // Ações CRUD Interativas
                                Spacer(modifier = Modifier.height(8.dp))
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    when (dl.state) {
                                        DownloadState.DOWNLOADING, DownloadState.PROCESSING -> {
                                            // Pausar
                                            FilledTonalButton(
                                                onClick = { viewModel?.pauseDownload(dl.id) },
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                modifier = Modifier.height(34.dp)
                                            ) {
                                                Icon(Icons.Default.Pause, contentDescription = "Pausar", modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Pausar", fontSize = 12.sp)
                                            }
                                            Spacer(modifier = Modifier.width(6.dp))
                                            // Cancelar
                                            TextButton(
                                                onClick = { viewModel?.cancelDownload(dl.id) },
                                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                                modifier = Modifier.height(34.dp)
                                            ) {
                                                Icon(Icons.Default.Close, contentDescription = "Cancelar", modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Cancelar", fontSize = 12.sp)
                                            }
                                        }
                                        DownloadState.PAUSED -> {
                                            // Retomar
                                            Button(
                                                onClick = { viewModel?.resumeDownload(dl.id) },
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                modifier = Modifier.height(34.dp)
                                            ) {
                                                Icon(Icons.Default.PlayArrow, contentDescription = "Retomar", modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Retomar", fontSize = 12.sp)
                                            }
                                            Spacer(modifier = Modifier.width(4.dp))
                                            // Renomear
                                            IconButton(
                                                onClick = { itemToRename = dl },
                                                modifier = Modifier.size(34.dp)
                                            ) {
                                                Icon(Icons.Default.Edit, contentDescription = "Renomear", modifier = Modifier.size(18.dp))
                                            }
                                            // Cancelar
                                            TextButton(
                                                onClick = { viewModel?.cancelDownload(dl.id) },
                                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                                                modifier = Modifier.height(34.dp)
                                            ) {
                                                Icon(Icons.Default.Close, contentDescription = "Cancelar", modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(2.dp))
                                                Text("Cancelar", fontSize = 12.sp)
                                            }
                                            // Deletar
                                            IconButton(
                                                onClick = { itemToDelete = dl },
                                                modifier = Modifier.size(34.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.DeleteOutline,
                                                    contentDescription = "Deletar",
                                                    tint = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                        DownloadState.COMPLETED -> {
                                            // Partilhar
                                            IconButton(
                                                onClick = {
                                                    dl.file?.let { shareFile ->
                                                        try {
                                                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                                                context,
                                                                "${context.packageName}.fileprovider",
                                                                shareFile
                                                            )
                                                            val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                                                type = dl.mimeType.ifBlank { "*/*" }
                                                                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                            }
                                                            context.startActivity(android.content.Intent.createChooser(shareIntent, "Partilhar Ficheiro"))
                                                        } catch (e: Exception) {
                                                            viewModel?.showNotification("Não foi possível partilhar o ficheiro")
                                                        }
                                                    }
                                                },
                                                modifier = Modifier.size(34.dp)
                                            ) {
                                                Icon(Icons.Default.Share, contentDescription = "Partilhar", modifier = Modifier.size(18.dp))
                                            }
                                            // Renomear
                                            IconButton(
                                                onClick = { itemToRename = dl },
                                                modifier = Modifier.size(34.dp)
                                            ) {
                                                Icon(Icons.Default.Edit, contentDescription = "Renomear", modifier = Modifier.size(18.dp))
                                            }
                                            // Deletar
                                            IconButton(
                                                onClick = { itemToDelete = dl },
                                                modifier = Modifier.size(34.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.DeleteOutline,
                                                    contentDescription = "Deletar",
                                                    tint = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(4.dp))
                                            // Ver Pré-visualização
                                            OutlinedButton(
                                                onClick = { previewItem = dl },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                                modifier = Modifier.height(34.dp)
                                            ) {
                                                Icon(Icons.Default.Visibility, contentDescription = "Ver", modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Ver", fontSize = 12.sp)
                                            }
                                            Spacer(modifier = Modifier.width(6.dp))
                                            // Abrir Ficheiro
                                            Button(
                                                onClick = { viewModel?.openDownloadedMediaOrFile(context, dl) },
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                                modifier = Modifier.height(34.dp)
                                            ) {
                                                Icon(Icons.Default.FileOpen, contentDescription = "Abrir", modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Abrir", fontSize = 12.sp)
                                            }
                                        }
                                        DownloadState.FAILED, DownloadState.CANCELLED, DownloadState.BLOCKED_EXTENSION -> {
                                            if (dl.state == DownloadState.FAILED || dl.state == DownloadState.CANCELLED) {
                                                // Tentar novamente / Retomar
                                                FilledTonalButton(
                                                    onClick = { viewModel?.resumeDownload(dl.id) },
                                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                    modifier = Modifier.height(34.dp)
                                                ) {
                                                    Icon(Icons.Default.Refresh, contentDescription = "Tentar novamente", modifier = Modifier.size(16.dp))
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text("Tentar de Novo", fontSize = 12.sp)
                                                }
                                                Spacer(modifier = Modifier.width(4.dp))
                                            }
                                            // Renomear
                                            IconButton(
                                                onClick = { itemToRename = dl },
                                                modifier = Modifier.size(34.dp)
                                            ) {
                                                Icon(Icons.Default.Edit, contentDescription = "Renomear", modifier = Modifier.size(18.dp))
                                            }
                                            // Deletar
                                            IconButton(
                                                onClick = { itemToDelete = dl },
                                                modifier = Modifier.size(34.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.DeleteOutline,
                                                    contentDescription = "Deletar",
                                                    tint = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                        DownloadState.PENDING -> {
                                            // Cancelar
                                            TextButton(
                                                onClick = { viewModel?.cancelDownload(dl.id) },
                                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                                modifier = Modifier.height(34.dp)
                                            ) {
                                                Icon(Icons.Default.Close, contentDescription = "Cancelar", modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Cancelar", fontSize = 12.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Diálogo de Renomear Ficheiro
    itemToRename?.let { item ->
        RenameDownloadDialog(
            item = item,
            onDismiss = { itemToRename = null },
            onConfirm = { newName ->
                viewModel?.renameDownload(item.id, newName)
                itemToRename = null
            }
        )
    }

    // Diálogo de Eliminar Transferência
    itemToDelete?.let { item ->
        DeleteDownloadDialog(
            item = item,
            onDismiss = { itemToDelete = null },
            onConfirm = { deleteFromDisk ->
                viewModel?.deleteDownload(item.id, deleteFromDisk)
                itemToDelete = null
            }
        )
    }

    // Diálogo de Limpar Todas as Concluídas
    if (showClearAllDialog) {
        ClearAllDownloadsDialog(
            onDismiss = { showClearAllDialog = false },
            onConfirm = { deleteFromDisk ->
                viewModel?.clearCompletedDownloads(deleteFromDisk)
                showClearAllDialog = false
            }
        )
    }

    // Modal de Pré-visualização com Detalhes e Ações Completas
    previewItem?.let { itemToPreview ->
        DownloadPreviewModal(
            item = itemToPreview,
            onDismiss = { previewItem = null },
            onOpenFile = {
                previewItem = null
                viewModel?.openDownloadedMediaOrFile(context, itemToPreview)
            },
            onShareFile = {
                itemToPreview.file?.let { shareFile ->
                    try {
                        val uri = androidx.core.content.FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            shareFile
                        )
                        val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = itemToPreview.mimeType.ifBlank { "*/*" }
                            putExtra(android.content.Intent.EXTRA_STREAM, uri)
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(android.content.Intent.createChooser(shareIntent, "Partilhar Ficheiro"))
                    } catch (e: Exception) {
                        viewModel?.showNotification("Não foi possível partilhar o ficheiro")
                    }
                }
            },
            onRename = {
                val target = itemToPreview
                previewItem = null
                itemToRename = target
            },
            onDelete = {
                val target = itemToPreview
                previewItem = null
                itemToDelete = target
            }
        )
    }
}

@Composable
fun RenameDownloadDialog(
    item: AdvancedDownloadItem,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val initialBaseName = remember(item) {
        if (item.fileName.contains('.')) item.fileName.substringBeforeLast('.') else item.fileName
    }
    val extension = remember(item) {
        if (item.fileName.contains('.')) "." + item.fileName.substringAfterLast('.') else ""
    }
    var newName by remember { mutableStateOf(initialBaseName) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        title = {
            Text("Renomear Ficheiro", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Insira o novo nome para o ficheiro descarregado:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = newName,
                    onValueChange = {
                        newName = it
                        errorMessage = when {
                            it.trim().isEmpty() -> "O nome não pode estar vazio"
                            it.contains('/') || it.contains('\\') || it.contains(':') || it.contains('*') ||
                            it.contains('?') || it.contains('"') || it.contains('<') || it.contains('>') ||
                            it.contains('|') -> "Caracteres proibidos: / \\ : * ? \" < > |"
                            else -> null
                        }
                    },
                    label = { Text("Nome do ficheiro") },
                    suffix = if (extension.isNotEmpty()) {
                        { Text(extension, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold) }
                    } else null,
                    singleLine = true,
                    isError = errorMessage != null,
                    supportingText = {
                        errorMessage?.let {
                            Text(it, color = MaterialTheme.colorScheme.error)
                        } ?: if (extension.isNotEmpty()) {
                            Text("A extensão $extension será mantida automaticamente", style = MaterialTheme.typography.labelSmall)
                        } else null
                    },
                    trailingIcon = {
                        if (newName.isNotEmpty()) {
                            IconButton(onClick = { newName = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Limpar")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = newName.trim()
                    if (trimmed.isNotEmpty() && errorMessage == null) {
                        val fullName = if (trimmed.endsWith(extension) || extension.isEmpty()) trimmed else "$trimmed$extension"
                        onConfirm(fullName)
                    }
                },
                enabled = newName.trim().isNotEmpty() && errorMessage == null
            ) {
                Text("Renomear")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

@Composable
fun DeleteDownloadDialog(
    item: AdvancedDownloadItem,
    onDismiss: () -> Unit,
    onConfirm: (deleteFromDisk: Boolean) -> Unit
) {
    var deleteFromDisk by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        },
        title = {
            Text("Eliminar Transferência", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Deseja remover \"${item.fileName}\" da lista de transferências?",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                if (item.file != null && item.file.exists()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { deleteFromDisk = !deleteFromDisk }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = deleteFromDisk,
                            onCheckedChange = { deleteFromDisk = it }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Eliminar também o ficheiro do dispositivo",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(deleteFromDisk) },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Eliminar", color = MaterialTheme.colorScheme.onError)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

@Composable
fun ClearAllDownloadsDialog(
    onDismiss: () -> Unit,
    onConfirm: (deleteFromDisk: Boolean) -> Unit
) {
    var deleteFromDisk by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        },
        title = {
            Text("Limpar Transferências Concluídas", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Deseja remover todas as transferências concluídas e com falha da lista?",
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { deleteFromDisk = !deleteFromDisk }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = deleteFromDisk,
                        onCheckedChange = { deleteFromDisk = it }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Eliminar também os ficheiros do dispositivo",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(deleteFromDisk) },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Limpar", color = MaterialTheme.colorScheme.onError)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

@Composable
fun HistoryScreen(
    history: List<HistoryEntity>,
    viewModel: BrowserViewModel? = null,
    onOpenUrl: (String) -> Unit = {},
    onDeleteItem: (Long) -> Unit = {},
    onClearAll: () -> Unit = {}
) {
    val context = LocalContext.current
    var historySubTab by remember { mutableIntStateOf(0) } // 0: Páginas Web, 1: Vídeos
    var searchQuery by remember { mutableStateOf("") }
    var showClearConfirmDialog by remember { mutableStateOf(false) }
    var showClearVideoConfirmDialog by remember { mutableStateOf(false) }
    var resumeConfirmVideoItem by remember { mutableStateOf<VideoHistoryEntity?>(null) }

    val videoHistoryList = viewModel?.videoHistory?.collectAsStateWithLifecycle()?.value ?: emptyList()

    val filteredHistory = remember(history, searchQuery) {
        val query = searchQuery.trim()
        if (query.isEmpty()) {
            history
        } else {
            history.filter {
                it.title.contains(query, ignoreCase = true) ||
                it.url.contains(query, ignoreCase = true)
            }
        }
    }

    val filteredVideoHistory = remember(videoHistoryList, searchQuery) {
        val query = searchQuery.trim()
        if (query.isEmpty()) {
            videoHistoryList
        } else {
            videoHistoryList.filter {
                it.title.contains(query, ignoreCase = true) ||
                it.videoUrl.contains(query, ignoreCase = true) ||
                (it.subtitle?.contains(query, ignoreCase = true) == true)
            }
        }
    }

    // Confirmação para Continuar Reprodução no Histórico das Configurações
    resumeConfirmVideoItem?.let { itemToResume ->
        val formattedPos = remember(itemToResume.lastPositionMs) {
            val s = (itemToResume.lastPositionMs / 1000) % 60
            val m = (itemToResume.lastPositionMs / 60000) % 60
            val h = itemToResume.lastPositionMs / 3600000
            if (h > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
            else String.format(Locale.getDefault(), "%02d:%02d", m, s)
        }

        AlertDialog(
            onDismissRequest = { resumeConfirmVideoItem = null },
            icon = {
                Icon(
                    Icons.Default.PlayCircleFilled,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text(
                    text = "Continuar reprodução?",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Você parou em $formattedPos no vídeo \"${itemToResume.title}\".",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Deseja continuar de onde parou ou recomeçar do início?",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val item = itemToResume
                        resumeConfirmVideoItem = null
                        viewModel?.playVideoFromHistory(item, resumePlayback = true)
                    }
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Continuar ($formattedPos)")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        val item = itemToResume.copy(lastPositionMs = 0L)
                        resumeConfirmVideoItem = null
                        viewModel?.playVideoFromHistory(item, resumePlayback = false)
                    }
                ) {
                    Icon(Icons.Default.Replay, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Recomeçar")
                }
            }
        )
    }

    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            title = { Text("Limpar todo o histórico?", fontWeight = FontWeight.Bold) },
            text = { Text("Esta ação apagará permanentemente todas as páginas registadas no histórico.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearAll()
                        showClearConfirmDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Limpar Tudo", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }

    if (showClearVideoConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearVideoConfirmDialog = false },
            title = { Text("Limpar histórico de vídeos?", fontWeight = FontWeight.Bold) },
            text = { Text("Esta ação removerá todas as URLs de vídeos salvas no banco de dados local.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel?.clearVideoHistory()
                        showClearVideoConfirmDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Limpar Vídeos", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearVideoConfirmDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isCompactWidth = maxWidth < 360.dp
        val horizontalPadding = if (isCompactWidth) 10.dp else 16.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = horizontalPadding, vertical = 8.dp)
        ) {
            // Seletor de Sub-Abas: Páginas Web vs Vídeos Assistidos
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = historySubTab == 0,
                    onClick = {
                        historySubTab = 0
                        searchQuery = ""
                    },
                    label = { Text("Páginas Web (${history.size})", fontSize = 12.sp) },
                    leadingIcon = {
                        Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                )

                FilterChip(
                    selected = historySubTab == 1,
                    onClick = {
                        historySubTab = 1
                        searchQuery = ""
                    },
                    label = { Text("Vídeos (${videoHistoryList.size})", fontSize = 12.sp) },
                    leadingIcon = {
                        Icon(Icons.Default.VideoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                )
            }

            if (historySubTab == 0) {
                // Aba: Páginas Web
                if (history.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.History,
                                contentDescription = null,
                                modifier = Modifier.size(56.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                "O seu histórico está vazio",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Os sites visitados aparecerão aqui.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Pesquisar no histórico...") },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = "Pesquisar")
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "Limpar busca")
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${filteredHistory.size} página(s) ${if (searchQuery.isNotEmpty()) "encontrada(s)" else "registada(s)"}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        TextButton(
                            onClick = { showClearConfirmDialog = true },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Limpar Tudo", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    if (filteredHistory.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = 32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "Nenhum site encontrado para \"$searchQuery\"",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(bottom = 24.dp)
                        ) {
                            items(filteredHistory.size, key = { filteredHistory[it].id }) { index ->
                                val item = filteredHistory[index]
                                val date = remember(item.timestamp) {
                                    SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(item.timestamp))
                                }
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onOpenUrl(item.url) },
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(40.dp)
                                                .background(
                                                    MaterialTheme.colorScheme.primaryContainer,
                                                    CircleShape
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Public,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(12.dp))

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = item.title.ifEmpty { "Sem título" },
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 14.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = item.url,
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.primary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = date,
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                            )
                                        }

                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            IconButton(
                                                onClick = { onOpenUrl(item.url) },
                                                modifier = Modifier.size(36.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                                    contentDescription = "Acessar site",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }

                                            IconButton(
                                                onClick = { onDeleteItem(item.id) },
                                                modifier = Modifier.size(36.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.DeleteOutline,
                                                    contentDescription = "Remover do histórico",
                                                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // Aba: Vídeos Assistidos (Salvos no Room)
                if (videoHistoryList.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.size(56.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.VideoLibrary,
                                        contentDescription = null,
                                        modifier = Modifier.size(30.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Text(
                                "Nenhum vídeo reproduzido",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                "Os vídeos e áudios que você reproduzir aparecerão aqui automaticamente salvos no banco local.",
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 24.dp)
                            )
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Pesquisar vídeos...") },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = "Pesquisar")
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "Limpar busca")
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${filteredVideoHistory.size} vídeo(s) ${if (searchQuery.isNotEmpty()) "encontrado(s)" else "registado(s)"}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        TextButton(
                            onClick = { showClearVideoConfirmDialog = true },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Limpar Vídeos", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    if (filteredVideoHistory.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = 32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "Nenhum vídeo encontrado para \"$searchQuery\"",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(bottom = 24.dp)
                        ) {
                            items(filteredVideoHistory.size, key = { filteredVideoHistory[it].id }) { index ->
                                val item = filteredVideoHistory[index]
                                val date = remember(item.timestamp) {
                                    SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(item.timestamp))
                                }
                                val hasThumbnail = !item.thumbnailUrl.isNullOrBlank()
                                val hasProgress = item.lastPositionMs > 3000L
                                val progressFraction = remember(item.lastPositionMs, item.durationMs) {
                                    if (item.durationMs > 0L) {
                                        (item.lastPositionMs.toFloat() / item.durationMs.toFloat()).coerceIn(0f, 1f)
                                    } else 0f
                                }

                                val handlePlayItem = {
                                    if (hasProgress && (item.durationMs == 0L || item.lastPositionMs < item.durationMs - 2000L)) {
                                        resumeConfirmVideoItem = item
                                    } else {
                                        viewModel?.playVideoFromHistory(item, resumePlayback = false)
                                    }
                                }

                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { handlePlayItem() },
                                    shape = RoundedCornerShape(14.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        // Miniatura Visual (Thumbnail)
                                        Box(
                                            modifier = Modifier
                                                .size(width = 80.dp, height = 54.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color(0xFF14171F)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (hasThumbnail) {
                                                AsyncImage(
                                                    model = item.thumbnailUrl,
                                                    contentDescription = item.title,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .background(Color.Black.copy(alpha = 0.25f))
                                                )
                                                Surface(
                                                    shape = CircleShape,
                                                    color = Color.Black.copy(alpha = 0.55f),
                                                    modifier = Modifier.size(22.dp)
                                                ) {
                                                    Box(contentAlignment = Alignment.Center) {
                                                        Icon(
                                                            imageVector = if (item.isAudioOnly) Icons.Default.Audiotrack else Icons.Default.PlayArrow,
                                                            contentDescription = null,
                                                            tint = Color.White,
                                                            modifier = Modifier.size(14.dp)
                                                        )
                                                    }
                                                }
                                            } else {
                                                Surface(
                                                    shape = RoundedCornerShape(8.dp),
                                                    color = if (item.isAudioOnly) Color(0xFF00B0FF).copy(alpha = 0.15f)
                                                    else MaterialTheme.colorScheme.primaryContainer,
                                                    modifier = Modifier.fillMaxSize()
                                                ) {
                                                    Box(contentAlignment = Alignment.Center) {
                                                        Icon(
                                                            imageVector = if (item.isAudioOnly) Icons.Default.Audiotrack else Icons.Default.PlayCircleFilled,
                                                            contentDescription = null,
                                                            tint = if (item.isAudioOnly) Color(0xFF00B0FF) else MaterialTheme.colorScheme.primary,
                                                            modifier = Modifier.size(24.dp)
                                                        )
                                                    }
                                                }
                                            }

                                            // Barra de progresso assistido
                                            if (hasProgress && progressFraction > 0f) {
                                                LinearProgressIndicator(
                                                    progress = { progressFraction },
                                                    modifier = Modifier
                                                        .align(Alignment.BottomCenter)
                                                        .fillMaxWidth()
                                                        .height(2.5.dp),
                                                    color = Color(0xFFFF3D00),
                                                    trackColor = Color.Black.copy(alpha = 0.5f)
                                                )
                                            }
                                        }

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = item.title.ifEmpty { "Vídeo Sem Título" },
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 13.5.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )

                                            if (hasProgress) {
                                                val s = (item.lastPositionMs / 1000) % 60
                                                val m = (item.lastPositionMs / 60000) % 60
                                                val h = item.lastPositionMs / 3600000
                                                val posStr = if (h > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
                                                else String.format(Locale.getDefault(), "%02d:%02d", m, s)

                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text(
                                                    text = "Parou em $posStr",
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }

                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = item.videoUrl,
                                                fontSize = 10.5.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = date,
                                                fontSize = 9.5.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                            )
                                        }

                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            IconButton(
                                                onClick = { handlePlayItem() },
                                                modifier = Modifier.size(34.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.PlayArrow,
                                                    contentDescription = "Reproduzir",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }

                                            IconButton(
                                                onClick = {
                                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                                    val clip = android.content.ClipData.newPlainText("URL do Vídeo", item.videoUrl)
                                                    clipboard.setPrimaryClip(clip)
                                                    viewModel?.showNotification("URL do vídeo copiada")
                                                },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.ContentCopy,
                                                    contentDescription = "Copiar URL",
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }

                                            IconButton(
                                                onClick = { viewModel?.deleteVideoHistoryEntry(item.id) },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.DeleteOutline,
                                                    contentDescription = "Remover",
                                                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DownloadFolderPreferenceRow(
    folderName: String,
    isCustom: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.FolderSpecial,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Pasta de Downloads",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = folderName,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        OutlinedButton(
            onClick = onClick,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.height(32.dp)
        ) {
            Text("Alterar", fontSize = 12.sp)
        }
    }
}

@Composable
fun StoragePermissionsStatusRow(
    permissionInfo: StoragePermissionInfo,
    onCheckOrRequest: () -> Unit
) {
    val allGood = permissionInfo.hasStorageAccess && permissionInfo.hasNotificationPermission
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onCheckOrRequest)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (allGood) Icons.Default.CheckCircle else Icons.Default.SecurityUpdateGood,
            contentDescription = null,
            tint = if (allGood) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Centro de Permissões",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Verificar e gerir autorizações de câmara, microfone, localização e downloads",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        FilledTonalButton(
            onClick = onCheckOrRequest,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.height(32.dp)
        ) {
            Text("Gerir", fontSize = 11.sp)
        }
    }
}

@Composable
fun FolderSelectionDialog(
    currentUri: String?,
    onSelectPreset: (FolderPreset) -> Unit,
    onChooseCustom: () -> Unit,
    onResetDefault: () -> Unit,
    onDismiss: () -> Unit
) {
    val presets = remember { DownloadStorageHelper.getStandardPresets() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.FolderOpen,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Pasta de Downloads", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                item {
                    Text(
                        text = "Selecione a pasta onde os ficheiros descarregados serão guardados:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }

                items(presets) { preset ->
                    val isSelected = (currentUri == null && preset.isDefault) || currentUri == preset.uriOrPath
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectPreset(preset) }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = when (preset.id) {
                                "documents" -> Icons.Default.Description
                                "movies" -> Icons.Default.Movie
                                "music" -> Icons.Default.MusicNote
                                "pictures" -> Icons.Default.Image
                                else -> Icons.Default.Folder
                            },
                            contentDescription = null,
                            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = preset.name,
                                fontSize = 14.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = preset.description,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Selecionado",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                }

                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onChooseCustom)
                            .padding(vertical = 12.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CreateNewFolder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Escolher outra pasta no dispositivo...",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            Text(
                                text = "Permite selecionar qualquer pasta no armazenamento interno ou SD",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (currentUri != null) {
                    item {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onResetDefault)
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.RestartAlt,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "Restaurar Pasta Padrão (Downloads)",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Fechar")
            }
        }
    )
}
