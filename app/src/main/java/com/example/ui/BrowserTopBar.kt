package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.BrowserPreferences
import com.example.model.BrowserTab
import com.example.model.FocusMode

private val CyanTabBadge = Color(0xFF00BCD4)
private val GreenSecure = Color(0xFF00E676)
private val DarkPillBg = Color(0xFF161B22)

private fun formatDisplayUrl(url: String): String {
    if (url.isBlank() || url == "about:blank") return ""
    var clean = url.trim()
    if (clean.startsWith("https://", ignoreCase = true)) {
        clean = clean.substring(8)
    } else if (clean.startsWith("http://", ignoreCase = true)) {
        clean = clean.substring(7)
    }
    if (clean.startsWith("www.", ignoreCase = true)) {
        clean = clean.substring(4)
    }
    return clean
}

@Composable
fun UnifiedToolbar(
    urlText: String,
    isLoading: Boolean,
    progress: Int,
    preferences: BrowserPreferences,
    tabCount: Int,
    activeTab: BrowserTab,
    splitTab: BrowserTab?,
    extractedVideoCount: Int = 0,
    onOpenExtractedVideos: () -> Unit = {},
    onUrlChange: (String) -> Unit,
    onNavigate: () -> Unit,
    onRefresh: () -> Unit,
    onToggleFocusMode: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onHome: () -> Unit,
    onOpenTabs: () -> Unit,
    onNewPrivateTab: () -> Unit = {},
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenVideoHistory: () -> Unit = {},
    onOpenPasswords: () -> Unit,
    onOpenAutofillDialog: (String, android.webkit.WebView) -> Unit,
    onOpenSavePasswordDialog: (String) -> Unit,
    onOpenSiteInfo: () -> Unit = {},
    onOpenNetworkTimeline: () -> Unit = {},
    onOpenPlayStream: () -> Unit = {},
    onToggleDesktopMode: () -> Unit = {},
    onOpenQrCode: () -> Unit = {},
    onFocusStateChange: ((Boolean) -> Unit)? = null
) {
    val haptic = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    var textFieldValue by remember { mutableStateOf(TextFieldValue(urlText)) }
    var isFocused by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }

    fun clampSelection(value: TextFieldValue): TextFieldValue {
        val maxLen = value.text.length
        val start = value.selection.start.coerceIn(0, maxLen)
        val end = value.selection.end.coerceIn(0, maxLen)
        return if (start != value.selection.start || end != value.selection.end) {
            value.copy(selection = TextRange(start, end))
        } else {
            value
        }
    }

    LaunchedEffect(urlText) {
        if (textFieldValue.text != urlText) {
            val safeLen = urlText.length
            textFieldValue = TextFieldValue(
                text = urlText,
                selection = TextRange(safeLen)
            )
        }
    }

    val (modeLabel, modeColor, modeIcon) = when (preferences.focusMode) {
        FocusMode.NORMAL -> Triple("Navegação Padrão", MaterialTheme.colorScheme.onSurfaceVariant, Icons.Default.FilterNone)
        FocusMode.BACKGROUND -> Triple("Modo Multimédia", Color(0xFF00B0FF), Icons.Default.PlayCircleOutline)
        FocusMode.SPLIT_SCREEN -> Triple("Modo Split-Screen", Color(0xFF00E676), Icons.Default.VerticalSplit)
        FocusMode.TOTAL_BLOCK -> Triple("Modo Foco Total", Color(0xFFFF5252), Icons.Default.DoNotDisturbOn)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (activeTab.isPrivate) Color(0xFF14131F) else Color(0xFF0D1117)
            )
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 1. Central Pill-shaped Address Bar with inside Security Lock, Clean URL & Action Buttons
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp)
            ) {
                // Sincronização da cor do cadeado e da borda com a segurança HTTPS/HTTP/Privado
                val lockColor = when {
                    activeTab.isPrivate -> Color(0xFFB388FF)
                    activeTab.url.isBlank() || activeTab.url == "about:blank" -> Color.White.copy(alpha = 0.55f)
                    activeTab.isSecure -> GreenSecure
                    else -> Color(0xFFFF5252)
                }

                val borderColor = when {
                    activeTab.isPrivate -> if (isFocused) Color(0xFFB388FF) else Color(0xFF7C4DFF).copy(alpha = 0.45f)
                    activeTab.url.isBlank() || activeTab.url == "about:blank" -> if (isFocused) CyanTabBadge else Color(0xFF30363D).copy(alpha = 0.7f)
                    activeTab.isSecure -> if (isFocused) GreenSecure else GreenSecure.copy(alpha = 0.45f)
                    else -> if (isFocused) Color(0xFFFF5252) else Color(0xFFFF5252).copy(alpha = 0.55f)
                }

                val borderWidth = if (isFocused) 1.5.dp else 1.dp

                BasicTextField(
                    value = textFieldValue,
                    onValueChange = { newValue ->
                        val safe = clampSelection(newValue)
                        textFieldValue = safe
                        onUrlChange(safe.text)
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRequester(focusRequester)
                        .onFocusChanged { focusState ->
                            if (focusState.isFocused && !isFocused && textFieldValue.text.isNotEmpty()) {
                                val len = textFieldValue.text.length
                                textFieldValue = textFieldValue.copy(
                                    selection = TextRange(0, len)
                                )
                            } else if (!focusState.isFocused && isFocused) {
                                val len = textFieldValue.text.length
                                textFieldValue = textFieldValue.copy(
                                    selection = TextRange(len)
                                )
                            }
                            isFocused = focusState.isFocused
                            onFocusStateChange?.invoke(focusState.isFocused)
                        }
                        .testTag("address_input"),
                    singleLine = true,
                    cursorBrush = SolidColor(if (activeTab.isPrivate) Color(0xFFB388FF) else if (activeTab.isSecure) GreenSecure else CyanTabBadge),
                    textStyle = LocalTextStyle.current.copy(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White
                    ),
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Search,
                        autoCorrectEnabled = false
                    ),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            focusManager.clearFocus()
                            onFocusStateChange?.invoke(false)
                            onNavigate()
                        }
                    ),
                    decorationBox = { innerTextField ->
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            shape = RoundedCornerShape(22.dp),
                            color = if (activeTab.isPrivate) Color(0xFF1E1C2B) else DarkPillBg,
                            border = BorderStroke(borderWidth, borderColor)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(start = 4.dp, end = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Cadeado / Ícone de Segurança Integrado Dentro da Borda
                                IconButton(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onOpenSiteInfo()
                                    },
                                    modifier = Modifier
                                        .size(32.dp)
                                        .testTag("site_security_button")
                                    ) {
                                    Icon(
                                        imageVector = when {
                                            activeTab.isPrivate -> Icons.Default.Security
                                            activeTab.url.isBlank() || activeTab.url == "about:blank" -> Icons.Default.Search
                                            activeTab.isSecure -> Icons.Default.Lock
                                            else -> Icons.Default.LockOpen
                                        },
                                        contentDescription = "Informações de segurança",
                                        tint = lockColor,
                                        modifier = Modifier.size(17.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(3.dp))

                                // Campo da URL com innerTextField sempre composto para evitar race condition no TextLayout
                                Box(
                                    modifier = Modifier.weight(1f),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    if (textFieldValue.text.isEmpty()) {
                                        Text(
                                            text = if (activeTab.isPrivate) "Pesquisar anonimamente..." else "Pesquisar ou introduzir endereço",
                                            fontSize = 13.5.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = Color.White.copy(alpha = 0.45f)
                                        )
                                    } else if (!isFocused) {
                                        val displayUrl = formatDisplayUrl(textFieldValue.text)
                                        Text(
                                            text = displayUrl.ifEmpty { textFieldValue.text },
                                            fontSize = 13.5.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    // Mantém innerTextField sempre no layout para garantir cálculo correto de índices pelo Compose
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .alpha(if (isFocused) 1f else 0f)
                                    ) {
                                        innerTextField()
                                    }
                                }

                                // Botões internos à direita: Limpar quando focado, Atualizar quando não focado
                                if (isFocused && textFieldValue.text.isNotEmpty()) {
                                    if (com.example.util.StreamUrlHelper.isDirectStreamUrl(textFieldValue.text)) {
                                        IconButton(
                                            onClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                onNavigate()
                                            },
                                            modifier = Modifier.size(30.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.PlayCircleFilled,
                                                contentDescription = "Reproduzir Stream no Media3",
                                                tint = Color(0xFF00E676),
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                    IconButton(
                                        onClick = {
                                            textFieldValue = TextFieldValue("")
                                            onUrlChange("")
                                        },
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Limpar",
                                            tint = Color.White.copy(alpha = 0.7f),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                } else {
                                    IconButton(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            onRefresh()
                                        },
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = "Atualizar página",
                                            tint = Color.White.copy(alpha = 0.8f),
                                            modifier = Modifier.size(17.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                )
            }

            // 3. Tab Counter Badge Button [ 1 ] (Rounded square with cyan border and text)
            Surface(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onOpenTabs()
                },
                shape = RoundedCornerShape(7.dp),
                color = Color.Transparent,
                border = BorderStroke(
                    width = 1.6.dp,
                    color = if (activeTab.isPrivate) Color(0xFFB388FF) else CyanTabBadge
                ),
                modifier = Modifier
                    .size(width = 30.dp, height = 30.dp)
                    .testTag("tabs_button")
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Text(
                        text = if (tabCount > 99) ":)" else tabCount.toString(),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        color = if (activeTab.isPrivate) Color(0xFFB388FF) else CyanTabBadge
                    )
                }
            }

            // 4. Overflow More Options Menu Button [ ⋮ ]
            Box {
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        menuExpanded = true
                    },
                    modifier = Modifier
                        .size(34.dp)
                        .testTag("more_menu_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Mais opções",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    modifier = Modifier.widthIn(min = 230.dp)
                ) {
                    DropdownMenuItem(
                        text = { Text("Nova Guia") },
                        leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            menuExpanded = false
                            onHome()
                        }
                    )

                    DropdownMenuItem(
                        text = { Text("Nova Guia Anônima") },
                        leadingIcon = { Icon(Icons.Default.Security, contentDescription = null, tint = Color(0xFF7C4DFF)) },
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            menuExpanded = false
                            onNewPrivateTab()
                        }
                    )

                    HorizontalDivider()

                    DropdownMenuItem(
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text("Reproduzir Stream...")
                                Surface(
                                    color = Color(0xFF2E7D32).copy(alpha = 0.2f),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = "HLS/DASH",
                                        color = Color(0xFF2E7D32),
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.LiveTv,
                                contentDescription = null,
                                tint = Color(0xFFE53935)
                            )
                        },
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            menuExpanded = false
                            onOpenPlayStream()
                        }
                    )

                    DropdownMenuItem(
                        text = { Text("Downloads") },
                        leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            menuExpanded = false
                            onOpenDownloads()
                        }
                    )

                    DropdownMenuItem(
                        text = { Text("Histórico") },
                        leadingIcon = { Icon(Icons.Default.History, contentDescription = null) },
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            menuExpanded = false
                            onOpenHistory()
                        }
                    )

                    DropdownMenuItem(
                        text = { Text("Gerenciador de Senhas") },
                        leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            menuExpanded = false
                            onOpenPasswords()
                        }
                    )

                    DropdownMenuItem(
                        text = { Text("Network Timeline") },
                        leadingIcon = { Icon(Icons.Default.Sensors, contentDescription = null, tint = CyanTabBadge) },
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            menuExpanded = false
                            onOpenNetworkTimeline()
                        }
                    )

                    DropdownMenuItem(
                        text = { Text("QR Code") },
                        leadingIcon = { Icon(Icons.Default.QrCode2, contentDescription = null, tint = CyanTabBadge) },
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            menuExpanded = false
                            onOpenQrCode()
                        }
                    )

                    DropdownMenuItem(
                        text = {
                            Column {
                                Text("Modo de Foco")
                                Text(
                                    text = modeLabel,
                                    fontSize = 11.sp,
                                    color = modeColor,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        },
                        leadingIcon = { Icon(modeIcon, contentDescription = null, tint = modeColor) },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            menuExpanded = false
                            onToggleFocusMode()
                        }
                    )

                    DropdownMenuItem(
                        text = { Text("Modo Desktop") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.DesktopWindows,
                                contentDescription = null,
                                tint = if (preferences.desktopMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        trailingIcon = {
                            Checkbox(
                                checked = preferences.desktopMode,
                                onCheckedChange = null,
                                colors = CheckboxDefaults.colors(
                                    checkedColor = MaterialTheme.colorScheme.primary
                                ),
                                modifier = Modifier.size(24.dp)
                            )
                        },
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            menuExpanded = false
                            onToggleDesktopMode()
                        }
                    )

                    HorizontalDivider()

                    DropdownMenuItem(
                        text = { Text("Definições") },
                        leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            menuExpanded = false
                            onOpenSettings()
                        }
                    )
                }
            }
        }

        // Web Loading Progress Bar
        AnimatedVisibility(
            visible = isLoading || (progress in 1..99),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            LinearProgressIndicator(
                progress = { (progress.coerceIn(5, 100)) / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.5.dp),
                color = if (activeTab.isPrivate) Color(0xFFB388FF) else CyanTabBadge,
                trackColor = Color.Transparent
            )
        }
    }
}
