package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.database.HistoryEntity
import com.example.database.ShortcutEntity
import java.net.URI

data class ShortcutUiModel(
    val id: Long,
    val title: String,
    val url: String,
    val isCustom: Boolean = true
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeDashboard(
    onSearch: (String) -> Unit,
    history: List<HistoryEntity> = emptyList(),
    shortcuts: List<ShortcutEntity> = emptyList(),
    onAddShortcut: (String, String) -> Unit = { _, _ -> },
    onUpdateShortcut: (Long, String, String) -> Unit = { _, _, _ -> },
    onDeleteShortcut: (Long) -> Unit = {},
    onResetShortcuts: () -> Unit = {},
    onOpenPlayStreamDialog: () -> Unit = {}
) {
    var searchQuery by remember { mutableStateOf("") }
    val haptic = LocalHapticFeedback.current
    val clipboard = LocalClipboardManager.current

    // Dialog & Sheet States
    var showAddEditDialog by remember { mutableStateOf(false) }
    var editingShortcut by remember { mutableStateOf<ShortcutUiModel?>(null) }
    var contextMenuShortcut by remember { mutableStateOf<ShortcutUiModel?>(null) }
    var showManageSheet by remember { mutableStateOf(false) }
    var showResetConfirmDialog by remember { mutableStateOf(false) }

    // Resolve active shortcuts: prioritize custom persisted shortcuts from database
    val displayShortcuts = remember(shortcuts, history) {
        if (shortcuts.isNotEmpty()) {
            shortcuts.map { ShortcutUiModel(it.id, it.title, it.url, isCustom = true) }
        } else {
            // Curated default initial shortcuts if database is still loading or empty
            listOf(
                ShortcutUiModel(1L, "Google", "https://www.google.com", isCustom = false),
                ShortcutUiModel(2L, "YouTube", "https://www.youtube.com", isCustom = false),
                ShortcutUiModel(3L, "GitHub", "https://github.com", isCustom = false),
                ShortcutUiModel(4L, "LinkedIn", "https://www.linkedin.com", isCustom = false),
                ShortcutUiModel(5L, "Reddit", "https://www.reddit.com", isCustom = false),
                ShortcutUiModel(6L, "X (Twitter)", "https://x.com", isCustom = false),
                ShortcutUiModel(7L, "Tiktok", "https://www.tiktok.com", isCustom = false),
                ShortcutUiModel(8L, "Notícias", "https://news.google.com", isCustom = false)
            )
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // Background Image loaded dynamically
        Image(
            painter = painterResource(id = R.drawable.img_home_background),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        // Modern dynamic gradient overlay (rich deep scrim)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.65f),
                            Color.Black.copy(alpha = 0.50f),
                            Color.Black.copy(alpha = 0.75f)
                        )
                    )
                )
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .widthIn(max = 540.dp)
                .imePadding()
        ) {
            // Brand Logo & Title with subtle glow
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(bottom = 24.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.15f),
                    modifier = Modifier
                        .size(64.dp)
                        .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = "Focus Browser Logo",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Focus Browser",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    letterSpacing = 0.5.sp
                )

                Text(
                    text = "Navegação rápida, limpa e focada",
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.75f),
                    fontWeight = FontWeight.Normal
                )
            }

            // Modern Floating Search Box with Glassmorphism
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(elevation = 8.dp, shape = RoundedCornerShape(28.dp)),
                shape = RoundedCornerShape(28.dp),
                color = Color.White.copy(alpha = 0.18f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f))
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("home_search_input"),
                    placeholder = { 
                        Text(
                            "Pesquisar na web ou digitar endereço...", 
                            color = Color.White.copy(alpha = 0.75f),
                            fontSize = 14.sp
                        ) 
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    leadingIcon = {
                        Icon(
                            Icons.Default.Search, 
                            contentDescription = "Pesquisar", 
                            tint = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.size(22.dp)
                        )
                    },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Search
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onSearch = { 
                            if (searchQuery.isNotBlank()) {
                                onSearch(searchQuery.trim()) 
                            }
                        }
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedBorderColor = Color.Transparent,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = Color.White
                    )
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Customizable Quick Shortcuts Section
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = Color.White.copy(alpha = 0.10f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Header with Customization & Add buttons
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Bookmark,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.9f),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Atalhos Rápidos",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White.copy(alpha = 0.95f),
                                letterSpacing = 0.3.sp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = CircleShape,
                                color = Color.White.copy(alpha = 0.18f),
                                modifier = Modifier.padding(horizontal = 4.dp)
                            ) {
                                Text(
                                    text = "${displayShortcuts.size}",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White.copy(alpha = 0.9f),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            // Quick Add button
                            IconButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    editingShortcut = null
                                    showAddEditDialog = true
                                },
                                modifier = Modifier
                                    .size(36.dp)
                                    .testTag("add_shortcut_header_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "Adicionar Atalho",
                                    tint = Color.White.copy(alpha = 0.9f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            // Manage / Customize button
                            IconButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    showManageSheet = true
                                },
                                modifier = Modifier
                                    .size(36.dp)
                                    .testTag("customize_shortcuts_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Tune,
                                    contentDescription = "Personalizar Atalhos",
                                    tint = Color.White.copy(alpha = 0.9f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    // Grid of Shortcuts (4 columns) + Add button slot
                    val allItemsList = displayShortcuts
                    val chunked = allItemsList.chunked(4)

                    for ((rowIndex, rowItems) in chunked.withIndex()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            for (item in rowItems) {
                                ShortcutItem(
                                    title = item.title,
                                    url = item.url,
                                    onClick = { onSearch(item.url) },
                                    onLongClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        contextMenuShortcut = item
                                    }
                                )
                            }

                            // If this is the last row and there are less than 4 items, add the "+" tile to complete the row
                            if (rowIndex == chunked.lastIndex && rowItems.size < 4) {
                                AddShortcutItem(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        editingShortcut = null
                                        showAddEditDialog = true
                                    }
                                )
                                // Fill remaining empty spaces if row has 1 or 2 items
                                repeat(3 - rowItems.size) {
                                    Spacer(modifier = Modifier.width(72.dp))
                                }
                            }
                        }
                    }

                    // If all rows were perfectly full (e.g. 4 or 8 items), show the "+" tile on a new row
                    if (allItemsList.size % 4 == 0) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start
                        ) {
                            AddShortcutItem(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    editingShortcut = null
                                    showAddEditDialog = true
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // Context Menu on Long Press
    contextMenuShortcut?.let { item ->
        ModalBottomSheet(
            onDismissRequest = { contextMenuShortcut = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .navigationBarsPadding()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = item.title.firstOrNull()?.uppercase() ?: "A",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = item.url,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                Spacer(modifier = Modifier.height(8.dp))

                // Action: Open
                ListItem(
                    headlineContent = { Text("Abrir Atalho") },
                    leadingContent = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
                    modifier = Modifier.clickable {
                        contextMenuShortcut = null
                        onSearch(item.url)
                    }
                )

                // Action: Edit
                ListItem(
                    headlineContent = { Text("Editar Atalho") },
                    leadingContent = { Icon(Icons.Default.Edit, contentDescription = null) },
                    modifier = Modifier.clickable {
                        val toEdit = item
                        contextMenuShortcut = null
                        editingShortcut = toEdit
                        showAddEditDialog = true
                    }
                )

                // Action: Copy URL
                ListItem(
                    headlineContent = { Text("Copiar Link") },
                    leadingContent = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                    modifier = Modifier.clickable {
                        clipboard.setText(AnnotatedString(item.url))
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        contextMenuShortcut = null
                    }
                )

                // Action: Delete
                ListItem(
                    headlineContent = { Text("Eliminar Atalho", color = MaterialTheme.colorScheme.error) },
                    leadingContent = { Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    modifier = Modifier.clickable {
                        val toDeleteId = item.id
                        contextMenuShortcut = null
                        onDeleteShortcut(toDeleteId)
                    }
                )

                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }

    // Full Management BottomSheet
    if (showManageSheet) {
        ModalBottomSheet(
            onDismissRequest = { showManageSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Personalizar Atalhos",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    IconButton(onClick = { showManageSheet = false }) {
                        Icon(Icons.Default.Close, contentDescription = "Fechar")
                    }
                }

                Text(
                    text = "Gerencie os sites rápidos da sua tela inicial. Toque para editar ou excluir.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 14.dp)
                )

                // Scrollable List of Shortcuts
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(displayShortcuts, key = { it.id }) { item ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = item.title.firstOrNull()?.uppercase() ?: "A",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.title,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = item.url,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        editingShortcut = item
                                        showAddEditDialog = true
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = "Editar",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        onDeleteShortcut(item.id)
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        Icons.Default.DeleteOutline,
                                        contentDescription = "Eliminar",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Bottom Action Buttons
                Button(
                    onClick = {
                        editingShortcut = null
                        showAddEditDialog = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Adicionar Novo Atalho")
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = { showResetConfirmDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Restaurar Atalhos Padrão")
                }

                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }

    // Add / Edit Shortcut Dialog
    if (showAddEditDialog) {
        AddEditShortcutDialog(
            shortcut = editingShortcut,
            onDismiss = {
                showAddEditDialog = false
                editingShortcut = null
            },
            onSave = { title, url ->
                val target = editingShortcut
                if (target != null && target.id > 0) {
                    onUpdateShortcut(target.id, title, url)
                } else {
                    onAddShortcut(title, url)
                }
                showAddEditDialog = false
                editingShortcut = null
            },
            onDelete = {
                val target = editingShortcut
                if (target != null && target.id > 0) {
                    onDeleteShortcut(target.id)
                }
                showAddEditDialog = false
                editingShortcut = null
            }
        )
    }

    // Reset Confirmation Dialog
    if (showResetConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showResetConfirmDialog = false },
            icon = { Icon(Icons.Default.RestartAlt, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("Restaurar Atalhos Padrão?") },
            text = {
                Text("Esta ação restaurará a lista inicial recomendada (Google, YouTube, GitHub, LinkedIn, Reddit, X, Tiktok e Notícias). Seus atalhos personalizados serão substituídos.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onResetShortcuts()
                        showResetConfirmDialog = false
                    }
                ) {
                    Text("Restaurar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShortcutItem(
    title: String,
    url: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val context = LocalContext.current
    var isImageError by remember { mutableStateOf(false) }

    val domain = remember(url) {
        try {
            val uri = URI(url)
            val host = uri.host ?: ""
            host.removePrefix("www.")
        } catch (_: Exception) {
            ""
        }
    }

    // High-resolution Google Favicon Service URL
    val faviconUrl = remember(domain) {
        if (domain.isNotEmpty()) {
            "https://www.google.com/s2/favicons?domain=$domain&sz=128"
        } else ""
    }

    // Fallback brand color & initial
    val (brandBackground, initial) = remember(domain, title) {
        val d = domain.lowercase()
        val bg = when {
            d.contains("youtube") -> Color(0xFFCC0000)
            d.contains("google") -> Color(0xFF4285F4)
            d.contains("github") -> Color(0xFF24292E)
            d.contains("wikipedia") -> Color(0xFF4A5568)
            d.contains("reddit") -> Color(0xFFFF4500)
            d.contains("x.com") || d.contains("twitter") -> Color(0xFF111111)
            d.contains("twitch") -> Color(0xFF9146FF)
            d.contains("facebook") -> Color(0xFF1877F2)
            d.contains("instagram") -> Color(0xFFE1306C)
            else -> Color.White.copy(alpha = 0.22f)
        }
        val letter = title.firstOrNull()?.uppercase() ?: (domain.firstOrNull()?.uppercase() ?: "W")
        Pair(bg, letter)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(72.dp)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(vertical = 4.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = Color.White.copy(alpha = 0.20f),
            modifier = Modifier
                .size(48.dp)
                .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape),
            shadowElevation = 2.dp
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                if (faviconUrl.isNotEmpty() && !isImageError) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(faviconUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = title,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape),
                        contentScale = ContentScale.Fit,
                        onError = {
                            isImageError = true
                        }
                    )
                }

                // Fallback letter or branding icon if favicon fails or is empty
                if (faviconUrl.isEmpty() || isImageError) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(brandBackground),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = initial,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = title,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White.copy(alpha = 0.95f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun AddShortcutItem(
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(72.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = Color.White.copy(alpha = 0.08f),
            modifier = Modifier
                .size(48.dp)
                .border(1.dp, Color.White.copy(alpha = 0.40f), CircleShape),
            shadowElevation = 1.dp
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Adicionar Atalho",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "Adicionar",
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White.copy(alpha = 0.85f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun AddEditShortcutDialog(
    shortcut: ShortcutUiModel?,
    onDismiss: () -> Unit,
    onSave: (title: String, url: String) -> Unit,
    onDelete: () -> Unit
) {
    var title by remember(shortcut) { mutableStateOf(shortcut?.title ?: "") }
    var url by remember(shortcut) { mutableStateOf(shortcut?.url ?: "") }

    // Auto domain helper
    val extractedDomain = remember(url) {
        try {
            val uri = URI(url.trim())
            val host = uri.host ?: ""
            host.removePrefix("www.").replaceFirstChar { it.uppercase() }
        } catch (_: Exception) {
            ""
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (shortcut != null) "Editar Atalho" else "Adicionar Atalho",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Nome do Atalho") },
                    placeholder = { Text("Ex: YouTube, Google...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        if (title.isNotEmpty()) {
                            IconButton(onClick = { title = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Limpar")
                            }
                        }
                    }
                )

                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Endereço Web (URL)") },
                    placeholder = { Text("https://exemplo.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        if (url.isNotEmpty()) {
                            IconButton(onClick = { url = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Limpar")
                            }
                        }
                    }
                )

                // Suggest name chip if title is blank and domain exists
                if (title.isBlank() && extractedDomain.isNotBlank()) {
                    FilledTonalButton(
                        onClick = { title = extractedDomain },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Usar \"$extractedDomain\" como nome", fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val finalUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        "https://${url.trim()}"
                    } else {
                        url.trim()
                    }
                    val finalTitle = if (title.isBlank()) extractedDomain.ifBlank { finalUrl } else title.trim()
                    onSave(finalTitle, finalUrl)
                },
                enabled = url.isNotBlank()
            ) {
                Text("Salvar")
            }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (shortcut != null) {
                    TextButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Excluir")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancelar")
                }
            }
        }
    )
}
