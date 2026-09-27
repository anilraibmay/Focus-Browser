package com.example.ui

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import androidx.compose.foundation.BorderStroke
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.ActiveDownloadNotification
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import coil.ImageLoader
import coil.decode.VideoFrameDecoder
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import android.view.WindowManager
import android.provider.Settings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.model.BrowserPreferences
import com.example.model.BrowserTab
import com.example.model.FocusMode
import com.example.viewmodel.BrowserViewModel
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.draw.alpha
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.roundToInt
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.painterResource
import com.example.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserMainScreen(viewModel: BrowserViewModel, credentialViewModel: com.example.viewmodel.CredentialViewModel) {
    val context = LocalContext.current
    val tabs by viewModel.tabs.collectAsState()
    val activeTabId by viewModel.activeTabId.collectAsState()
    val splitTab by viewModel.splitTab.collectAsState()
    val preferences by viewModel.preferences.collectAsState()
    val fullscreenView by viewModel.fullscreenView.collectAsState()
    val notification by viewModel.notification.collectAsState()
    val pendingDownloadPayload by viewModel.pendingDownloadPayload.collectAsState()
    val downloadsList by viewModel.downloadsList.collectAsState()
    val sslErrorState by viewModel.sslErrorState.collectAsState()
    val historyList by viewModel.historyList.collectAsState()
    val shortcuts by viewModel.shortcuts.collectAsState()
    val activeMediaPlayback by viewModel.activeMediaPlayback.collectAsStateWithLifecycle()

    var showTabsManager by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showPasswordManager by remember { mutableStateOf(false) }
    var settingsTabSelected by remember { mutableStateOf(0) }
    var urlInputText by remember { mutableStateOf("") }
    var isAddressFocused by remember { mutableStateOf(false) }
    var showExtractionDialog by remember { mutableStateOf(false) }
    var showPlayStreamDialog by remember { mutableStateOf(false) }
    var showFocusModeDialog by remember { mutableStateOf(false) }
    var showVideoHistoryDialog by remember { mutableStateOf(false) }
    var showQrDialog by remember { mutableStateOf(false) }
    
    var autofillDomain by remember { mutableStateOf<String?>(null) }
    var autofillWebView by remember { mutableStateOf<android.webkit.WebView?>(null) }
    var savePasswordDomain by remember { mutableStateOf<String?>(null) }

    val activeTab = tabs.find { it.id == activeTabId }
    val focusManager = LocalFocusManager.current
    
    val imageLoader = remember(context) {
        ImageLoader.Builder(context)
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .build()
    }

    var downloadActionToRun by remember { mutableStateOf<(() -> Unit)?>(null) }

    // Dedicated YouTube Downloader State
    val youTubeDetails by viewModel.youTubeDetails.collectAsStateWithLifecycle()
    val isYouTubeExtracting by viewModel.isYouTubeExtracting.collectAsStateWithLifecycle()
    var showYouTubeDialog by remember { mutableStateOf(false) }

    val isYouTubeUrl = remember(activeTab?.url) {
        com.example.util.YouTubeDedicatedEngine.isYouTubeUrl(activeTab?.url)
    }
    val isYouTubeVideo = remember(activeTab?.url) {
        isYouTubeUrl && com.example.util.YouTubeDedicatedEngine.extractVideoId(activeTab?.url) != null
    }

    // Auto-extract YouTube details as soon as a YouTube video URL is loaded
    LaunchedEffect(activeTab?.url) {
        if (isYouTubeVideo) {
            val cookies = android.webkit.CookieManager.getInstance().getCookie(activeTab?.url) ?: ""
            val ua = activeTab?.webView?.settings?.userAgentString ?: ""
            viewModel.extractYouTubeDetails(activeTab?.url ?: "", cookies, ua)
        } else if (!isYouTubeUrl) {
            viewModel.clearYouTubeDetails()
        }
    }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE || event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                viewModel.saveCurrentSession()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DisposableEffect(Unit) {
        val receiver = com.example.util.DownloadCompleteReceiver { message ->
            viewModel.showNotification(message)
        }
        val filter = android.content.IntentFilter(android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        androidx.core.content.ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED
        )
        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (ignored: Exception) {}
        }
    }

    val writeStoragePermissionState = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            downloadActionToRun?.invoke()
            downloadActionToRun = null
        } else {
            viewModel.showNotification("Permissão de armazenamento negada!")
        }
    }

    val notificationPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { /* Notificações opcionais para transferências */ }

    // Solicitação oportuna de permissões apenas quando necessário para downloads
    val performDownloadAction: (() -> Unit) -> Unit = { action ->
        // Solicitação oportuna de notificação no Android 13+ (apenas ao iniciar download)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.P) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                action()
            } else {
                downloadActionToRun = action
                writeStoragePermissionState.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        } else {
            action()
        }
    }

    // Gestão de upload de ficheiros no navegador (<input type="file">)
    val pendingFileChooser by viewModel.pendingFileChooser.collectAsState()
    val fileChooserLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris = android.webkit.WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        viewModel.handleFileChooserResult(uris)
    }

    LaunchedEffect(pendingFileChooser) {
        pendingFileChooser?.let { chooser ->
            try {
                val intent = chooser.fileChooserParams.createIntent()
                fileChooserLauncher.launch(intent)
            } catch (e: Exception) {
                try {
                    val fallbackIntent = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
                        addCategory(android.content.Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                    fileChooserLauncher.launch(fallbackIntent)
                } catch (e2: Exception) {
                    viewModel.handleFileChooserResult(null)
                }
            }
        }
    }

    val pendingWebPermissionRequest by viewModel.pendingWebPermissionRequest.collectAsState()
    val pendingGeolocationRequest by viewModel.pendingGeolocationRequest.collectAsState()
    val revokedPermissionAlert by viewModel.revokedPermissionAlert.collectAsState()
    val networkEvents by viewModel.networkEvents.collectAsState()
    var showSiteInfoDialog by remember { mutableStateOf(false) }
    var showNetworkTimeline by remember { mutableStateOf(false) }

    // Synchronize address bar based on active tab loading (only when user is not typing)
    LaunchedEffect(activeTab?.url) {
        if (!isAddressFocused) {
            urlInputText = activeTab?.url ?: ""
        }
    }

    // Auto-create initial tab if list is empty
    LaunchedEffect(tabs) {
        if (tabs.isEmpty()) {
            viewModel.createNewTab(context, "")
        }
    }

    // Handle system back press
    BackHandler(enabled = true) {
        when {
            isAddressFocused -> {
                isAddressFocused = false
                focusManager.clearFocus()
            }
            fullscreenView != null -> {
                // If video is fullscreen, exit it
                activeTab?.webView?.webChromeClient?.onHideCustomView()
            }
            activeMediaPlayback != null -> {
                viewModel.dismissMediaPlayback()
            }
            showPlayStreamDialog -> {
                showPlayStreamDialog = false
            }
            showFocusModeDialog -> {
                showFocusModeDialog = false
            }
            showVideoHistoryDialog -> {
                showVideoHistoryDialog = false
            }
            showQrDialog -> {
                showQrDialog = false
            }
            showYouTubeDialog -> {
                showYouTubeDialog = false
            }
            showExtractionDialog -> {
                showExtractionDialog = false
            }
            showNetworkTimeline -> {
                showNetworkTimeline = false
            }
            showTabsManager -> {
                showTabsManager = false
            }
            showSettings -> {
                showSettings = false
            }
            splitTab != null -> {
                // Close split screen pane first
                viewModel.closeSplitScreen()
            }
            activeTab?.webView?.canGoBack() == true -> {
                activeTab?.webView?.goBack()
            }
            tabs.size > 1 -> {
                // Close active tab and go back to previous one
                activeTab?.let { viewModel.closeTab(it.id, context) }
            }
            else -> {
                // Exit app or make background
                (context as? Activity)?.moveTaskToBack(true)
            }
        }
    }

    // Fullscreen video streaming container
    if (fullscreenView != null) {
        FullScreenVideoContainer(fullscreenView = fullscreenView!!, onExit = { viewModel.exitFullscreen() })
    } else {
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
            topBar = {
                if (activeTab != null && !showTabsManager && !showSettings && !showPasswordManager && !showNetworkTimeline) {
                    val extractedVideos = activeTab.extractedVideos
                    val mediaGroups = activeTab.mediaGroups
                    val distinctVideoCount = if (isYouTubeVideo || youTubeDetails != null) {
                        (youTubeDetails?.videoFormats?.size ?: 0) + (youTubeDetails?.audioFormats?.size ?: 0)
                    } else if (mediaGroups.isNotEmpty()) {
                        mediaGroups.size
                    } else {
                        extractedVideos.size
                    }
                    UnifiedToolbar(
                        urlText = urlInputText,
                        isLoading = activeTab.isLoading,
                        progress = activeTab.progress,
                        preferences = preferences,
                        tabCount = tabs.size,
                        activeTab = activeTab,
                        splitTab = splitTab,
                        extractedVideoCount = distinctVideoCount,
                        onOpenExtractedVideos = {
                            if (isYouTubeVideo || youTubeDetails != null) {
                                val cookies = android.webkit.CookieManager.getInstance().getCookie(activeTab.url) ?: ""
                                val ua = activeTab.webView?.settings?.userAgentString ?: ""
                                viewModel.extractYouTubeDetails(activeTab.url, cookies, ua)
                                showYouTubeDialog = true
                            } else {
                                showExtractionDialog = true
                            }
                        },
                        onUrlChange = { urlInputText = it },
                        onOpenSiteInfo = {
                            if (activeTab != null && activeTab.url.isNotBlank()) {
                                showSiteInfoDialog = true
                            }
                        },
                        onFocusStateChange = { focused ->
                            isAddressFocused = focused
                        },
                        onNavigate = {
                            isAddressFocused = false
                            focusManager.clearFocus()
                            viewModel.loadUrl(activeTab, urlInputText)
                        },
                        onRefresh = { viewModel.refresh(activeTab) },
                        onToggleFocusMode = {
                            showFocusModeDialog = true
                        },
                        onToggleDesktopMode = {
                            viewModel.updatePreferences { it.copy(desktopMode = !it.desktopMode) }
                        },
                        onBack = {
                            if (splitTab != null && splitTab?.webView?.canGoBack() == true) {
                                viewModel.goBack(splitTab!!)
                            } else {
                                viewModel.goBack(activeTab)
                            }
                        },
                        onForward = {
                            if (splitTab != null && splitTab?.webView?.canGoForward() == true) {
                                viewModel.goForward(splitTab!!)
                            } else {
                                viewModel.goForward(activeTab)
                            }
                        },
                        onHome = {
                            if (splitTab != null) {
                                viewModel.closeSplitScreen()
                            }
                            viewModel.navigateToHome(activeTab)
                        },
                        onOpenTabs = {
                            if (activeTabId.isNotEmpty()) {
                                viewModel.captureTabThumbnail(activeTabId)
                            }
                            showTabsManager = true
                            showSettings = false
                        },
                        onNewPrivateTab = {
                            viewModel.createNewPrivateTab(context, "")
                            showTabsManager = false
                        },
                        onOpenDownloads = {
                            showTabsManager = false
                            showSettings = true
                            settingsTabSelected = 1
                        },
                        onOpenSettings = {
                            showTabsManager = false
                            showSettings = true
                            settingsTabSelected = 0
                        },
                        onOpenHistory = {
                            showTabsManager = false
                            showSettings = true
                            settingsTabSelected = 2
                        },
                        onOpenVideoHistory = {
                            showVideoHistoryDialog = true
                        },
                        onOpenPasswords = {
                            showPasswordManager = true
                        },
                        onOpenNetworkTimeline = {
                            showNetworkTimeline = true
                        },
                        onOpenPlayStream = {
                            showPlayStreamDialog = true
                        },
                        onOpenQrCode = {
                            showQrDialog = true
                        },
                        onOpenAutofillDialog = { domain, webView ->
                            autofillDomain = domain
                            autofillWebView = webView
                        },
                        onOpenSavePasswordDialog = { domain ->
                            savePasswordDomain = domain
                        }
                    )
                }
            },
            bottomBar = {
                if (activeTab != null && !showTabsManager && !showSettings && !showPasswordManager && !showNetworkTimeline) {
                    val extractedVideos = activeTab.extractedVideos
                    val mediaGroups = activeTab.mediaGroups
                    val distinctVideoCount = if (isYouTubeVideo || youTubeDetails != null) {
                        (youTubeDetails?.videoFormats?.size ?: 0) + (youTubeDetails?.audioFormats?.size ?: 0)
                    } else if (mediaGroups.isNotEmpty()) {
                        mediaGroups.size
                    } else {
                        extractedVideos.size
                    }
                    val isBackEnabled = (splitTab?.canGoBack == true) || activeTab.canGoBack
                    val isForwardEnabled = (splitTab?.canGoForward == true) || activeTab.canGoForward

                    BrowserBottomBar(
                        canGoBack = isBackEnabled,
                        canGoForward = isForwardEnabled,
                        extractedVideoCount = distinctVideoCount,
                        isPrivate = activeTab.isPrivate,
                        onBack = {
                            if (splitTab != null && splitTab?.webView?.canGoBack() == true) {
                                viewModel.goBack(splitTab!!)
                            } else {
                                viewModel.goBack(activeTab)
                            }
                        },
                        onForward = {
                            if (splitTab != null && splitTab?.webView?.canGoForward() == true) {
                                viewModel.goForward(splitTab!!)
                            } else {
                                viewModel.goForward(activeTab)
                            }
                        },
                        onHome = {
                            if (splitTab != null) {
                                viewModel.closeSplitScreen()
                            }
                            viewModel.navigateToHome(activeTab)
                        },
                        onOpenDownloadsOrMedia = {
                            if (distinctVideoCount > 0) {
                                if (isYouTubeVideo || youTubeDetails != null) {
                                    val cookies = android.webkit.CookieManager.getInstance().getCookie(activeTab.url) ?: ""
                                    val ua = activeTab.webView?.settings?.userAgentString ?: ""
                                    viewModel.extractYouTubeDetails(activeTab.url, cookies, ua)
                                    showYouTubeDialog = true
                                } else {
                                    showExtractionDialog = true
                                }
                            } else {
                                showTabsManager = false
                                showSettings = true
                                settingsTabSelected = 1
                            }
                        },
                        onNewTab = {
                            viewModel.createNewTab(context, "")
                            showTabsManager = false
                        }
                    )
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Main Browser Workspace (Includes split-screen layout)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        if (showTabsManager) {
                            TabsManagerContent(
                                isMaximized = true,
                                onToggleMaximize = { showTabsManager = false },
                                tabs = tabs,
                                activeTabId = activeTabId,
                                onSelectTab = { id ->
                                    viewModel.selectTab(id, context)
                                    showTabsManager = false
                                },
                                onCloseTab = { id ->
                                    viewModel.closeTab(id, context)
                                },
                                onNewTab = {
                                    viewModel.createNewTab(context, "")
                                    showTabsManager = false
                                },
                                onNewPrivateTab = {
                                    viewModel.createNewPrivateTab(context, "")
                                    showTabsManager = false
                                },
                                onCloseAllTabs = {
                                    viewModel.closeAllTabs(context)
                                    showTabsManager = false
                                },
                                onCloseAllPrivateTabs = {
                                    viewModel.closeAllPrivateTabs(context)
                                    showTabsManager = false
                                }
                            )
                        } else if (showPasswordManager) {
                            PasswordManagerContent(
                                isMaximized = true,
                                onToggleMaximize = { showPasswordManager = false },
                                viewModel = credentialViewModel
                            )
                        } else if (showNetworkTimeline) {
                            NetworkTimelineScreen(
                                networkEvents = networkEvents,
                                onClose = { showNetworkTimeline = false },
                                onClearEvents = { viewModel.clearNetworkEvents() },
                                onDownloadDirect = { mediaUrl ->
                                    val ua = activeTab?.webView?.settings?.userAgentString ?: ""
                                    viewModel.startDirectDownload(
                                        context = context,
                                        url = mediaUrl,
                                        userAgent = ua
                                    )
                                },
                                onOpenUrlInNewTab = { targetUrl ->
                                    viewModel.createNewTab(context, targetUrl)
                                    showNetworkTimeline = false
                                }
                            )
                        } else if (showSettings) {
                            SettingsContent(
                                isMaximized = true,
                                onToggleMaximize = { showSettings = false },
                                preferences = preferences,
                                onUpdatePreferences = { update ->
                                    viewModel.updatePreferences(update)
                                },
                                onClearData = {
                                    viewModel.clearHistoryAndCache()
                                },
                                onSimulateLowMemory = {
                                    viewModel.discardTabsOnLowMemory()
                                },
                                downloads = downloadsList,
                                history = historyList,
                                viewModel = viewModel,
                                tabSelected = settingsTabSelected,
                                onTabSelected = { settingsTabSelected = it },
                                onOpenUrl = { url ->
                                    showSettings = false
                                    activeTab?.let { viewModel.loadUrl(it, url) }
                                }
                            )
                        } else {
                            if (activeTab == null) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator()
                                }
                            } else {
                                BrowserWorkspace(
                                    tabs = tabs,
                                    activeTab = activeTab,
                                    splitTab = splitTab,
                                    history = historyList,
                                    shortcuts = shortcuts,
                                    onSearch = { query ->
                                        viewModel.loadUrl(activeTab, query)
                                    },
                                    onCloseSplit = {
                                        viewModel.closeSplitScreen()
                                    },
                                    onPromoteSplit = {
                                        splitTab?.url?.let { url ->
                                            viewModel.createNewTab(context, url, selectNow = true)
                                            viewModel.closeSplitScreen()
                                        }
                                    },
                                    onAddShortcut = { title, url ->
                                        viewModel.addShortcut(title, url)
                                    },
                                    onUpdateShortcut = { id, title, url ->
                                        viewModel.updateShortcut(id, title, url)
                                    },
                                    onDeleteShortcut = { id ->
                                        viewModel.deleteShortcut(id)
                                    },
                                    onResetShortcuts = {
                                        viewModel.resetShortcutsToDefaults()
                                    },
                                    onOpenPlayStreamDialog = {
                                        showPlayStreamDialog = true
                                    }
                                )
                            }
                        }
                    }


                }

                // Video Extract Dialog
                val extractedVideos = activeTab?.extractedVideos ?: emptyList()
                val mediaGroups = activeTab?.mediaGroups ?: emptyList()

                // Dedicated YouTube Download Bottom Sheet / Dialog
                if (showYouTubeDialog) {
                    YouTubeDownloadBottomSheet(
                        videoDetails = youTubeDetails,
                        isLoading = isYouTubeExtracting,
                        onDismiss = { showYouTubeDialog = false },
                        onRetry = {
                            val cookies = android.webkit.CookieManager.getInstance().getCookie(activeTab?.url) ?: ""
                            val ua = activeTab?.webView?.settings?.userAgentString ?: ""
                            viewModel.extractYouTubeDetails(activeTab?.url ?: "", cookies, ua, force = true)
                        },
                        onFallbackStandard = {
                            showYouTubeDialog = false
                            showExtractionDialog = true
                        },
                        onPreviewFormat = { format ->
                            val ua = activeTab?.webView?.settings?.userAgentString ?: ""
                            val headers = if (ua.isNotBlank()) mapOf("User-Agent" to ua) else emptyMap()
                            val isAudio = format.isAudioOnly
                            val playRequest = com.example.media.MediaPlayRequest(
                                title = youTubeDetails?.title ?: "Prévia YouTube",
                                uri = android.net.Uri.parse(format.url),
                                thumbnailUrl = youTubeDetails?.thumbnailUrl,
                                headers = headers,
                                mimeType = format.mimeType.substringBefore(';'),
                                isAudioOnly = isAudio,
                                subtitle = "Prévia YouTube • ${format.qualityLabel.ifBlank { if (isAudio) "Áudio" else "Vídeo" }} • ${format.formattedSize}"
                            )
                            viewModel.playMedia(playRequest)
                        },
                        onDownloadVideo = { videoFmt, audioFmt ->
                            showYouTubeDialog = false
                            val videoTitle = youTubeDetails?.title ?: activeTab?.title ?: "YouTube_Video"
                            val cleanName = com.example.util.VideoFileNameGenerator.sanitizePageTitle(videoTitle) + ".mp4"
                            viewModel.triggerInstantDownloadNotification(cleanName)
                            performDownloadAction {
                                val ua = activeTab?.webView?.settings?.userAgentString ?: ""
                                val cookies = android.webkit.CookieManager.getInstance().getCookie(activeTab?.url) ?: ""
                                viewModel.startYouTubeDualStreamDownload(
                                    context = context,
                                    title = videoTitle,
                                    videoUrl = videoFmt.url,
                                    audioUrl = if (videoFmt.isCombined) "" else (audioFmt?.url ?: ""),
                                    ua = ua,
                                    cookies = cookies
                                )
                            }
                        },
                        onDownloadAudio = { audioFmt, asMp3 ->
                            showYouTubeDialog = false
                            val audioTitle = youTubeDetails?.title ?: activeTab?.title ?: "YouTube_Audio"
                            val ext = if (asMp3) ".mp3" else ".m4a"
                            val cleanName = com.example.util.VideoFileNameGenerator.sanitizePageTitle(audioTitle) + ext
                            viewModel.triggerInstantDownloadNotification(cleanName)
                            performDownloadAction {
                                val ua = activeTab?.webView?.settings?.userAgentString ?: ""
                                val cookies = android.webkit.CookieManager.getInstance().getCookie(activeTab?.url) ?: ""
                                viewModel.startYouTubeAudioDownload(
                                    context = context,
                                    title = audioTitle,
                                    audioUrl = audioFmt.url,
                                    ua = ua,
                                    cookies = cookies,
                                    asMp3 = asMp3
                                )
                            }
                        }
                    )
                }

                if (showExtractionDialog) {
                    androidx.compose.ui.window.Dialog(
                        onDismissRequest = { showExtractionDialog = false },
                        properties = androidx.compose.ui.window.DialogProperties(
                            usePlatformDefaultWidth = false, // Make it full screen
                            decorFitsSystemWindows = false
                        )
                    ) {
                        ExtractedVideosScreen(
                            extractedVideos = extractedVideos,
                            mediaGroups = mediaGroups,
                            imageLoader = imageLoader,
                            onClose = { showExtractionDialog = false },
                            onRescan = {
                                viewModel.rescanVideos(activeTab)
                            },
                            onPreviewVideo = { video ->
                                viewModel.previewExtractedVideo(video)
                            },
                            onDownload = { selectedVideosList ->
                                showExtractionDialog = false
                                val pageTitle = activeTab?.title?.takeIf { it.isNotBlank() && it != "Nova Aba" && it != "about:blank" }
                                    ?: selectedVideosList.firstOrNull()?.title
                                val firstTitle = selectedVideosList.firstOrNull()?.let { v -> pageTitle ?: v.title } ?: "video"
                                val firstCleanName = com.example.util.VideoFileNameGenerator.sanitizePageTitle(firstTitle) + ".mp4"
                                viewModel.triggerInstantDownloadNotification(firstCleanName)
                                performDownloadAction {
                                    val existingDownloadNames = downloadsList.map { it.fileName }.toMutableSet()
                                    try {
                                        val downloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                                        val filesOnDisk = downloadDir.list()
                                        if (filesOnDisk != null) {
                                            existingDownloadNames.addAll(filesOnDisk)
                                        }
                                    } catch (_: Exception) {}

                                    val generatedNames = com.example.util.VideoFileNameGenerator.generateVideoFileNames(
                                        pageTitle = pageTitle,
                                        videos = selectedVideosList,
                                        existingNames = existingDownloadNames
                                    )

                                    selectedVideosList.forEachIndexed { index, video ->
                                        val ua = video.headers["User-Agent"] ?: ""
                                        val fname = generatedNames.getOrElse(index) { "video_${System.currentTimeMillis()}.mp4" }
                                        
                                        val mimeType = when (video.type) {
                                            com.example.extractor.VideoType.HLS -> "application/x-mpegURL"
                                            com.example.extractor.VideoType.AUDIO -> {
                                                if (fname.endsWith(".mp3")) "audio/mpeg" else "audio/mp4"
                                            }
                                            else -> "video/mp4"
                                        }

                                        val isYouTube = video.url.contains("googlevideo.com", ignoreCase = true) ||
                                                        video.url.contains("youtube.com", ignoreCase = true)

                                        val groupForVideo = mediaGroups.firstOrNull { it.id == video.mediaGroupId }
                                            ?: mediaGroups.firstOrNull { g -> g.streams.any { it.rawVideo.url == video.url } }

                                        val matchingAudio = if (isYouTube && video.type != com.example.extractor.VideoType.AUDIO) {
                                            groupForVideo?.audioStreams?.firstOrNull()?.rawVideo
                                                ?: extractedVideos.firstOrNull {
                                                    it.type == com.example.extractor.VideoType.AUDIO &&
                                                    (it.url.contains("googlevideo.com", ignoreCase = true) || it.url.contains("youtube.com", ignoreCase = true))
                                                }
                                        } else null

                                        if (isYouTube && matchingAudio != null) {
                                            val titleForDownload = (pageTitle ?: video.title).takeIf { it.isNotBlank() } ?: "YouTube_Video"
                                            val cookies = video.headers["Cookie"] ?: ""
                                            viewModel.startYouTubeDualStreamDownload(
                                                context = context,
                                                title = titleForDownload,
                                                videoUrl = video.url,
                                                audioUrl = matchingAudio.url,
                                                ua = ua,
                                                cookies = cookies
                                            )
                                        } else {
                                            viewModel.executeDirectDownload(
                                                context = context,
                                                payload = com.example.model.DownloadPayload(
                                                    url = video.url,
                                                    userAgent = ua,
                                                    contentDisposition = "filename=\"$fname\"",
                                                    mimeType = mimeType,
                                                    customFileName = fname,
                                                    headers = video.headers
                                                )
                                            )
                                        }
                                    }
                                }
                            },
                            onCopyLinks = { selectedVideosList ->
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                val urlsJoined = selectedVideosList.joinToString("\n") { it.url }
                                val clip = android.content.ClipData.newPlainText("Links de Vídeos Extraídos", urlsJoined)
                                clipboard?.setPrimaryClip(clip)
                                viewModel.showNotification("Links copiados para a área de transferência!")
                                showExtractionDialog = false
                            }
                        )
                    }
                }

                // Instant Notification for Started Downloads (Non-fixed, auto-dismisses in 4 seconds)
                val activeDownloadNotif by viewModel.activeDownloadNotification.collectAsStateWithLifecycle()

                LaunchedEffect(activeDownloadNotif?.id) {
                    val notif = activeDownloadNotif ?: return@LaunchedEffect
                    delay(4000)
                    if (viewModel.activeDownloadNotification.value?.id == notif.id) {
                        viewModel.dismissActiveDownloadNotification()
                    }
                }

                // Floating "VidMate-like" Download Indicator Button
                // Displays automatically when videos/audio are detected on the current active tab or when on YouTube
                val isYouTubePage = isYouTubeVideo || youTubeDetails != null
                val detectedCount = if (isYouTubePage) {
                    (youTubeDetails?.videoFormats?.size ?: 0) + (youTubeDetails?.audioFormats?.size ?: 0)
                } else if (mediaGroups.isNotEmpty()) {
                    mediaGroups.size
                } else {
                    extractedVideos.size
                }
                val isFloatingButtonVisible = (isYouTubePage || detectedCount > 0) && !showTabsManager && !showSettings && !showExtractionDialog && !showYouTubeDialog

                val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "vidmate_pulse")
                val pulseScale by infiniteTransition.animateFloat(
                    initialValue = 1f,
                    targetValue = 1.08f,
                    animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                        animation = androidx.compose.animation.core.tween(750, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                        repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
                    ),
                    label = "pulseScale"
                )

                AnimatedVisibility(
                    visible = isFloatingButtonVisible,
                    enter = androidx.compose.animation.scaleIn(tween(300)) + fadeIn(tween(250)),
                    exit = androidx.compose.animation.scaleOut(tween(300)) + fadeOut(tween(250)),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 18.dp, bottom = 28.dp)
                ) {
                    Surface(
                        onClick = {
                            if (isYouTubePage) {
                                val cookies = android.webkit.CookieManager.getInstance().getCookie(activeTab?.url) ?: ""
                                val ua = activeTab?.webView?.settings?.userAgentString ?: ""
                                viewModel.extractYouTubeDetails(activeTab?.url ?: "", cookies, ua)
                                showYouTubeDialog = true
                            } else {
                                showExtractionDialog = true
                            }
                        },
                        shape = CircleShape,
                        color = if (isYouTubePage) Color(0xFFFF0000) else Color(0xFFE53935), // YouTube Red
                        shadowElevation = 10.dp,
                        modifier = Modifier
                            .graphicsLayer {
                                scaleX = pulseScale
                                scaleY = pulseScale
                            }
                            .size(56.dp)
                            .testTag("floating_vidmate_download_button")
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            // Downward arrow icon or YouTube badge (VidMate style)
                            Icon(
                                imageVector = if (isYouTubePage) Icons.Default.PlayArrow else Icons.Default.ArrowDownward,
                                contentDescription = if (isYouTubePage) "Baixar do YouTube" else "Vídeo detectado - Escolher qualidade",
                                tint = Color.White,
                                modifier = Modifier.size(30.dp)
                            )

                            // Badge count for detected qualities/streams
                            if (detectedCount > 0) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(x = (-3).dp, y = 3.dp)
                                        .background(Color.White, CircleShape)
                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                        .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = if (detectedCount > 99) "99+" else detectedCount.toString(),
                                        color = Color(0xFFD32F2F),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        lineHeight = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }

                AnimatedVisibility(
                    visible = activeDownloadNotif != null,
                    enter = fadeIn(animationSpec = tween(50)) + slideInVertically(
                        animationSpec = tween(70),
                        initialOffsetY = { it / 3 }
                    ),
                    exit = fadeOut(animationSpec = tween(120)) + slideOutVertically(
                        animationSpec = tween(120),
                        targetOffsetY = { it / 3 }
                    ),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 76.dp, end = 16.dp, start = 16.dp)
                ) {
                    activeDownloadNotif?.let { notif ->
                        Surface(
                            color = MaterialTheme.colorScheme.inverseSurface,
                            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                            shape = RoundedCornerShape(16.dp),
                            shadowElevation = 8.dp,
                            modifier = Modifier
                                .widthIn(max = 420.dp)
                                .fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                                    .fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Download,
                                        contentDescription = "Download Iniciado",
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Transferência iniciada",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.inverseOnSurface
                                    )
                                    Text(
                                        text = notif.fileName,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.8f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                TextButton(
                                    onClick = {
                                        viewModel.dismissActiveDownloadNotification()
                                        showTabsManager = false
                                        settingsTabSelected = 1
                                        showSettings = true
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "Ver",
                                        color = MaterialTheme.colorScheme.inversePrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }

                                IconButton(
                                    onClick = { viewModel.dismissActiveDownloadNotification() },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Fechar notificação",
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                    }
                }

                // Ssl Error Dialog
                sslErrorState?.let { errorState ->
                    AlertDialog(
                        onDismissRequest = { viewModel.dismissSslError() },
                        title = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Security,
                                    tint = MaterialTheme.colorScheme.error,
                                    contentDescription = "Aviso de Segurança"
                                )
                                Text("Aviso de Segurança (SSL/TLS)", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                            }
                        },
                        text = {
                            Text(
                                "O certificado deste site (${errorState.url}) não é confiável ou está expirado.\n\n" +
                                "A conexão não é privada e as suas informações podem estar em risco se continuar.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = { viewModel.proceedSslError() }) {
                                Text("Avançar Mesmo Assim", color = MaterialTheme.colorScheme.error)
                            }
                        },
                        dismissButton = {
                            Button(onClick = { viewModel.dismissSslError() }) {
                                Text("Voltar em Segurança")
                            }
                        }
                    )
                }

                savePasswordDomain?.let { domain ->
                    var username by remember { mutableStateOf("") }
                    var password by remember { mutableStateOf("") }
                    AlertDialog(
                        onDismissRequest = { savePasswordDomain = null },
                        title = { Text("Guardar Senha para $domain") },
                        text = {
                            Column {
                                OutlinedTextField(
                                    value = username,
                                    onValueChange = { username = it },
                                    label = { Text("Utilizador/Email") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = password,
                                    onValueChange = { password = it },
                                    label = { Text("Senha") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                credentialViewModel.savePassword(domain, username, password, context as? androidx.fragment.app.FragmentActivity)
                                savePasswordDomain = null
                            }) {
                                Text("Guardar")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { savePasswordDomain = null }) {
                                Text("Cancelar")
                            }
                        }
                    )
                }

                autofillDomain?.let { domain ->
                    val passwords by credentialViewModel.passwordList.collectAsState()
                    val domainPasswords = passwords.filter { it.domain == domain }

                    AlertDialog(
                        onDismissRequest = { autofillDomain = null },
                        title = { Text("Preencher Senhas") },
                        text = {
                            if (domainPasswords.isEmpty()) {
                                Text("Não há senhas guardadas para este site.")
                            } else {
                                LazyColumn {
                                    items(domainPasswords) { pwd ->
                                        ListItem(
                                            headlineContent = { Text(pwd.username) },
                                            modifier = Modifier.clickable {
                                                val activity = context as? androidx.fragment.app.FragmentActivity
                                                if (activity != null) {
                                                    // Ensure AdBlock is enabled to mitigate XSS risks during autofill
                                                    if (!preferences.enableAdBlock) {
                                                        viewModel.updatePreferences { it.copy(enableAdBlock = true) }
                                                    }
                                                    credentialViewModel.getDecryptedPassword(pwd, activity, onSuccess = { decrypted ->
                                                        // Inject JS securely
                                                        // Use Base64 encoding to avoid syntax errors with quotes and mitigate naive JS injection
                                                        val u64 = android.util.Base64.encodeToString(pwd.username.toByteArray(), android.util.Base64.NO_WRAP)
                                                        val p64 = android.util.Base64.encodeToString(decrypted.toByteArray(), android.util.Base64.NO_WRAP)
                                                        
                                                        val js = """
                                                            (function() {
                                                                let u = atob('$u64');
                                                                let p = atob('$p64');
                                                                try {
                                                                    const inputs = document.getElementsByTagName('input');
                                                                    for (let i = 0; i < inputs.length; i++) {
                                                                        const input = inputs[i];
                                                                        const type = input.type.toLowerCase();
                                                                        if (type === 'password') {
                                                                            input.value = p;
                                                                            input.dispatchEvent(new Event('input', { bubbles: true }));
                                                                            input.dispatchEvent(new Event('change', { bubbles: true }));
                                                                        } else if ((type === 'text' || type === 'email') && !input.value) {
                                                                            input.value = u;
                                                                            input.dispatchEvent(new Event('input', { bubbles: true }));
                                                                            input.dispatchEvent(new Event('change', { bubbles: true }));
                                                                        }
                                                                    }
                                                                } catch(e) {
                                                                } finally {
                                                                    u = null;
                                                                    p = null;
                                                                }
                                                            })();
                                                        """.trimIndent()
                                                        autofillWebView?.evaluateJavascript(js, null)
                                                        autofillDomain = null
                                                    })
                                                }

                                            }
                                        )
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { autofillDomain = null }) {
                                Text("Fechar")
                            }
                        }
                    )
                }

                // Intercept Confirmation Dialog for Downloads
                pendingDownloadPayload?.let { req ->
                    DownloadApprovalDialog(
                        payload = req,
                        defaultDirectoryName = preferences.downloadDirectoryName,
                        defaultDirectoryUri = preferences.downloadDirectoryUri,
                        onConfirm = { ctx, confirmedPayload ->
                            val resolvedName = confirmedPayload.customFileName
                                ?: android.webkit.URLUtil.guessFileName(confirmedPayload.url, confirmedPayload.contentDisposition, confirmedPayload.mimeType)
                                ?: "ficheiro"
                            viewModel.triggerInstantDownloadNotification(resolvedName)
                            performDownloadAction {
                                viewModel.confirmDownload(ctx, confirmedPayload)
                            }
                        },
                        onCancel = { viewModel.cancelPendingDownload() }
                    )
                }

                // WebRTC Media Permissions (Camera, Microphone)
                pendingWebPermissionRequest?.let { req ->
                    WebMediaPermissionPromptDialog(
                        requestEvent = req,
                        viewModel = viewModel
                    )
                }

                // HTML5 Geolocation Permissions
                pendingGeolocationRequest?.let { req ->
                    WebGeolocationPromptDialog(
                        requestEvent = req,
                        viewModel = viewModel
                    )
                }

                // Revoked / Denied System Permission Alert
                revokedPermissionAlert?.let { alert ->
                    RevokedPermissionAlertDialog(
                        alert = alert,
                        onDismiss = { viewModel.dismissRevokedPermissionAlert() }
                    )
                }

                // Page Info & Origin-Specific Permissions Dialog (triggered by lock icon in address bar)
                if (showSiteInfoDialog && activeTab != null) {
                    SiteInfoBottomSheet(
                        activeTab = activeTab,
                        viewModel = viewModel,
                        onDismiss = { showSiteInfoDialog = false }
                    )
                }

                // Temporary Focus notifications (snackbar)
                AnimatedVisibility(
                    visible = notification != null,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp, start = 16.dp, end = 16.dp)
                ) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ),
                        shape = RoundedCornerShape(12.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.FilterCenterFocus,
                                contentDescription = "Modo Foco"
                            )
                            Text(
                                text = notification ?: "",
                                fontSize = 14.sp,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = { viewModel.clearNotification() }
                            ) {
                                Text("OK")
                            }
                        }
                    }
                }

                // Interactive History Suggestions Overlay (Chrome-like)
                if (isAddressFocused && !showTabsManager && !showSettings && !showPasswordManager) {
                    HistorySuggestionsOverlay(
                        query = urlInputText,
                        history = historyList,
                        onSelectUrl = { url ->
                            urlInputText = url
                            isAddressFocused = false
                            focusManager.clearFocus()
                            activeTab?.let { viewModel.loadUrl(it, url) }
                        },
                        onInsertUrl = { url ->
                            urlInputText = url
                        },
                        onDeleteHistory = { id ->
                            viewModel.deleteHistoryEntry(id)
                        },
                        onDismiss = {
                            isAddressFocused = false
                            focusManager.clearFocus()
                        },
                        onPlayStream = { streamUrl ->
                            isAddressFocused = false
                            focusManager.clearFocus()
                            viewModel.playDirectStream(streamUrl)
                        }
                    )
                }

                // Diálogo para Colar e Reproduzir Streams (HLS, DASH, MP4, Áudio)
                if (showPlayStreamDialog) {
                    PlayStreamDialog(
                        initialUrl = if (com.example.util.StreamUrlHelper.isDirectStreamUrl(urlInputText)) urlInputText else "",
                        onDismiss = { showPlayStreamDialog = false },
                        onPlayStream = { url, customTitle, headers ->
                            viewModel.playDirectStream(url, customTitle, headers)
                        }
                    )
                }

                // Caixa de Diálogo para Seleção do Modo de Foco
                if (showFocusModeDialog) {
                    FocusModeSelectorDialog(
                        currentMode = preferences.focusMode,
                        onSelectMode = { newMode ->
                            viewModel.updatePreferences { it.copy(focusMode = newMode) }
                            val modeMsg = when (newMode) {
                                FocusMode.NORMAL -> "Navegação Padrão ativada"
                                FocusMode.BACKGROUND -> "Modo Multimédia / Segundo Plano ativado"
                                FocusMode.SPLIT_SCREEN -> "Modo Split-Screen ativado"
                                FocusMode.TOTAL_BLOCK -> "Modo Foco Total (Bloqueio) ativado"
                            }
                            viewModel.showNotification(modeMsg)
                        },
                        onDismiss = { showFocusModeDialog = false }
                    )
                }

                // Caixa de Diálogo para Histórico de Vídeos (Banco de Dados Room)
                if (showVideoHistoryDialog) {
                    val videoHistoryList by viewModel.videoHistory.collectAsStateWithLifecycle()
                    VideoHistoryDialog(
                        videoHistory = videoHistoryList,
                        onPlayVideo = { item ->
                            viewModel.playVideoFromHistory(item)
                        },
                        onDeleteItem = { id ->
                            viewModel.deleteVideoHistoryEntry(id)
                        },
                        onClearAll = {
                            viewModel.clearVideoHistory()
                        },
                        onDismiss = { showVideoHistoryDialog = false },
                        onShowNotification = { msg -> viewModel.showNotification(msg) }
                    )
                }

                // Native Media Player Modal (Media3 / ExoPlayer com suporte a streaming, arquivos locais e PiP)
                activeMediaPlayback?.let { request ->
                    com.example.media.NativeMediaPlayerModal(
                        request = request,
                        onProgressUpdate = { posMs, durMs ->
                            viewModel.updateVideoPlaybackProgress(request.uri.toString(), posMs, durMs)
                        },
                        onDismiss = { viewModel.dismissMediaPlayback() },
                        onOpenExternal = {
                            if (request.sourceDownloadItem != null) {
                                viewModel.openDownloadedFile(context, request.sourceDownloadItem)
                            }
                        }
                    )
                }

                // Gerador e Leitor de QR Code Offline
                if (showQrDialog) {
                    val currentUrl = activeTab?.url?.takeIf { it.isNotBlank() && it != "about:blank" } ?: ""
                    QrCodeManagerDialog(
                        initialText = currentUrl,
                        onDismiss = { showQrDialog = false },
                        onOpenUrl = { url ->
                            if (activeTab != null) {
                                viewModel.loadUrl(activeTab, url)
                            }
                        }
                    )
                }
            }
        }
    }
}

