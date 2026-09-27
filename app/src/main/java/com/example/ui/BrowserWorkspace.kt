package com.example.ui

import android.view.ViewGroup
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterCenterFocus
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.database.HistoryEntity
import com.example.database.ShortcutEntity
import com.example.model.BrowserTab

@Composable
fun BrowserWorkspace(
    tabs: List<BrowserTab>,
    activeTab: BrowserTab,
    splitTab: BrowserTab?,
    history: List<HistoryEntity> = emptyList(),
    shortcuts: List<ShortcutEntity> = emptyList(),
    onSearch: (String) -> Unit,
    onCloseSplit: () -> Unit,
    onPromoteSplit: () -> Unit,
    onAddShortcut: (String, String) -> Unit = { _, _ -> },
    onUpdateShortcut: (Long, String, String) -> Unit = { _, _, _ -> },
    onDeleteShortcut: (Long) -> Unit = {},
    onResetShortcuts: () -> Unit = {},
    onOpenPlayStreamDialog: () -> Unit = {}
) {
    val focusManager = LocalFocusManager.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        focusManager.clearFocus()
                    }
                )
            }
    ) {
        // Main view upper half
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            tabs.forEach { tab ->
                val isActive = tab.id == activeTab.id
                key(tab.id) {
                    DisposableEffect(isActive, tab.id) {
                        if (isActive) {
                            tab.webView?.onResume()
                        } else {
                            tab.webView?.onPause()
                        }
                        onDispose { }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                alpha = if (isActive) 1f else 0f
                                translationX = if (isActive) 0f else 99999f
                            }
                    ) {
                        if (tab.url.isEmpty() || tab.url == "about:blank") {
                            if (isActive) {
                                HomeDashboard(
                                    onSearch = onSearch,
                                    history = history,
                                    shortcuts = shortcuts,
                                    onAddShortcut = onAddShortcut,
                                    onUpdateShortcut = onUpdateShortcut,
                                    onDeleteShortcut = onDeleteShortcut,
                                    onResetShortcuts = onResetShortcuts,
                                    onOpenPlayStreamDialog = onOpenPlayStreamDialog
                                )
                            }
                        } else {
                            WebViewContainer(tab = tab)
                        }
                    }
                }
            }
        }

        // Secondary bottom half for link focus mode! (Split screen)
        if (splitTab != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1.1f),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Split screen Header with special controls and 48dp minimum touch targets
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.FilterCenterFocus,
                            contentDescription = "Foco",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(20.dp)
                                .padding(end = 4.dp)
                        )
                        Text(
                            text = splitTab.title.ifEmpty { "Guia de Foco" },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        
                        // Action to make this focused browser state the main active tab (48dp touch target)
                        IconButton(
                            onClick = onPromoteSplit,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("split_promote_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = "Fazer principal",
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        // Close split panel (48dp touch target)
                        IconButton(
                            onClick = onCloseSplit,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("split_close_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Fechar Foco",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Progress bar for the splitting webview
                    if (splitTab.isLoading) {
                        LinearProgressIndicator(
                            progress = { splitTab.progress / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp),
                            color = MaterialTheme.colorScheme.secondary,
                            trackColor = Color.Transparent
                        )
                    }

                    // The secondary WebView content viewport
                    Box(modifier = Modifier.fillMaxSize()) {
                        WebViewContainer(tab = splitTab)
                    }
                }
            }
        }
    }
}

@Composable
fun WebViewContainer(tab: BrowserTab) {
    val focusManager = LocalFocusManager.current
    val activeContext = LocalContext.current
    val webViewInstance = tab.webView

    // Dynamically update the WebView's context wrapper to avoid leaking the old activity
    LaunchedEffect(activeContext, tab.id) {
        webViewInstance?.let { webView ->
            val contextWrapper = webView.context as? android.content.MutableContextWrapper
            contextWrapper?.setBaseContext(activeContext)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. Live Interactive WebView Instance with native Pull-To-Refresh (Swipe down at top of page)
        if (webViewInstance != null) {
            AndroidView(
                factory = { context ->
                    val swipeRefreshLayout = SwipeRefreshLayout(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        setColorSchemeColors(
                            android.graphics.Color.parseColor("#00BCD4"),
                            android.graphics.Color.parseColor("#00E676"),
                            android.graphics.Color.parseColor("#7C4DFF")
                        )
                        setProgressBackgroundColorSchemeColor(android.graphics.Color.parseColor("#161B22"))
                        
                        setOnRefreshListener {
                            webViewInstance.reload()
                        }
                    }

                    webViewInstance.apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(this) { _, _ ->
                            androidx.core.view.WindowInsetsCompat.CONSUMED
                        }
                        setOnTouchListener { _, event ->
                            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                                focusManager.clearFocus()
                            }
                            false
                        }
                    }

                    (webViewInstance.parent as? ViewGroup)?.removeView(webViewInstance)
                    swipeRefreshLayout.addView(webViewInstance)
                    swipeRefreshLayout
                },
                update = { swipeLayout ->
                    swipeLayout.isRefreshing = tab.isLoading
                    if (swipeLayout.childCount == 0 || swipeLayout.getChildAt(0) != webViewInstance) {
                        swipeLayout.removeAllViews()
                        (webViewInstance.parent as? ViewGroup)?.removeView(webViewInstance)
                        swipeLayout.addView(webViewInstance)
                    }
                    webViewInstance.setOnTouchListener { _, event ->
                        if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                            focusManager.clearFocus()
                        }
                        false
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // 2. Freeze-Dry Instant Visual Snapshot (Zero Latency Restore Layer)
        val showFreezeDryOverlay = (tab.isRestoringSnapshot || tab.isFrozen || tab.isDiscarded || webViewInstance == null) && tab.thumbnail != null

        AnimatedVisibility(
            visible = showFreezeDryOverlay,
            enter = fadeIn(animationSpec = tween(100)),
            exit = fadeOut(animationSpec = tween(300))
        ) {
            tab.thumbnail?.let { bmp ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.White)
                ) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Prévia da página",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.FillWidth,
                        alignment = Alignment.TopCenter
                    )

                    // Discreto indicador linear de reidratação em progresso no topo
                    if (tab.isRestoringSnapshot || tab.isLoading) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.5.dp)
                                .align(Alignment.TopCenter),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = Color.Transparent
                        )
                    }
                }
            }
        }

        // 3. Fallback discreto caso não haja miniatura e o WebView ainda esteja a ser inicializado
        if (webViewInstance == null && tab.thumbnail == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(24.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(36.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 3.dp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = tab.title.ifEmpty { "A restaurar aba..." },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Recuperando sessão...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
