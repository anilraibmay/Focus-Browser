package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.ImageLoader
import coil.compose.AsyncImage
import com.example.extractor.ExtractedVideo
import com.example.extractor.VideoType

private val RedBrand = Color(0xFFE53935)
private val OrangeAudio = Color(0xFFFB8C00)
private val BlueHD = Color(0xFF0288D1)
private val SlateSD = Color(0xFF546E7A)

private enum class MediaFilterTier(val label: String) {
    ALL("Todas as Qualidades"),
    FHD_PLUS("1080p+ Full HD"),
    HD("720p HD"),
    SD("480p/SD Econômico"),
    AUDIO("Somente Áudio")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtractedVideosScreen(
    extractedVideos: List<ExtractedVideo>,
    mediaGroups: List<com.example.model.ExtractedMediaGroup> = emptyList(),
    imageLoader: ImageLoader,
    onClose: () -> Unit,
    onDownload: (List<ExtractedVideo>) -> Unit,
    onCopyLinks: (List<ExtractedVideo>) -> Unit,
    onRescan: () -> Unit = {},
    onPreviewVideo: (ExtractedVideo) -> Unit = {}
) {
    val haptic = LocalHapticFeedback.current
    // Configure edge-to-edge transparent system bars for dialog window across Android versions
    val view = LocalView.current
    val isDark = isSystemInDarkTheme()
    DisposableEffect(view) {
        val dialogWindow = (view.parent as? DialogWindowProvider)?.window
        if (dialogWindow != null) {
            WindowCompat.setDecorFitsSystemWindows(dialogWindow, false)
            dialogWindow.statusBarColor = android.graphics.Color.TRANSPARENT
            dialogWindow.navigationBarColor = android.graphics.Color.TRANSPARENT
            val controller = WindowInsetsControllerCompat(dialogWindow, view)
            controller.isAppearanceLightNavigationBars = !isDark
            controller.isAppearanceLightStatusBars = !isDark
        }
        onDispose {}
    }

    // Para cada grupo de mídia (vídeo distinto), mapeia groupId -> selectedStreamOptionId
    var groupSelections by remember(mediaGroups) {
        mutableStateOf(
            mediaGroups.associate { group ->
                group.id to (group.selectedStream?.id ?: group.streams.firstOrNull()?.id.orEmpty())
            }
        )
    }

    // Grupos ativos selecionados para download (por padrão nenhum item vem marcado automaticamente)
    var selectedGroupIds by remember(mediaGroups) {
        mutableStateOf(emptySet<String>())
    }

    var selectedVideoUrls by remember { mutableStateOf(emptySet<String>()) }
    var selectedFilterTier by remember { mutableStateOf(MediaFilterTier.ALL) }

    val hasGroups = mediaGroups.isNotEmpty()

    // Total e contagem de itens selecionados
    val totalCount = if (hasGroups) mediaGroups.size else extractedVideos.size
    val selectedCount = if (hasGroups) selectedGroupIds.size else selectedVideoUrls.size
    val isAllSelected = totalCount > 0 && selectedCount == totalCount
    val isPartiallySelected = selectedCount > 0 && !isAllSelected

    val selectAllAction: () -> Unit = {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        if (hasGroups) {
            selectedGroupIds = mediaGroups.map { it.id }.toSet()
        } else {
            selectedVideoUrls = extractedVideos.map { it.url }.toSet()
        }
    }

    val deselectAllAction: () -> Unit = {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        if (hasGroups) {
            selectedGroupIds = emptySet()
        } else {
            selectedVideoUrls = emptySet()
        }
    }

    val toggleSelectAllAction: () -> Unit = {
        if (isAllSelected) {
            deselectAllAction()
        } else {
            selectAllAction()
        }
    }

    // Lista final de ExtractedVideo para download
    val finalSelectedVideos: List<ExtractedVideo> = if (hasGroups) {
        mediaGroups
            .filter { selectedGroupIds.contains(it.id) }
            .mapNotNull { group ->
                val streamId = groupSelections[group.id]
                val option = group.streams.firstOrNull { it.id == streamId } ?: group.selectedStream
                option?.rawVideo
            }
    } else {
        extractedVideos.filter { selectedVideoUrls.contains(it.url) }
    }

    // Separar em Áudios e Vídeos (fallback)
    val audioStreams = remember(extractedVideos) {
        extractedVideos.filter { it.type == VideoType.AUDIO }
    }
    val videoStreams = remember(extractedVideos) {
        extractedVideos.filter { it.type != VideoType.AUDIO }
    }

    val primaryVideo = remember(extractedVideos, mediaGroups) {
        mediaGroups.firstOrNull()?.let { g ->
            ExtractedVideo(
                url = g.selectedStream?.rawVideo?.url ?: "",
                headers = emptyMap(),
                title = g.title,
                type = VideoType.DIRECT,
                thumbnailUrl = g.thumbnailUrl,
                duration = g.durationMs,
                formattedDuration = g.formattedDuration
            )
        } ?: extractedVideos.firstOrNull { !it.thumbnailUrl.isNullOrBlank() }
        ?: extractedVideos.firstOrNull()
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isVeryCompactWidth = maxWidth < 360.dp
        val isTabletOrExpanded = maxWidth >= 600.dp
        val isLandscapeCompactHeight = maxHeight < 500.dp && maxWidth > maxHeight
        val horizontalContentPadding = if (isVeryCompactWidth) 12.dp else 16.dp

        // Insets da barra de navegação do Android
        val navBarBottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val safeBottomInset = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
        val effectiveBottomInset = maxOf(navBarBottomInset, safeBottomInset)
        val safeBottomPadding = if (effectiveBottomInset > 0.dp) effectiveBottomInset + 6.dp else 16.dp

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
            topBar = {
                TopAppBar(
                    windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .background(RedBrand, RoundedCornerShape(8.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ArrowDownward,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = "Mídias Extraídas",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = if (isVeryCompactWidth) 16.sp else 18.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (hasGroups) {
                                        if (mediaGroups.size == 1) "1 vídeo com resoluções organizadas" else "${mediaGroups.size} vídeos com resoluções organizadas"
                                    } else if (extractedVideos.isNotEmpty()) {
                                        if (extractedVideos.size == 1) "1 formato detectado" else "${extractedVideos.size} formatos detectados"
                                    } else {
                                        "Aguardando fluxos de mídia..."
                                    },
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    actions = {
                        if (totalCount > 0) {
                            IconButton(
                                onClick = toggleSelectAllAction,
                                modifier = Modifier.testTag("top_toggle_select_all_button")
                            ) {
                                Icon(
                                    imageVector = if (isAllSelected) Icons.Default.Deselect else Icons.Default.SelectAll,
                                    contentDescription = if (isAllSelected) "Desmarcar todos" else "Marcar todos"
                                )
                            }
                        }
                        IconButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onRescan()
                            },
                            modifier = Modifier.testTag("rescan_media_button")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Reanalisar mídias")
                        }
                        IconButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onClose()
                            }
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Fechar")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            },
            bottomBar = {
                if (hasGroups || extractedVideos.isNotEmpty()) {
                    Surface(
                        shadowElevation = 16.dp,
                        tonalElevation = 6.dp,
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    top = if (isLandscapeCompactHeight) 8.dp else 10.dp,
                                    bottom = safeBottomPadding
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .widthIn(max = 720.dp)
                                    .fillMaxWidth()
                                    .padding(horizontal = horizontalContentPadding)
                            ) {
                                if (isLandscapeCompactHeight) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (selectedCount > 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                            modifier = Modifier.defaultMinSize(minWidth = 28.dp, minHeight = 28.dp)
                                        ) {
                                            Box(
                                                contentAlignment = Alignment.Center,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            ) {
                                                Text(
                                                    text = "$selectedCount/$totalCount",
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (selectedCount > 0) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }

                                        FilledTonalButton(
                                            onClick = toggleSelectAllAction,
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier
                                                .height(42.dp)
                                                .testTag("btn_toggle_select_all")
                                        ) {
                                            Icon(
                                                imageVector = if (isAllSelected) Icons.Default.Deselect else Icons.Default.SelectAll,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                text = if (isAllSelected) "Desmarcar todos" else "Marcar todos",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }

                                        if (isPartiallySelected) {
                                            TextButton(
                                                onClick = deselectAllAction,
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                                modifier = Modifier.height(42.dp)
                                            ) {
                                                Text("Limpar", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                                            }
                                        }

                                        Spacer(modifier = Modifier.weight(1f))

                                        OutlinedButton(
                                            onClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                onCopyLinks(finalSelectedVideos)
                                            },
                                            enabled = finalSelectedVideos.isNotEmpty(),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier
                                                .height(42.dp)
                                                .testTag("btn_copy_links")
                                        ) {
                                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                text = if (finalSelectedVideos.size > 1) "Copiar links" else "Copiar link",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }

                                        Button(
                                            onClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                onDownload(finalSelectedVideos)
                                            },
                                            enabled = finalSelectedVideos.isNotEmpty(),
                                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = RedBrand,
                                                contentColor = Color.White,
                                                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                            ),
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier
                                                .height(42.dp)
                                                .testTag("btn_download_selected")
                                        ) {
                                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(17.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                text = if (finalSelectedVideos.isEmpty()) "BAIXAR"
                                                else "BAIXAR (${finalSelectedVideos.size})",
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                } else {
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                modifier = Modifier.weight(1f, fill = false)
                                            ) {
                                                Surface(
                                                    shape = RoundedCornerShape(6.dp),
                                                    color = if (selectedCount > 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                                    modifier = Modifier.defaultMinSize(minWidth = 24.dp, minHeight = 22.dp)
                                                ) {
                                                    Box(
                                                        contentAlignment = Alignment.Center,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    ) {
                                                        Text(
                                                            text = "$selectedCount/$totalCount",
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = if (selectedCount > 0) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                }

                                                Text(
                                                    text = if (finalSelectedVideos.isEmpty()) {
                                                        if (isVeryCompactWidth) "Nenhum marcado" else "Nenhuma opção selecionada"
                                                    } else {
                                                        if (finalSelectedVideos.size == 1) "1 item para descarregar" else "${finalSelectedVideos.size} itens para descarregar"
                                                    },
                                                    fontSize = if (isVeryCompactWidth) 11.sp else 12.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }

                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                if (isPartiallySelected && !isVeryCompactWidth) {
                                                    TextButton(
                                                        onClick = deselectAllAction,
                                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                                        modifier = Modifier
                                                            .height(36.dp)
                                                            .testTag("btn_clear_selection")
                                                    ) {
                                                        Text(
                                                            text = "Limpar",
                                                            fontSize = 12.sp,
                                                            fontWeight = FontWeight.SemiBold,
                                                            color = MaterialTheme.colorScheme.error
                                                        )
                                                    }
                                                }

                                                FilledTonalButton(
                                                    onClick = toggleSelectAllAction,
                                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                    shape = RoundedCornerShape(10.dp),
                                                    modifier = Modifier
                                                        .height(36.dp)
                                                        .testTag("btn_toggle_select_all")
                                                ) {
                                                    Icon(
                                                        imageVector = if (isAllSelected) Icons.Default.Deselect else Icons.Default.SelectAll,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(15.dp)
                                                    )
                                                    Spacer(Modifier.width(4.dp))
                                                    Text(
                                                        text = if (isAllSelected) "Desmarcar todos" else "Marcar todos",
                                                        fontSize = if (isVeryCompactWidth) 11.sp else 12.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                }
                                            }
                                        }

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            OutlinedButton(
                                                onClick = {
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    onCopyLinks(finalSelectedVideos)
                                                },
                                                enabled = finalSelectedVideos.isNotEmpty(),
                                                modifier = Modifier
                                                    .weight(if (isTabletOrExpanded) 0.35f else 0.38f)
                                                    .height(48.dp)
                                                    .testTag("btn_copy_links"),
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                                                shape = RoundedCornerShape(12.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.ContentCopy,
                                                    contentDescription = "Copiar Links",
                                                    modifier = Modifier.size(17.dp)
                                                )
                                                Spacer(Modifier.width(6.dp))
                                                Text(
                                                    text = if (isVeryCompactWidth) "Copiar" else if (finalSelectedVideos.size > 1) "Copiar links" else "Copiar link",
                                                    fontSize = if (isVeryCompactWidth) 12.sp else 13.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }

                                            Button(
                                                onClick = {
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    onDownload(finalSelectedVideos)
                                                },
                                                enabled = finalSelectedVideos.isNotEmpty(),
                                                modifier = Modifier
                                                    .weight(if (isTabletOrExpanded) 0.65f else 0.62f)
                                                    .height(48.dp)
                                                    .testTag("btn_download_selected"),
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = RedBrand,
                                                    contentColor = Color.White,
                                                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                                ),
                                                shape = RoundedCornerShape(12.dp),
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                                elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp, pressedElevation = 4.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Download,
                                                    contentDescription = "Baixar",
                                                    modifier = Modifier.size(20.dp)
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                    text = if (finalSelectedVideos.isEmpty()) "BAIXAR SELECIONADOS"
                                                    else "BAIXAR (${finalSelectedVideos.size})",
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
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
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.TopCenter
            ) {
                if (!hasGroups && extractedVideos.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontalContentPadding),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ArrowDownward,
                                    contentDescription = null,
                                    modifier = Modifier.size(36.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                text = "Nenhum vídeo detectado nesta aba",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = "Inicie a reprodução do vídeo do YouTube, Facebook, Instagram ou qualquer site para capturar as resoluções e faixas de áudio.",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Button(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onRescan()
                                },
                                modifier = Modifier.testTag("empty_state_rescan_button"),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Reanalisar Página")
                            }
                        }
                    }
                } else if (hasGroups) {
                    // Visão Agrupada por Vídeo Único com Resoluções e Áudio organizados
                    LazyColumn(
                        contentPadding = PaddingValues(
                            start = horizontalContentPadding,
                            end = horizontalContentPadding,
                            top = 10.dp,
                            bottom = 20.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .widthIn(max = 720.dp)
                    ) {
                        items(mediaGroups, key = { it.id }) { group ->
                            val isGroupSelected = selectedGroupIds.contains(group.id)
                            val currentStreamId = groupSelections[group.id]

                            MediaGroupCard(
                                group = group,
                                isSelected = isGroupSelected,
                                selectedStreamOptionId = currentStreamId,
                                imageLoader = imageLoader,
                                onToggleGroupSelection = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    selectedGroupIds = if (isGroupSelected) selectedGroupIds - group.id
                                    else selectedGroupIds + group.id
                                },
                                onSelectStream = { optionId ->
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    groupSelections = groupSelections + (group.id to optionId)
                                    if (!isGroupSelected) {
                                        selectedGroupIds = selectedGroupIds + group.id
                                    }
                                },
                                onPreviewVideo = onPreviewVideo
                            )
                        }
                    }
                } else {
                    // Fallback para lista flat se mediaGroups não estiver pronto
                    LazyColumn(
                        contentPadding = PaddingValues(
                            start = horizontalContentPadding,
                            end = horizontalContentPadding,
                            top = 10.dp,
                            bottom = 20.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .widthIn(max = 720.dp)
                    ) {
                        if (primaryVideo != null) {
                            item(key = "video_header") {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    ),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(10.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .width(110.dp)
                                                .height(68.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color.Black)
                                                .clickable { onPreviewVideo(primaryVideo) },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            val thumbModel = primaryVideo.thumbnailUrl ?: primaryVideo.url
                                            AsyncImage(
                                                model = thumbModel,
                                                imageLoader = imageLoader,
                                                contentDescription = "Miniatura do vídeo",
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                            Surface(
                                                color = Color.Black.copy(alpha = 0.55f),
                                                shape = CircleShape,
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Icon(
                                                        imageVector = Icons.Default.PlayArrow,
                                                        contentDescription = "Pré-visualizar vídeo",
                                                        tint = Color.White,
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                }
                                            }
                                            if (!primaryVideo.formattedDuration.isNullOrBlank()) {
                                                Surface(
                                                    color = Color.Black.copy(alpha = 0.75f),
                                                    shape = RoundedCornerShape(4.dp),
                                                    modifier = Modifier
                                                        .align(Alignment.BottomEnd)
                                                        .padding(3.dp)
                                                ) {
                                                    Text(
                                                        text = primaryVideo.formattedDuration,
                                                        color = Color.White,
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                    )
                                                }
                                            }
                                        }

                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text(
                                                text = primaryVideo.title,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "Selecione o formato para download:",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (audioStreams.isNotEmpty()) {
                            item(key = "section_audio_header") {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(top = 4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Audiotrack,
                                        contentDescription = null,
                                        tint = OrangeAudio,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = "MÚSICA / APENAS ÁUDIO",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        letterSpacing = 0.5.sp,
                                        color = OrangeAudio
                                    )
                                }
                            }

                            items(audioStreams, key = { it.url }) { audio ->
                                val isSelected = selectedVideoUrls.contains(audio.url)
                                QualityOptionRow(
                                    video = audio,
                                    isSelected = isSelected,
                                    isAudio = true,
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        selectedVideoUrls = if (isSelected) selectedVideoUrls - audio.url
                                        else selectedVideoUrls + audio.url
                                    },
                                    onPreview = { onPreviewVideo(audio) }
                                )
                            }
                        }

                        if (videoStreams.isNotEmpty()) {
                            item(key = "section_video_header") {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(top = 8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Movie,
                                        contentDescription = null,
                                        tint = RedBrand,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = "VÍDEO",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        letterSpacing = 0.5.sp,
                                        color = RedBrand
                                    )
                                }
                            }

                            items(videoStreams, key = { it.url }) { video ->
                                val isSelected = selectedVideoUrls.contains(video.url)
                                QualityOptionRow(
                                    video = video,
                                    isSelected = isSelected,
                                    isAudio = false,
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        selectedVideoUrls = if (isSelected) selectedVideoUrls - video.url
                                        else selectedVideoUrls + video.url
                                    },
                                    onPreview = { onPreviewVideo(video) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Card que agrupa um vídeo específico e exibe de forma categorizada e interativa suas resoluções
 * (Full HD, HD, Econômico e Áudio) com badges e tamanhos nítidos.
 */
@Composable
private fun MediaGroupCard(
    group: com.example.model.ExtractedMediaGroup,
    isSelected: Boolean,
    selectedStreamOptionId: String?,
    imageLoader: ImageLoader,
    onToggleGroupSelection: () -> Unit,
    onSelectStream: (String) -> Unit,
    onPreviewVideo: (ExtractedVideo) -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
        ),
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) RedBrand.copy(alpha = 0.6f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Header do Vídeo: Checkbox + Miniatura + Título + Duração
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleGroupSelection() },
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleGroupSelection() },
                    colors = CheckboxDefaults.colors(
                        checkedColor = RedBrand
                    )
                )

                // Miniatura do Vídeo com botão de pré-visualização nativa
                Box(
                    modifier = Modifier
                        .width(96.dp)
                        .height(60.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black)
                        .clickable {
                            val streamToPlay = group.selectedStream ?: group.streams.firstOrNull()
                            if (streamToPlay != null) {
                                onPreviewVideo(streamToPlay.rawVideo)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    val thumbModel = group.thumbnailUrl ?: group.streams.firstOrNull()?.rawVideo?.url
                    AsyncImage(
                        model = thumbModel,
                        imageLoader = imageLoader,
                        contentDescription = "Miniatura de ${group.title}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Surface(
                        color = Color.Black.copy(alpha = 0.5f),
                        shape = CircleShape,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Pré-visualizar vídeo",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    if (!group.formattedDuration.isNullOrBlank()) {
                        Surface(
                            color = Color.Black.copy(alpha = 0.8f),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(3.dp)
                        ) {
                            Text(
                                text = group.formattedDuration,
                                color = Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }

                // Título e Resumo de Resoluções
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = group.title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${group.videoStreams.size} " + (if (group.videoStreams.size == 1) "resolução disponível" else "resoluções disponíveis") +
                                if (group.audioStreams.isNotEmpty()) " • Áudio disponível" else "",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            // Seletor Categorizado de Resoluções / Áudio
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "SELECIONE A QUALIDADE DO ARQUIVO:",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = "${group.streams.size} " + (if (group.streams.size == 1) "opção" else "opções"),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            // Lista de Opções de Stream Categorizadas
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                group.streams.forEach { stream ->
                    val isCurrentSelected = stream.id == selectedStreamOptionId
                    val isAudio = stream.isAudioOnly
                    val isFHD = !isAudio && (stream.qualityLabel.contains("1080") || stream.qualityLabel.contains("4K") || stream.qualityLabel.contains("1440"))
                    val isHD = !isAudio && !isFHD && stream.qualityLabel.contains("720")

                    val (tierColor, tierBadge) = when {
                        isAudio -> OrangeAudio to "ÁUDIO"
                        isFHD -> RedBrand to "FULL HD"
                        isHD -> BlueHD to "HD 720p"
                        else -> SlateSD to "ECONÔMICO"
                    }

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onSelectStream(stream.id) },
                        color = if (isCurrentSelected) {
                            tierColor.copy(alpha = 0.14f)
                        } else {
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)
                        },
                        border = BorderStroke(
                            width = if (isCurrentSelected) 1.5.dp else 1.dp,
                            color = if (isCurrentSelected) {
                                tierColor
                            } else {
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                            }
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                RadioButton(
                                    selected = isCurrentSelected,
                                    onClick = { onSelectStream(stream.id) },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = tierColor
                                    ),
                                    modifier = Modifier.size(20.dp)
                                )

                                Surface(
                                    color = tierColor,
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = stream.formatBadge.ifBlank { tierBadge },
                                        color = Color.White,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                    )
                                }

                                Text(
                                    text = stream.qualityLabel,
                                    fontSize = 13.sp,
                                    fontWeight = if (isCurrentSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.End,
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = stream.formattedSize,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isCurrentSelected) tierColor else MaterialTheme.colorScheme.onSurface
                                    )
                                    if (!stream.estimationConfidence.isNullOrBlank() && stream.formattedSize != "Tamanho sob consulta") {
                                        val (confColor, confBg) = when (stream.estimationConfidence) {
                                            "Exacto" -> Color(0xFF81C784) to Color(0xFF1B5E20).copy(alpha = 0.35f)
                                            "Alta" -> Color(0xFF64B5F6) to Color(0xFF0D47A1).copy(alpha = 0.35f)
                                            "Média" -> Color(0xFFFFB74D) to Color(0xFFE65100).copy(alpha = 0.35f)
                                            else -> Color(0xFFBDBDBD) to Color(0xFF424242).copy(alpha = 0.35f)
                                        }
                                        Surface(
                                            shape = RoundedCornerShape(3.dp),
                                            color = confBg
                                        ) {
                                            Text(
                                                text = stream.estimationConfidence,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = confColor,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                }

                                IconButton(
                                    onClick = { onPreviewVideo(stream.rawVideo) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = "Pré-visualizar ${stream.qualityLabel}",
                                        tint = tierColor,
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

@Composable
private fun QualityOptionRow(
    video: ExtractedVideo,
    isSelected: Boolean,
    isAudio: Boolean,
    onClick: () -> Unit,
    onPreview: (() -> Unit)? = null
) {
    val isFHD = !isAudio && (video.quality?.contains("1080") == true || video.quality?.contains("4K") == true)
    val isHD = !isAudio && !isFHD && video.quality?.contains("720") == true

    val activeColor = when {
        isAudio -> OrangeAudio
        isFHD -> RedBrand
        isHD -> BlueHD
        else -> SlateSD
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                activeColor.copy(alpha = 0.15f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            }
        ),
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) activeColor else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                RadioButton(
                    selected = isSelected,
                    onClick = onClick,
                    colors = RadioButtonDefaults.colors(
                        selectedColor = activeColor
                    )
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    val qualityLabel = when {
                        !video.quality.isNullOrBlank() -> video.quality
                        isAudio -> if (video.url.contains(".m4a", ignoreCase = true)) "Áudio M4A" else if (video.url.contains(".mp3", ignoreCase = true)) "Áudio MP3" else "Faixa de Áudio"
                        video.type == VideoType.ABYS -> "Stream ABYS (.fd / MP4)"
                        video.type == VideoType.HLS -> "Stream Adaptativo HLS (.m3u8)"
                        video.type == VideoType.DASH -> "Stream Adaptativo DASH (.mpd)"
                        video.url.contains(".webm", ignoreCase = true) -> "Vídeo WebM"
                        else -> "Vídeo MP4"
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = qualityLabel,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )

                        Surface(
                            color = activeColor.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.wrapContentSize()
                        ) {
                            val badgeText = when {
                                isAudio -> if (video.url.contains(".m4a", ignoreCase = true) || video.quality?.contains("M4A") == true) "M4A" else if (video.url.contains(".mp3", ignoreCase = true)) "MP3" else "ÁUDIO"
                                video.type == VideoType.ABYS -> "ABYS"
                                video.type == VideoType.HLS -> "HLS"
                                video.type == VideoType.DASH -> "DASH"
                                isFHD -> "FULL HD"
                                isHD -> "HD 720p"
                                else -> "MP4"
                            }
                            Text(
                                text = badgeText,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1,
                                softWrap = false,
                                color = activeColor,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    val detailNote = when {
                        isAudio -> "Faixa de áudio extraída sem perda"
                        video.type == VideoType.ABYS -> "Fluxo ABYS (Descriptografia e Montagem Automática)"
                        isFHD -> "Alta Definição (Full HD/4K) - Imagem ultra nítida"
                        isHD -> "Alta Definição (HD 720p) - Equilíbrio perfeito"
                        !video.quality.isNullOrBlank() -> "Resolução ${video.quality} - Download leve"
                        video.type == VideoType.HLS -> "Fluxo de transmissão ao vivo/VOD"
                        else -> "Fluxo direto detectado"
                    }
                    Text(
                        text = detailNote,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val displaySize = video.formattedSize ?: "Estimando..."
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Surface(
                        color = if (isSelected) {
                            activeColor.copy(alpha = 0.22f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .wrapContentSize()
                    ) {
                        Text(
                            text = displaySize,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                            color = if (isSelected) activeColor else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                        )
                    }

                    if (!video.estimationConfidence.isNullOrBlank()) {
                        val confColor = when (video.estimationConfidence) {
                            "Exacto" -> Color(0xFF2E7D32)
                            "Alta" -> Color(0xFF1976D2)
                            "Média" -> Color(0xFFF57C00)
                            else -> Color(0xFF757575)
                        }
                        Text(
                            text = video.estimationConfidence,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = confColor
                        )
                    }
                }

                if (onPreview != null) {
                    IconButton(
                        onClick = onPreview,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Pré-visualizar",
                            tint = activeColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}
