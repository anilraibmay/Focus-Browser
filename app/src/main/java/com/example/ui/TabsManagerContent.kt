package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.BrowserTab
import kotlinx.coroutines.launch
import kotlin.math.abs

private val PrivatePurple = Color(0xFF7C4DFF)
private val PrivatePurpleLight = Color(0xFFB388FF)
private val PrivateDarkContainer = Color(0xFF1E1B2E)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabsManagerContent(
    isMaximized: Boolean,
    onToggleMaximize: () -> Unit,
    tabs: List<BrowserTab>,
    activeTabId: String,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onNewTab: () -> Unit,
    onNewPrivateTab: () -> Unit = {},
    onCloseAllTabs: () -> Unit = {},
    onCloseAllPrivateTabs: () -> Unit = {}
) {
    val haptic = LocalHapticFeedback.current
    val activeTab = tabs.find { it.id == activeTabId }
    
    // Tab mode: 0 = Normal, 1 = Anônima (Incógnito)
    var selectedTabMode by remember(activeTab?.isPrivate) {
        mutableStateOf(if (activeTab?.isPrivate == true) 1 else 0)
    }

    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    var showCloseAllDialog by remember { mutableStateOf(false) }
    var showCloseAllPrivateDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    val normalTabs = remember(tabs) { tabs.filter { !it.isPrivate } }
    val privateTabs = remember(tabs) { tabs.filter { it.isPrivate } }

    val currentModeTabs = if (selectedTabMode == 1) privateTabs else normalTabs

    val filteredTabs = remember(currentModeTabs, searchQuery) {
        if (searchQuery.isBlank()) {
            currentModeTabs
        } else {
            val query = searchQuery.trim().lowercase()
            currentModeTabs.filter {
                it.title.lowercase().contains(query) || it.url.lowercase().contains(query)
            }
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("tabs_manager_scaffold"),
        containerColor = if (selectedTabMode == 1) Color(0xFF0F0E17) else MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (selectedTabMode == 1) Color(0xFF161522) else MaterialTheme.colorScheme.surface
                    )
            ) {
                CenterAlignedTopAppBar(
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    title = {
                        Text(
                            text = if (selectedTabMode == 1) "Navegação Anônima" else "Gerenciador de Guias",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (selectedTabMode == 1) PrivatePurpleLight else MaterialTheme.colorScheme.onSurface
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onToggleMaximize()
                            },
                            modifier = Modifier.testTag("tabs_manager_back_button")
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Voltar para o navegador",
                                tint = if (selectedTabMode == 1) Color.White else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    },
                    actions = {
                        if (currentModeTabs.size > 2) {
                            IconButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    isSearchActive = !isSearchActive
                                    if (!isSearchActive) searchQuery = ""
                                },
                                modifier = Modifier.testTag("tabs_search_toggle")
                            ) {
                                Icon(
                                    if (isSearchActive) Icons.Default.Close else Icons.Default.Search,
                                    contentDescription = if (isSearchActive) "Fechar pesquisa" else "Pesquisar abas",
                                    tint = if (selectedTabMode == 1) Color.White else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                        IconButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                if (selectedTabMode == 1) onNewPrivateTab() else onNewTab()
                            },
                            modifier = Modifier.testTag("top_bar_new_tab_button")
                        ) {
                            Icon(
                                if (selectedTabMode == 1) Icons.Outlined.Security else Icons.Default.Add,
                                contentDescription = if (selectedTabMode == 1) "Nova Aba Anônima" else "Nova Aba",
                                tint = if (selectedTabMode == 1) PrivatePurpleLight else MaterialTheme.colorScheme.primary
                            )
                        }
                        Box {
                            IconButton(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                showMenu = true
                            }) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    contentDescription = "Mais opções",
                                    tint = if (selectedTabMode == 1) Color.White else MaterialTheme.colorScheme.onSurface
                                )
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Nova guia padrão") },
                                    leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                                    onClick = {
                                        showMenu = false
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        selectedTabMode = 0
                                        onNewTab()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Nova guia anônima") },
                                    leadingIcon = { Icon(Icons.Outlined.Security, contentDescription = null, tint = PrivatePurple) },
                                    onClick = {
                                        showMenu = false
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        selectedTabMode = 1
                                        onNewPrivateTab()
                                    }
                                )
                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                if (privateTabs.isNotEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("Fechar guias anônimas (${privateTabs.size})") },
                                        leadingIcon = { Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                        onClick = {
                                            showMenu = false
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            showCloseAllPrivateDialog = true
                                        }
                                    )
                                }
                                if (normalTabs.isNotEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("Fechar guias normais (${normalTabs.size})") },
                                        leadingIcon = { Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                        onClick = {
                                            showMenu = false
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            showCloseAllDialog = true
                                        }
                                    )
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = if (selectedTabMode == 1) Color(0xFF161522) else MaterialTheme.colorScheme.surface
                    )
                )

                // Segmented Selector: [ Padrão (N) ] | [ Anônima (M) ]
                Surface(
                    color = if (selectedTabMode == 1) Color(0xFF12111D) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Normal Tabs Segment
                        Surface(
                            onClick = {
                                if (selectedTabMode != 0) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    selectedTabMode = 0
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = if (selectedTabMode == 0) MaterialTheme.colorScheme.surface else Color.Transparent,
                            shadowElevation = if (selectedTabMode == 0) 2.dp else 0.dp,
                            modifier = Modifier.weight(1f)
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Tab,
                                    contentDescription = null,
                                    tint = if (selectedTabMode == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(17.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Padrão (${normalTabs.size})",
                                    fontWeight = if (selectedTabMode == 0) FontWeight.Bold else FontWeight.Medium,
                                    color = if (selectedTabMode == 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }

                        // Private / Incognito Tabs Segment
                        Surface(
                            onClick = {
                                if (selectedTabMode != 1) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    selectedTabMode = 1
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = if (selectedTabMode == 1) PrivatePurple else Color.Transparent,
                            shadowElevation = if (selectedTabMode == 1) 3.dp else 0.dp,
                            modifier = Modifier.weight(1f)
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Security,
                                    contentDescription = null,
                                    tint = if (selectedTabMode == 1) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(17.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Anônima (${privateTabs.size})",
                                    fontWeight = if (selectedTabMode == 1) FontWeight.Bold else FontWeight.Medium,
                                    color = if (selectedTabMode == 1) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }
                }

                AnimatedVisibility(
                    visible = isSearchActive,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                            .testTag("tabs_search_input"),
                        placeholder = { Text("Filtrar abas por título ou link...") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Limpar filtro")
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = if (selectedTabMode == 1) Color(0xFF1E1B2E) else MaterialTheme.colorScheme.surfaceVariant,
                            unfocusedContainerColor = if (selectedTabMode == 1) Color(0xFF1E1B2E) else MaterialTheme.colorScheme.surfaceVariant
                        )
                    )
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (selectedTabMode == 1) onNewPrivateTab() else onNewTab()
                },
                icon = {
                    Icon(
                        imageVector = if (selectedTabMode == 1) Icons.Outlined.Security else Icons.Default.Add,
                        contentDescription = null
                    )
                },
                text = {
                    Text(
                        text = if (selectedTabMode == 1) "Nova guia anônima" else "Nova guia",
                        fontWeight = FontWeight.Bold
                    )
                },
                containerColor = if (selectedTabMode == 1) PrivatePurple else MaterialTheme.colorScheme.primary,
                contentColor = Color.White,
                modifier = Modifier
                    .padding(bottom = 12.dp)
                    .testTag("fab_new_tab")
            )
        },
        floatingActionButtonPosition = FabPosition.Center
    ) { innerPadding ->
        if (filteredTabs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Surface(
                        shape = CircleShape,
                        color = if (selectedTabMode == 1) PrivatePurple.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(88.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (selectedTabMode == 1) Icons.Outlined.Security else if (searchQuery.isNotBlank()) Icons.Default.SearchOff else Icons.Default.Tab,
                                contentDescription = null,
                                modifier = Modifier.size(44.dp),
                                tint = if (selectedTabMode == 1) PrivatePurpleLight else MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(18.dp))
                    Text(
                        text = when {
                            searchQuery.isNotBlank() -> "Nenhuma aba encontrada"
                            selectedTabMode == 1 -> "Nenhuma guia anônima aberta"
                            else -> "Nenhuma guia aberta"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (selectedTabMode == 1) Color.White else MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = when {
                            searchQuery.isNotBlank() -> "Nenhum resultado corresponde a \"$searchQuery\"."
                            selectedTabMode == 1 -> "No modo anônimo, o histórico, cookies e pesquisas não são armazenados no dispositivo."
                            else -> "Toque no botão abaixo para abrir uma nova guia e navegar na web."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (selectedTabMode == 1) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    Spacer(modifier = Modifier.height(22.dp))
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (searchQuery.isNotBlank()) {
                                searchQuery = ""
                            } else if (selectedTabMode == 1) {
                                onNewPrivateTab()
                            } else {
                                onNewTab()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selectedTabMode == 1) PrivatePurple else MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            imageVector = if (searchQuery.isNotBlank()) Icons.Default.Refresh else if (selectedTabMode == 1) Icons.Outlined.Security else Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            if (searchQuery.isNotBlank()) "Limpar busca"
                            else if (selectedTabMode == 1) "Abrir Guia Anônima"
                            else "Abrir Nova Aba"
                        )
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                // Incognito Privacy Shield Banner (if in Incognito Mode)
                if (selectedTabMode == 1) {
                    Surface(
                        color = PrivatePurple.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, PrivatePurple.copy(alpha = 0.35f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Outlined.Shield,
                                contentDescription = null,
                                tint = PrivatePurpleLight,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Navegação privativa: cookies e histórico são apagados ao encerrar.",
                                fontSize = 11.5.sp,
                                color = Color.White.copy(alpha = 0.9f),
                                lineHeight = 14.sp
                            )
                        }
                    }
                }

                // Subtle gesture guide
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Toque para abrir ou deslize para os lados para fechar",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selectedTabMode == 1) Color.White.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.CompareArrows,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = if (selectedTabMode == 1) PrivatePurpleLight.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }

                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 155.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("tabs_grid"),
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(filteredTabs, key = { it.id }) { tab ->
                        SwipeableTabGridItem(
                            tab = tab,
                            isActive = tab.id == activeTabId,
                            onSelect = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelectTab(tab.id)
                            },
                            onClose = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onCloseTab(tab.id)
                            }
                        )
                    }
                }
            }
        }
    }

    if (showCloseAllDialog) {
        AlertDialog(
            onDismissRequest = { showCloseAllDialog = false },
            icon = { Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Fechar todas as abas normais?") },
            text = { Text("Todas as abas padrão serão encerradas e uma nova guia inicial será iniciada.") },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        showCloseAllDialog = false
                        onCloseAllTabs()
                    }
                ) {
                    Text("Fechar todas")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCloseAllDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }

    if (showCloseAllPrivateDialog) {
        AlertDialog(
            onDismissRequest = { showCloseAllPrivateDialog = false },
            icon = { Icon(Icons.Outlined.Security, contentDescription = null, tint = PrivatePurple) },
            title = { Text("Fechar guias anônimas?") },
            text = { Text("Todas as guias privativas serão fechadas e seus dados de sessão descartados.") },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = PrivatePurple),
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        showCloseAllPrivateDialog = false
                        onCloseAllPrivateTabs()
                    }
                ) {
                    Text("Encerrar anônimas")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCloseAllPrivateDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }
}

/**
 * Item de grade com gesto de swipe (deslizar para fechar) estilo Chrome e feedback tátil integrado.
 */
@Composable
fun SwipeableTabGridItem(
    tab: BrowserTab,
    isActive: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    var itemWidthPx by remember { mutableFloatStateOf(0f) }
    var isDismissed by remember { mutableStateOf(false) }
    var hasTriggeredThresholdHaptic by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.72f)
            .onSizeChanged { size ->
                itemWidthPx = size.width.toFloat()
            }
    ) {
        val currentOffset = offsetX.value
        val isSwiping = abs(currentOffset) > 4f
        val maxSwipeDist = if (itemWidthPx > 0f) itemWidthPx * 0.45f else 150f
        val swipeFraction = if (maxSwipeDist > 0f) (abs(currentOffset) / maxSwipeDist).coerceIn(0f, 1f) else 0f

        // Background reveal during swipe with delete icon
        if (isSwiping) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp)),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = (0.35f + 0.65f * swipeFraction).coerceIn(0f, 1f)),
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.error.copy(alpha = (0.2f + 0.8f * swipeFraction).coerceIn(0f, 1f)))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = if (currentOffset > 0) Alignment.CenterStart else Alignment.CenterEnd
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Fechar aba",
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size((22 + (8 * swipeFraction)).dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Fechar",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }

        // Foreground Tab Card with horizontal drag gesture (Chrome style)
        val normalizedOffset = if (itemWidthPx > 0f) currentOffset / itemWidthPx else 0f
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = currentOffset
                    rotationZ = (normalizedOffset * 6f).coerceIn(-8f, 8f)
                    val scaleFactor = (1f - abs(normalizedOffset) * 0.08f).coerceIn(0.92f, 1f)
                    scaleX = if (isDismissed) 0.85f else scaleFactor
                    scaleY = if (isDismissed) 0.85f else scaleFactor
                    alpha = if (isDismissed) 0f else (1f - (abs(currentOffset) / (itemWidthPx.coerceAtLeast(1f) * 0.9f))).coerceIn(0f, 1f)
                    shadowElevation = if (isSwiping) 8.dp.toPx() else 2.dp.toPx()
                }
                .pointerInput(tab.id) {
                    val velocityTracker = VelocityTracker()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        velocityTracker.resetTracking()
                        velocityTracker.addPosition(down.uptimeMillis, down.position)
                        hasTriggeredThresholdHaptic = false

                        var isHorizontalSwipeActive = false
                        var totalDeltaX = 0f
                        var totalDeltaY = 0f
                        val touchSlop = viewConfiguration.touchSlop

                        while (true) {
                            val event = awaitPointerEvent()
                            val dragChange = event.changes.firstOrNull { it.id == down.id } ?: break

                            if (!dragChange.pressed) {
                                if (isHorizontalSwipeActive) {
                                    val velocityX = velocityTracker.calculateVelocity().x
                                    val currentX = offsetX.value
                                    val threshold = if (itemWidthPx > 0f) itemWidthPx * 0.26f else 90f
                                    val isFling = abs(velocityX) > 650f
                                    val isPastThreshold = abs(currentX) > threshold

                                    if (isFling || isPastThreshold) {
                                        val exitRight = if (isFling) velocityX > 0 else currentX > 0
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        coroutineScope.launch {
                                            isDismissed = true
                                            val target = if (exitRight) itemWidthPx * 1.25f else -itemWidthPx * 1.25f
                                            offsetX.animateTo(
                                                targetValue = target,
                                                animationSpec = tween(durationMillis = 140)
                                            )
                                            onClose()
                                        }
                                    } else {
                                        coroutineScope.launch {
                                            offsetX.animateTo(
                                                targetValue = 0f,
                                                animationSpec = spring(
                                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                                    stiffness = Spring.StiffnessMediumLow
                                                )
                                            )
                                        }
                                    }
                                }
                                break
                            }

                            velocityTracker.addPosition(dragChange.uptimeMillis, dragChange.position)
                            val delta = dragChange.positionChange()

                            if (!isHorizontalSwipeActive) {
                                totalDeltaX += delta.x
                                totalDeltaY += delta.y
                                val absX = abs(totalDeltaX)
                                val absY = abs(totalDeltaY)

                                if (absX > touchSlop && absX > absY * 1.15f) {
                                    isHorizontalSwipeActive = true
                                    dragChange.consume()
                                } else if (absY > touchSlop && absY > absX) {
                                    break
                                }
                            } else {
                                dragChange.consume()
                                val newOffset = offsetX.value + delta.x
                                val threshold = if (itemWidthPx > 0f) itemWidthPx * 0.26f else 90f

                                if (abs(newOffset) > threshold && !hasTriggeredThresholdHaptic) {
                                    hasTriggeredThresholdHaptic = true
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                } else if (abs(newOffset) <= threshold && hasTriggeredThresholdHaptic) {
                                    hasTriggeredThresholdHaptic = false
                                }

                                coroutineScope.launch {
                                    offsetX.snapTo(newOffset)
                                }
                            }
                        }
                    }
                }
        ) {
            TabPreviewBlockCard(
                tab = tab,
                isActive = isActive,
                onSelect = onSelect,
                onClose = onClose
            )
        }
    }
}

/**
 * Bloco retangular individual de aba com visualização rica e suporte a abas privadas/anônimas.
 */
@Composable
fun TabPreviewBlockCard(
    tab: BrowserTab,
    isActive: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val isPrivate = tab.isPrivate

    val displayTitle = remember(tab.title, tab.url, isPrivate) {
        when {
            tab.title.isNotBlank() -> tab.title
            tab.url.isNotBlank() -> {
                tab.url.removePrefix("https://")
                    .removePrefix("http://")
                    .removePrefix("www.")
                    .substringBefore('/')
            }
            isPrivate -> "Guia Anônima"
            else -> "Nova guia"
        }
    }

    val borderColor = when {
        isActive && isPrivate -> PrivatePurpleLight
        isActive -> MaterialTheme.colorScheme.primary
        isPrivate -> PrivatePurple.copy(alpha = 0.45f)
        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    }
    val borderWidth = if (isActive) 2.5.dp else 1.dp
    val elevation = if (isActive) 6.dp else 1.5.dp

    Card(
        modifier = Modifier
            .fillMaxSize()
            .clickable(onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onSelect()
            })
            .testTag("tab_card_${tab.id}"),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(borderWidth, borderColor),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isPrivate && isActive -> Color(0xFF262238)
                isPrivate -> Color(0xFF1B1828)
                isActive -> MaterialTheme.colorScheme.surfaceContainerHigh
                else -> MaterialTheme.colorScheme.surfaceContainer
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Cabeçalho da aba (Ícone / Título / Fechar)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .background(
                        when {
                            isPrivate && isActive -> PrivatePurple.copy(alpha = 0.3f)
                            isPrivate -> Color(0xFF141220)
                            isActive -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        }
                    )
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Ícone de Segurança / Anônimo / Globo
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                isPrivate -> PrivatePurple.copy(alpha = 0.25f)
                                isActive -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                else -> MaterialTheme.colorScheme.surface
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when {
                            isPrivate -> Icons.Outlined.Security
                            tab.url.startsWith("https://") -> Icons.Default.Lock
                            tab.url.isBlank() || tab.url == "about:blank" -> Icons.Default.Home
                            else -> Icons.Default.Language
                        },
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = when {
                            isPrivate -> PrivatePurpleLight
                            isActive -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Título da aba
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = when {
                        isPrivate -> Color.White
                        isActive -> MaterialTheme.colorScheme.onSurface
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.weight(1f)
                )

                // Botão de fechar aba
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onClose()
                    },
                    modifier = Modifier
                        .size(32.dp)
                        .testTag("close_tab_button_${tab.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Fechar aba $displayTitle",
                        modifier = Modifier.size(16.dp),
                        tint = if (isPrivate) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(
                thickness = 0.75.dp,
                color = when {
                    isPrivate -> PrivatePurple.copy(alpha = 0.35f)
                    isActive -> MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                    else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                }
            )

            // Corpo com Pré-visualização da página
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(
                        if (isPrivate) Color(0xFF12111D) else MaterialTheme.colorScheme.surface
                    )
            ) {
                if (tab.thumbnail != null) {
                    Image(
                        bitmap = tab.thumbnail.asImageBitmap(),
                        contentDescription = "Pré-visualização da aba $displayTitle",
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.TopCenter,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isPrivate) Color(0xFF221F33) else MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Icon(
                                    if (isPrivate) Icons.Outlined.Security else Icons.Default.Language,
                                    contentDescription = null,
                                    modifier = Modifier.size(10.dp),
                                    tint = if (isPrivate) PrivatePurpleLight else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (tab.url.isBlank()) "about:blank" else tab.url.removePrefix("https://").removePrefix("http://"),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 9.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (isPrivate) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }

                        Icon(
                            imageVector = if (isPrivate) Icons.Outlined.Shield else if (tab.url.isBlank()) Icons.Default.Public else Icons.Default.Web,
                            contentDescription = null,
                            modifier = Modifier.size(36.dp),
                            tint = if (isPrivate) PrivatePurple.copy(alpha = 0.5f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = displayTitle,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            color = if (isPrivate) Color.White.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Tag "ATIVA"
                if (isActive) {
                    Surface(
                        shape = RoundedCornerShape(topStart = 8.dp),
                        color = if (isPrivate) PrivatePurple else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.BottomEnd)
                    ) {
                        Text(
                            text = "ATIVA",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                // Indicador de aba anônima no canto superior esquerdo se tiver thumbnail
                if (isPrivate && tab.thumbnail != null) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color.Black.copy(alpha = 0.75f),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Security,
                                contentDescription = null,
                                modifier = Modifier.size(10.dp),
                                tint = PrivatePurpleLight
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "Anônima",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.sp,
                                color = Color.White
                            )
                        }
                    }
                }

                // Indicador de suspensão de memória
                if (tab.isFrozen) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                        modifier = Modifier
                            .align(if (isPrivate) Alignment.BottomStart else Alignment.TopStart)
                            .padding(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                Icons.Default.AcUnit,
                                contentDescription = null,
                                modifier = Modifier.size(10.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "Suspensa",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Indicador de reprodução de áudio/mídia ativa
                if (tab.hasActiveMedia) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(6.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = "Reproduzindo áudio",
                            modifier = Modifier
                                .padding(4.dp)
                                .size(12.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
        }
    }
}
