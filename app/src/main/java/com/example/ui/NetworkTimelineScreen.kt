package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.NetworkEvent
import com.example.model.NetworkEventType
import java.text.SimpleDateFormat
import java.util.*

private val CyanBrand = Color(0xFF00BCD4)
private val DarkBg = Color(0xFF070A0F)
private val CardBg = Color(0xFF0E131C)
private val CardBorder = Color(0xFF212836)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkTimelineScreen(
    networkEvents: List<NetworkEvent>,
    onClose: () -> Unit,
    onClearEvents: () -> Unit,
    onDownloadDirect: (String) -> Unit,
    onOpenUrlInNewTab: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val clipboardManager = remember { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }

    var selectedFilter by remember { mutableStateOf(NetworkEventType.ALL) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    var expandedEventId by remember { mutableStateOf<String?>(null) }
    var snackbarMessage by remember { mutableStateOf<String?>(null) }

    BackHandler {
        onClose()
    }

    // Filter events
    val filteredEvents = remember(networkEvents, selectedFilter, searchQuery) {
        networkEvents.filter { event ->
            val matchesFilter = when (selectedFilter) {
                NetworkEventType.ALL -> true
                else -> event.type == selectedFilter
            }
            val matchesSearch = if (searchQuery.isBlank()) {
                true
            } else {
                event.url.contains(searchQuery, ignoreCase = true) ||
                event.method.contains(searchQuery, ignoreCase = true) ||
                event.type.name.contains(searchQuery, ignoreCase = true)
            }
            matchesFilter && matchesSearch
        }
    }

    val listState = rememberLazyListState()

    val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBg),
        containerColor = DarkBg,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0B0F17))
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            ) {
                // Top Action Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onClose()
                        },
                        modifier = Modifier.testTag("network_timeline_back")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Voltar",
                            tint = Color.White
                        )
                    }

                    if (!isSearchActive) {
                        Text(
                            text = "Network Timeline",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 4.dp)
                        )

                        Text(
                            text = "${filteredEvents.size} events",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = CyanBrand,
                            modifier = Modifier.padding(end = 4.dp)
                        )

                        IconButton(
                            onClick = { isSearchActive = true }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Pesquisar",
                                tint = Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onClearEvents()
                                snackbarMessage = "Histórico de rede limpo"
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "Limpar requisições",
                                tint = Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    } else {
                        TextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Filtrar URL ou domínio...", fontSize = 14.sp, color = Color.Gray) },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedIndicatorColor = CyanBrand,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        if (searchQuery.isNotEmpty()) {
                                            searchQuery = ""
                                        } else {
                                            isSearchActive = false
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Fechar busca",
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 4.dp)
                        )
                    }
                }

                // Filter Chips Horizontal Scroll Row
                val filterTypes = listOf(
                    NetworkEventType.ALL,
                    NetworkEventType.MANIFEST,
                    NetworkEventType.VIDEO,
                    NetworkEventType.AUDIO,
                    NetworkEventType.SEGMENT,
                    NetworkEventType.XHR,
                    NetworkEventType.IMAGE,
                    NetworkEventType.SCRIPT,
                    NetworkEventType.OTHER
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    filterTypes.forEach { type ->
                        val isSelected = selectedFilter == type
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) CyanBrand else Color(0xFF141A24),
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) CyanBrand else Color(0xFF263042)
                            ),
                            modifier = Modifier
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    selectedFilter = type
                                }
                        ) {
                            Text(
                                text = type.label,
                                fontSize = 11.5.sp,
                                fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                                color = if (isSelected) Color.Black else Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                HorizontalDivider(
                    thickness = 1.dp,
                    color = Color(0xFF1A2230)
                )
            }
        },
        snackbarHost = {
            snackbarMessage?.let { msg ->
                Snackbar(
                    containerColor = Color(0xFF1F2937),
                    contentColor = Color.White,
                    modifier = Modifier.padding(16.dp),
                    action = {
                        TextButton(onClick = { snackbarMessage = null }) {
                            Text("OK", color = CyanBrand)
                        }
                    }
                ) {
                    Text(msg)
                }
            }
        }
    ) { innerPadding ->
        if (filteredEvents.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Sensors,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.3f),
                        modifier = Modifier.size(56.dp)
                    )
                    Text(
                        text = if (searchQuery.isNotEmpty()) "Nenhuma requisição corresponde ao filtro" else "Aguardando tráfego de rede...",
                        fontSize = 15.sp,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                    Text(
                        text = "Navegue ou reproduza vídeos em uma aba para capturar requisições em tempo real.",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.4f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = filteredEvents,
                    key = { it.id }
                ) { event ->
                    val isExpanded = expandedEventId == event.id

                    val typeBadgeColor = when (event.type) {
                        NetworkEventType.MANIFEST -> Color(0xFF00E5FF)
                        NetworkEventType.VIDEO -> Color(0xFFFFB300)
                        NetworkEventType.AUDIO -> Color(0xFFAB47BC)
                        NetworkEventType.SEGMENT -> Color(0xFF66BB6A)
                        NetworkEventType.XHR -> Color(0xFF42A5F5)
                        NetworkEventType.IMAGE -> Color(0xFFFF7043)
                        NetworkEventType.SCRIPT -> Color(0xFFFFD54F)
                        else -> Color(0xFF90A4AE)
                    }

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                expandedEventId = if (isExpanded) null else event.id
                            },
                        shape = RoundedCornerShape(10.dp),
                        color = CardBg,
                        border = BorderStroke(1.dp, if (isExpanded) CyanBrand.copy(alpha = 0.7f) else CardBorder)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // Header Row: Method + Type Badge + Time
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Method Badge [ GET / POST ]
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = Color(0xFF1B2332),
                                    border = BorderStroke(0.5.dp, Color(0xFF324158))
                                ) {
                                    Text(
                                        text = event.method,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(6.dp))

                                // Type Badge [ OTHER / MANIFEST / VIDEO / ... ]
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = typeBadgeColor.copy(alpha = 0.15f),
                                    border = BorderStroke(0.5.dp, typeBadgeColor.copy(alpha = 0.5f))
                                ) {
                                    Text(
                                        text = event.type.name,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = typeBadgeColor,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }

                                if (event.isBlocked) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = Color(0xFFD32F2F).copy(alpha = 0.2f),
                                        border = BorderStroke(0.5.dp, Color(0xFFD32F2F))
                                    ) {
                                        Text(
                                            text = "BLOCKED",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Black,
                                            color = Color(0xFFFF5252),
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.weight(1f))

                                // Timestamp
                                Text(
                                    text = timeFormat.format(Date(event.timestamp)),
                                    fontSize = 11.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color.White.copy(alpha = 0.45f)
                                )
                            }

                            // URL display
                            SelectionContainer {
                                Text(
                                    text = event.url,
                                    fontSize = 12.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color.White.copy(alpha = 0.9f),
                                    maxLines = if (isExpanded) 10 else 2,
                                    overflow = TextOverflow.Ellipsis,
                                    lineHeight = 16.sp
                                )
                            }

                            // Expanded details and action bar
                            AnimatedVisibility(visible = isExpanded) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    HorizontalDivider(
                                        thickness = 0.8.dp,
                                        color = Color(0xFF1E2636)
                                    )

                                    // Action buttons row
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = {
                                                clipboardManager.setPrimaryClip(ClipData.newPlainText("URL", event.url))
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                snackbarMessage = "URL copiada para a área de transferência"
                                            },
                                            shape = RoundedCornerShape(6.dp),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = CyanBrand),
                                            border = BorderStroke(1.dp, CyanBrand.copy(alpha = 0.5f)),
                                            modifier = Modifier.weight(1f),
                                            contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp)
                                        ) {
                                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Copiar URL", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }

                                        if (event.type == NetworkEventType.MANIFEST || event.type == NetworkEventType.VIDEO || event.type == NetworkEventType.AUDIO) {
                                            Button(
                                                onClick = {
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    onDownloadDirect(event.url)
                                                    snackbarMessage = "Iniciando download da mídia..."
                                                },
                                                shape = RoundedCornerShape(6.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = CyanBrand, contentColor = Color.Black),
                                                modifier = Modifier.weight(1f),
                                                contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp)
                                            ) {
                                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Baixar", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        } else {
                                            OutlinedButton(
                                                onClick = {
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    onOpenUrlInNewTab(event.url)
                                                    onClose()
                                                },
                                                shape = RoundedCornerShape(6.dp),
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                                border = BorderStroke(1.dp, Color(0xFF324158)),
                                                modifier = Modifier.weight(1f),
                                                contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp)
                                            ) {
                                                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Abrir Aba", fontSize = 11.sp)
                                            }
                                        }
                                    }

                                    // Headers if available
                                    if (event.headers.isNotEmpty()) {
                                        Text(
                                            text = "Request Headers:",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White.copy(alpha = 0.6f)
                                        )
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = Color(0xFF070B11),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(8.dp),
                                                verticalArrangement = Arrangement.spacedBy(2.dp)
                                            ) {
                                                event.headers.forEach { (k, v) ->
                                                    Text(
                                                        text = "$k: $v",
                                                        fontSize = 10.5.sp,
                                                        fontFamily = FontFamily.Monospace,
                                                        color = Color.White.copy(alpha = 0.7f)
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
}
