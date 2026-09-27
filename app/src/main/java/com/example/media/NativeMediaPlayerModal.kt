package com.example.media

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.util.Rational
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class GestureDragMode {
    NONE,
    SEEK,
    BRIGHTNESS,
    VOLUME
}

private enum class GestureOverlayType {
    NONE,
    SEEK,
    BRIGHTNESS,
    VOLUME
}

private enum class ScreenOrientationMode(val label: String) {
    SENSOR("Rotação Automática"),
    LANDSCAPE("Modo Paisagem"),
    PORTRAIT("Modo Retrato")
}

@OptIn(UnstableApi::class)
@Composable
fun NativeMediaPlayerModal(
    request: MediaPlayRequest,
    onDismiss: () -> Unit,
    onOpenExternal: (() -> Unit)? = null,
    onProgressUpdate: ((positionMs: Long, durationMs: Long) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val activity = context as? Activity
    val haptic = LocalHapticFeedback.current

    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    val maxVolume = remember(audioManager) {
        audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 15
    }

    val segmentMonitor = remember(request.uri) {
        StreamSegmentMonitorListener(tag = "FocusSegmentMonitor")
    }

    // Criação e ciclo de vida do ExoPlayer
    val exoPlayer = remember(request.uri) {
        val isAbys = com.example.util.AbysDedicatedEngine.isAbysUrl(request.uri.toString())
        val effectiveHeaders = request.headers.toMutableMap()
        val defaultBrowserUa = "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
        if (!effectiveHeaders.containsKey("User-Agent")) {
            effectiveHeaders["User-Agent"] = defaultBrowserUa
        }
        if (isAbys) {
            if (!effectiveHeaders.containsKey("Referer")) {
                val host = request.uri.host
                effectiveHeaders["Referer"] = if (!host.isNullOrBlank()) "https://$host/" else com.example.util.AbysDedicatedEngine.DEFAULT_REFERER
            }
            // Do not force Origin header on media GET requests to prevent HTTP 405 Method Not Allowed
        }
        if (!effectiveHeaders.containsKey("Cookie")) {
            try {
                val cookie = android.webkit.CookieManager.getInstance().getCookie(request.uri.toString())
                if (!cookie.isNullOrBlank()) {
                    effectiveHeaders["Cookie"] = cookie
                }
            } catch (_: Exception) {}
        }

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(effectiveHeaders["User-Agent"] ?: defaultBrowserUa)
            .setConnectTimeoutMs(20000)
            .setReadTimeoutMs(20000)

        if (effectiveHeaders.isNotEmpty()) {
            httpDataSourceFactory.setDefaultRequestProperties(effectiveHeaders)
        }

        val abysDataSourceFactory = com.example.media.AbysDataSourceFactory(
            baseFactory = httpDataSourceFactory,
            context = context
        )

        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(abysDataSourceFactory)

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().apply {
                addAnalyticsListener(segmentMonitor)
                val mediaItemBuilder = MediaItem.Builder().setUri(request.uri)
                val resolvedMimeType = if (isAbys) "video/mp4" else request.mimeType
                if (!resolvedMimeType.isNullOrBlank()) {
                    mediaItemBuilder.setMimeType(resolvedMimeType)
                }
                setMediaItem(mediaItemBuilder.build())
                if (request.initialPositionMs > 0L) {
                    seekTo(request.initialPositionMs)
                }
                prepare()
                playWhenReady = true
            }
    }

    // Telemetria do Monitor de Segmentos (HLS / DASH / ABYS)
    var showSegmentInspector by remember { mutableStateOf(false) }
    val segmentReport by segmentMonitor.reportFlow.collectAsStateWithLifecycle()
    val segmentRecords by segmentMonitor.recordsFlow.collectAsStateWithLifecycle()

    // Estados de Reprodução
    var isPlaying by remember { mutableStateOf(true) }
    var isBuffering by remember { mutableStateOf(true) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var isMuted by remember { mutableStateOf(false) }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }

    // Estados de Interface & Controles
    var controlsVisible by remember { mutableStateOf(true) }
    var isSeeking by remember { mutableStateOf(false) }
    var seekProgress by remember { mutableFloatStateOf(0f) }
    var isFullscreen by remember { mutableStateOf(false) }
    var showSpeedMenu by remember { mutableStateOf(false) }
    var isScreenLocked by remember { mutableStateOf(false) }
    var showLockOverlayTrigger by remember { mutableStateOf(false) }
    var currentOrientationMode by remember { mutableStateOf(ScreenOrientationMode.SENSOR) }
    var orientationNoticeText by remember { mutableStateOf<String?>(null) }

    // Estados dos Gestos na Tela
    var gestureDragMode by remember { mutableStateOf(GestureDragMode.NONE) }
    var gestureOverlayType by remember { mutableStateOf(GestureOverlayType.NONE) }
    var gestureSeekTargetMs by remember { mutableLongStateOf(0L) }
    var gestureSeekDeltaSec by remember { mutableIntStateOf(0) }
    var gestureBrightnessPercent by remember { mutableIntStateOf(50) }
    var gestureVolumePercent by remember { mutableIntStateOf(50) }
    var doubleTapFeedbackText by remember { mutableStateOf<String?>(null) }

    // Rastreamento de Gestos
    var dragStartOffset by remember { mutableStateOf(Offset.Zero) }
    var dragAccumulatedX by remember { mutableFloatStateOf(0f) }
    var dragAccumulatedY by remember { mutableFloatStateOf(0f) }
    var initialSeekPosMs by remember { mutableLongStateOf(0L) }
    var initialVolumeLevel by remember { mutableIntStateOf(0) }
    var initialBrightnessVal by remember { mutableFloatStateOf(0.5f) }

    // Status do Sistema: Relógio em Tempo Real
    var currentTimeString by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        while (true) {
            currentTimeString = sdf.format(Date())
            delay(1000L)
        }
    }

    // Status do Sistema: Bateria
    var batteryPercentage by remember { mutableIntStateOf(100) }
    var isBatteryCharging by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            try {
                val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                val batteryStatus = context.registerReceiver(null, ifilter)
                val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                isBatteryCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                if (level >= 0 && scale > 0) {
                    batteryPercentage = (level * 100 / scale)
                }
            } catch (_: Exception) {}
            delay(15000L)
        }
    }

    // Função para alterar brilho da janela da Activity
    val applyBrightness: (Float) -> Unit = { value ->
        val clamped = value.coerceIn(0.01f, 1.0f)
        activity?.window?.let { win ->
            val lp = win.attributes
            lp.screenBrightness = clamped
            win.attributes = lp
        }
        gestureBrightnessPercent = (clamped * 100).roundToInt()
    }

    // Função para ativar/desativar Tela Cheia Imersiva
    val toggleImmersiveMode: (Boolean) -> Unit = { enable ->
        activity?.window?.let { window ->
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            if (enable) {
                insetsController.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                insetsController.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // Listener do ExoPlayer
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = (state == Player.STATE_BUFFERING)
                if (state == Player.STATE_READY) {
                    durationMs = exoPlayer.duration.coerceAtLeast(0L)
                    playbackError = null
                } else if (state == Player.STATE_ENDED) {
                    isPlaying = false
                    val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
                    val dur = exoPlayer.duration.coerceAtLeast(0L)
                    val isAbys = com.example.util.AbysDedicatedEngine.isAbysUrl(request.uri.toString())
                    if (isAbys && dur > 60_000L && pos < (dur * 0.85)) {
                        playbackError = "Fim da Parte 1 inicial (${formatDurationMs(pos)} de ${formatDurationMs(dur)}). O provedor limitou o arquivo .fd aos primeiros 10 MB. As partes subsequentes são protegidas pelo player da página."
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                isBuffering = false
                val cause = error.cause?.message
                val codeName = error.errorCodeName
                val isAbys = com.example.util.AbysDedicatedEngine.isAbysUrl(request.uri.toString())
                val isPartialEof = error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
                        error.errorCode == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE ||
                        cause?.contains("416") == true
                playbackError = when {
                    isAbys && (isPartialEof || currentPositionMs > 5000L) -> {
                        "Fim da Parte 1 disponível (${formatDurationMs(currentPositionMs)} de ${formatDurationMs(durationMs)}). A CDN ABYS entregou apenas a amostra inicial (.fd - 10 MB); os fragmentos subsequentes não foram liberados nesta URL direta."
                    }
                    !cause.isNullOrBlank() -> "$cause ($codeName)"
                    !error.message.isNullOrBlank() -> "${error.message} ($codeName)"
                    else -> "Erro ao reproduzir fluxo de mídia ($codeName)"
                }
            }
        }
        exoPlayer.addListener(listener)

        onDispose {
            try {
                val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
                val dur = exoPlayer.duration.coerceAtLeast(0L)
                if (pos > 0L) {
                    onProgressUpdate?.invoke(pos, dur)
                }
            } catch (_: Exception) {}
            exoPlayer.removeListener(listener)
            exoPlayer.stop()
            exoPlayer.release()
            // Restaura brilho original, barras do sistema e orientação
            toggleImmersiveMode(false)
            activity?.window?.let { win ->
                val lp = win.attributes
                lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                win.attributes = lp
            }
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Atualização contínua do progresso a cada 250ms e persistência a cada 3 segundos
    LaunchedEffect(isPlaying, isSeeking) {
        var lastPersistTimeMs = 0L
        while (isPlaying && !isSeeking) {
            currentPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            val d = exoPlayer.duration
            if (d > 0) durationMs = d
            val now = System.currentTimeMillis()
            if (now - lastPersistTimeMs >= 3000L) {
                lastPersistTimeMs = now
                if (currentPositionMs > 0L) {
                    onProgressUpdate?.invoke(currentPositionMs, durationMs)
                }
            }
            delay(250)
        }
    }

    // Auto-ocultar controles após 4s de inatividade enquanto reproduz
    LaunchedEffect(controlsVisible, isPlaying, isScreenLocked) {
        if (controlsVisible && isPlaying && !isScreenLocked) {
            delay(4000)
            controlsVisible = false
        }
    }

    // Auto-ocultar overlay de bloqueio após 3s
    LaunchedEffect(showLockOverlayTrigger) {
        if (showLockOverlayTrigger) {
            delay(3000)
            showLockOverlayTrigger = false
        }
    }

    // Notificação temporária de orientação
    LaunchedEffect(orientationNoticeText) {
        if (orientationNoticeText != null) {
            delay(2000)
            orientationNoticeText = null
        }
    }

    // Notificação temporária de double tap
    LaunchedEffect(doubleTapFeedbackText) {
        if (doubleTapFeedbackText != null) {
            delay(1200)
            doubleTapFeedbackText = null
        }
    }

    // Detectar modo PiP do Android
    val isActivityInPiP = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        activity?.isInPictureInPictureMode == true
    } else false

    val triggerPiP: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity != null) {
            try {
                val rational = if (exoPlayer.videoSize.width > 0 && exoPlayer.videoSize.height > 0) {
                    val w = exoPlayer.videoSize.width
                    val h = exoPlayer.videoSize.height
                    val ratio = w.toFloat() / h.toFloat()
                    if (ratio in 0.42f..2.38f) Rational(w, h) else Rational(16, 9)
                } else {
                    Rational(16, 9)
                }
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(rational)
                    .build()
                controlsVisible = false
                activity.enterPictureInPictureMode(params)
            } catch (_: Exception) {}
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            val screenWidthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
            val screenHeightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)

            // 1. Superfície de Vídeo (PlayerView)
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = exoPlayer
                        this.useController = false
                        this.resizeMode = resizeMode
                        this.setBackgroundColor(android.graphics.Color.BLACK)
                    }
                },
                update = { pv ->
                    pv.player = exoPlayer
                    pv.resizeMode = resizeMode
                },
                modifier = Modifier.fillMaxSize()
            )

            // 2. Camada Transparente de Gestos na Tela (Touch, Double Tap, Drag)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(isScreenLocked, durationMs, currentPositionMs) {
                        detectTapGestures(
                            onTap = {
                                if (isScreenLocked) {
                                    showLockOverlayTrigger = true
                                } else {
                                    controlsVisible = !controlsVisible
                                }
                            },
                            onDoubleTap = { offset ->
                                if (!isScreenLocked && !request.isAudioOnly) {
                                    if (offset.x < screenWidthPx * 0.35f) {
                                        // Retroceder 10s
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        val target = (exoPlayer.currentPosition - 10000L).coerceAtLeast(0L)
                                        exoPlayer.seekTo(target)
                                        currentPositionMs = target
                                        doubleTapFeedbackText = "-10s"
                                    } else if (offset.x > screenWidthPx * 0.65f) {
                                        // Avançar 10s
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        val target = (exoPlayer.currentPosition + 10000L).coerceAtMost(durationMs)
                                        exoPlayer.seekTo(target)
                                        currentPositionMs = target
                                        doubleTapFeedbackText = "+10s"
                                    } else {
                                        // Centro: alternar Play / Pause
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        if (isPlaying) exoPlayer.pause() else exoPlayer.play()
                                    }
                                }
                            }
                        )
                    }
                    .pointerInput(isScreenLocked, durationMs) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                if (isScreenLocked) {
                                    showLockOverlayTrigger = true
                                    return@detectDragGestures
                                }
                                dragStartOffset = offset
                                dragAccumulatedX = 0f
                                dragAccumulatedY = 0f
                                gestureDragMode = GestureDragMode.NONE
                                initialSeekPosMs = currentPositionMs
                                initialVolumeLevel = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
                                val lp = activity?.window?.attributes
                                initialBrightnessVal = if ((lp?.screenBrightness ?: -1f) < 0f) 0.5f else lp!!.screenBrightness
                            },
                            onDrag = { change, dragAmount ->
                                if (isScreenLocked) return@detectDragGestures
                                change.consume()
                                dragAccumulatedX += dragAmount.x
                                dragAccumulatedY += dragAmount.y

                                // Decisão inicial da direção do gesto
                                if (gestureDragMode == GestureDragMode.NONE) {
                                    if (abs(dragAccumulatedX) > 28f && abs(dragAccumulatedX) > abs(dragAccumulatedY)) {
                                        gestureDragMode = GestureDragMode.SEEK
                                    } else if (abs(dragAccumulatedY) > 28f && abs(dragAccumulatedY) > abs(dragAccumulatedX)) {
                                        gestureDragMode = if (dragStartOffset.x < screenWidthPx * 0.5f) {
                                            GestureDragMode.BRIGHTNESS
                                        } else {
                                            GestureDragMode.VOLUME
                                        }
                                    }
                                }

                                when (gestureDragMode) {
                                    GestureDragMode.SEEK -> {
                                        val fraction = (dragAccumulatedX / screenWidthPx).coerceIn(-1f, 1f)
                                        val seekDeltaMs = (fraction * 120000L).toLong() // até 120 segundos de salto
                                        gestureSeekTargetMs = (initialSeekPosMs + seekDeltaMs).coerceIn(0L, durationMs.coerceAtLeast(1L))
                                        gestureSeekDeltaSec = ((gestureSeekTargetMs - initialSeekPosMs) / 1000).toInt()
                                        gestureOverlayType = GestureOverlayType.SEEK
                                    }
                                    GestureDragMode.BRIGHTNESS -> {
                                        val fractionY = -(dragAccumulatedY / screenHeightPx)
                                        val newBr = (initialBrightnessVal + fractionY * 1.3f).coerceIn(0.01f, 1.0f)
                                        applyBrightness(newBr)
                                        gestureOverlayType = GestureOverlayType.BRIGHTNESS
                                    }
                                    GestureDragMode.VOLUME -> {
                                        val fractionY = -(dragAccumulatedY / screenHeightPx)
                                        val targetVolFrac = ((initialVolumeLevel.toFloat() / maxVolume.toFloat()) + fractionY * 1.3f).coerceIn(0f, 1f)
                                        val newVol = (targetVolFrac * maxVolume).roundToInt().coerceIn(0, maxVolume)
                                        gestureVolumePercent = (targetVolFrac * 100).roundToInt()
                                        audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                                        gestureOverlayType = GestureOverlayType.VOLUME
                                    }
                                    GestureDragMode.NONE -> {}
                                }
                            },
                            onDragEnd = {
                                if (gestureDragMode == GestureDragMode.SEEK) {
                                    exoPlayer.seekTo(gestureSeekTargetMs)
                                    currentPositionMs = gestureSeekTargetMs
                                }
                                gestureDragMode = GestureDragMode.NONE
                                scope.launch {
                                    delay(650)
                                    gestureOverlayType = GestureOverlayType.NONE
                                }
                            },
                            onDragCancel = {
                                gestureDragMode = GestureDragMode.NONE
                                gestureOverlayType = GestureOverlayType.NONE
                            }
                        )
                    }
            )

            // 3. Visualizador especial para Áudio Puro (Sem faixa de vídeo)
            if (request.isAudioOnly) {
                AudioVisualizerOverlay(
                    title = request.title,
                    subtitle = request.subtitle ?: "Fluxo de Áudio Nativo",
                    isPlaying = isPlaying
                )
            }

            // 4. Indicador Central de Gestos (Avançar/Retroceder, Brilho, Som)
            AnimatedVisibility(
                visible = gestureOverlayType != GestureOverlayType.NONE,
                enter = fadeIn(tween(150)) + scaleIn(initialScale = 0.85f),
                exit = fadeOut(tween(250)) + scaleOut(targetScale = 0.85f),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.80f),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                    modifier = Modifier.padding(24.dp)
                ) {
                    when (gestureOverlayType) {
                        GestureOverlayType.SEEK -> {
                            Column(
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val isForward = gestureSeekDeltaSec >= 0
                                Icon(
                                    imageVector = if (isForward) Icons.Default.FastForward else Icons.Default.FastRewind,
                                    contentDescription = null,
                                    tint = if (isForward) Color(0xFF00E676) else Color(0xFFFF5252),
                                    modifier = Modifier.size(44.dp)
                                )
                                Text(
                                    text = "${if (isForward) "+" else ""}${gestureSeekDeltaSec}s",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Black,
                                    color = if (isForward) Color(0xFF00E676) else Color(0xFFFF5252)
                                )
                                Text(
                                    text = "${formatDurationMs(gestureSeekTargetMs)} / ${formatDurationMs(durationMs)}",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color.White
                                )
                            }
                        }
                        GestureOverlayType.BRIGHTNESS -> {
                            Column(
                                modifier = Modifier.padding(horizontal = 28.dp, vertical = 18.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val icon = if (gestureBrightnessPercent > 66) Icons.Default.BrightnessHigh
                                else if (gestureBrightnessPercent > 33) Icons.Default.BrightnessMedium
                                else Icons.Default.BrightnessLow
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    tint = Color(0xFFFFD54F),
                                    modifier = Modifier.size(40.dp)
                                )
                                Text(
                                    text = "Brilho: $gestureBrightnessPercent%",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                LinearProgressIndicator(
                                    progress = { (gestureBrightnessPercent / 100f).coerceIn(0f, 1f) },
                                    modifier = Modifier
                                        .width(100.dp)
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp)),
                                    color = Color(0xFFFFD54F),
                                    trackColor = Color.White.copy(alpha = 0.2f)
                                )
                            }
                        }
                        GestureOverlayType.VOLUME -> {
                            Column(
                                modifier = Modifier.padding(horizontal = 28.dp, vertical = 18.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val icon = if (gestureVolumePercent == 0) Icons.AutoMirrored.Filled.VolumeMute
                                else if (gestureVolumePercent < 50) Icons.AutoMirrored.Filled.VolumeDown
                                else Icons.AutoMirrored.Filled.VolumeUp
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    tint = Color(0xFF00B0FF),
                                    modifier = Modifier.size(40.dp)
                                )
                                Text(
                                    text = "Volume: $gestureVolumePercent%",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                LinearProgressIndicator(
                                    progress = { (gestureVolumePercent / 100f).coerceIn(0f, 1f) },
                                    modifier = Modifier
                                        .width(100.dp)
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp)),
                                    color = Color(0xFF00B0FF),
                                    trackColor = Color.White.copy(alpha = 0.2f)
                                )
                            }
                        }
                        GestureOverlayType.NONE -> {}
                    }
                }
            }

            // 5. Feedback visual de Toque Duplo (+10s / -10s)
            AnimatedVisibility(
                visible = doubleTapFeedbackText != null,
                enter = fadeIn() + scaleIn(initialScale = 0.7f),
                exit = fadeOut() + scaleOut(targetScale = 0.7f),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.75f),
                    shape = CircleShape,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)),
                    modifier = Modifier.size(72.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = doubleTapFeedbackText ?: "",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black,
                            color = Color.White
                        )
                    }
                }
            }

            // 6. Aviso Temporário de Rotação
            AnimatedVisibility(
                visible = orientationNoticeText != null,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 80.dp)
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0xFF00E676).copy(alpha = 0.5f)),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ScreenRotation,
                            contentDescription = null,
                            tint = Color(0xFF00E676),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = orientationNoticeText ?: "",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // 7. Botão Flutuante de Bloqueio/Desbloqueio (Estilo VLC / MX Player)
            AnimatedVisibility(
                visible = (controlsVisible && !isActivityInPiP) || isScreenLocked,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 16.dp)
            ) {
                Surface(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        isScreenLocked = !isScreenLocked
                        if (isScreenLocked) {
                            controlsVisible = false
                            showLockOverlayTrigger = true
                        } else {
                            controlsVisible = true
                            showLockOverlayTrigger = false
                        }
                    },
                    shape = CircleShape,
                    color = if (isScreenLocked) Color(0xFFFF5252).copy(alpha = 0.85f) else Color.Black.copy(alpha = 0.65f),
                    border = BorderStroke(
                        width = 1.5.dp,
                        color = if (isScreenLocked) Color(0xFFFF5252) else Color.White.copy(alpha = 0.4f)
                    ),
                    modifier = Modifier
                        .size(46.dp)
                        .testTag("btn_lock_screen")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isScreenLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = if (isScreenLocked) "Desbloquear controles" else "Bloquear controles",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            // Dica flutuante quando a tela está bloqueada e o usuário clica
            AnimatedVisibility(
                visible = isScreenLocked && showLockOverlayTrigger,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 60.dp)
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0xFFFF5252).copy(alpha = 0.6f))
                ) {
                    Text(
                        text = "🔒 Controles Bloqueados • Toque no cadeado para desbloquear",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }

            // 8. Indicador de Carregamento Central
            if (isBuffering && playbackError == null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.6f),
                        shape = CircleShape,
                        modifier = Modifier.size(64.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 3.dp,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                }
            }

            // 9. Mensagem de Erro com botão de tentar novamente
            if (playbackError != null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .padding(24.dp)
                            .widthIn(max = 380.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(32.dp)
                            )
                            Text(
                                text = "Não foi possível reproduzir o fluxo",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = playbackError ?: "Erro desconhecido",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(top = 4.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { showSegmentInspector = true },
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Ver Fragmentos", fontSize = 12.sp)
                                }
                                if (currentPositionMs > 5000L) {
                                    Button(
                                        onClick = {
                                            playbackError = null
                                            isBuffering = true
                                            exoPlayer.seekTo(0L)
                                            currentPositionMs = 0L
                                            exoPlayer.prepare()
                                            exoPlayer.play()
                                        },
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("Reiniciar", fontSize = 12.sp)
                                    }
                                } else {
                                    Button(
                                        onClick = {
                                            playbackError = null
                                            isBuffering = true
                                            exoPlayer.prepare()
                                            exoPlayer.play()
                                        },
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("Tentar Novamente", fontSize = 12.sp)
                                    }
                                }
                            }
                            if (onOpenExternal != null) {
                                OutlinedButton(
                                    onClick = onOpenExternal,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.padding(top = 2.dp)
                                ) {
                                    Text("Abrir Externamente / Navegador", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }

            // 10. Controles Principais de Sobreposição (Visíveis quando não bloqueado)
            AnimatedVisibility(
                visible = controlsVisible && !isScreenLocked && !isActivityInPiP,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(250))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.85f),
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.90f)
                                )
                            )
                        )
                ) {
                    val statusTopPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                    val navBottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

                    // Top Bar de Controles + Status do Sistema (Hora e Bateria)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = statusTopPadding + 4.dp, start = 12.dp, end = 12.dp)
                    ) {
                        // Linha 1: Status Real do Sistema (Hora e Bateria Integrada)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // Hora Atual em Tempo Real
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AccessTime,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.8f),
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = currentTimeString.ifEmpty { "12:00" },
                                    color = Color.White.copy(alpha = 0.9f),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            // Indicador de Bateria com Porcentagem
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "$batteryPercentage%",
                                    color = if (batteryPercentage <= 15) Color(0xFFFF5252) else Color.White.copy(alpha = 0.9f),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Icon(
                                    imageVector = if (isBatteryCharging) Icons.Default.BatteryChargingFull
                                    else if (batteryPercentage <= 15) Icons.Default.BatteryAlert
                                    else Icons.Default.BatteryFull,
                                    contentDescription = null,
                                    tint = if (isBatteryCharging) Color(0xFF00E676)
                                    else if (batteryPercentage <= 15) Color(0xFFFF5252)
                                    else Color.White.copy(alpha = 0.9f),
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }

                        // Linha 2: Ações do Cabeçalho (Fechar, Título, Rotação, Velocidade, PiP)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier
                                    .size(38.dp)
                                    .testTag("btn_close_player")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Fechar player",
                                    tint = Color.White
                                )
                            }

                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 6.dp)
                            ) {
                                Text(
                                    text = request.title,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Surface(
                                        color = if (request.isAudioOnly) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.85f)
                                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = if (request.isAudioOnly) "ÁUDIO" else "EXOPLAYER",
                                            color = Color.White,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }
                                    if (!request.subtitle.isNullOrBlank()) {
                                        Text(
                                            text = request.subtitle,
                                            color = Color.White.copy(alpha = 0.7f),
                                            fontSize = 11.sp,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }

                            // Botão de Rotação (Sensor -> Paisagem -> Retrato)
                            IconButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    when (currentOrientationMode) {
                                        ScreenOrientationMode.SENSOR -> {
                                            currentOrientationMode = ScreenOrientationMode.LANDSCAPE
                                            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                            orientationNoticeText = "Modo Paisagem Forçado"
                                        }
                                        ScreenOrientationMode.LANDSCAPE -> {
                                            currentOrientationMode = ScreenOrientationMode.PORTRAIT
                                            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                            orientationNoticeText = "Modo Retrato Forçado"
                                        }
                                        ScreenOrientationMode.PORTRAIT -> {
                                            currentOrientationMode = ScreenOrientationMode.SENSOR
                                            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
                                            orientationNoticeText = "Rotação Automática pelo Sensor"
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .size(38.dp)
                                    .testTag("btn_rotate_player")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ScreenRotation,
                                    contentDescription = "Alternar rotação de tela",
                                    tint = if (currentOrientationMode != ScreenOrientationMode.SENSOR) Color(0xFF00E676) else Color.White
                                )
                            }

                            // Menu de Velocidade
                            Box {
                                IconButton(
                                    onClick = { showSpeedMenu = true },
                                    modifier = Modifier.size(38.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Speed,
                                        contentDescription = "Velocidade de reprodução",
                                        tint = Color.White
                                    )
                                }
                                DropdownMenu(
                                    expanded = showSpeedMenu,
                                    onDismissRequest = { showSpeedMenu = false }
                                ) {
                                    listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { speed ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = "${speed}x" + if (playbackSpeed == speed) " ✓" else "",
                                                    fontWeight = if (playbackSpeed == speed) FontWeight.Bold else FontWeight.Normal
                                                )
                                            },
                                            onClick = {
                                                playbackSpeed = speed
                                                exoPlayer.setPlaybackSpeed(speed)
                                                showSpeedMenu = false
                                            }
                                        )
                                    }
                                }
                            }

                            // Proporção de Tela (Fit / Zoom / Stretch)
                            if (!request.isAudioOnly) {
                                IconButton(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        resizeMode = when (resizeMode) {
                                            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                                            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                                        }
                                    },
                                    modifier = Modifier.size(38.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AspectRatio,
                                        contentDescription = "Ajustar proporção de vídeo",
                                        tint = Color.White
                                    )
                                }
                            }

                            // Monitor de Segmentos (HLS / DASH / ABYS)
                            IconButton(
                                onClick = { showSegmentInspector = true },
                                modifier = Modifier
                                    .size(38.dp)
                                    .testTag("stream_segment_monitor_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Layers,
                                    contentDescription = "Monitor de segmentos e fragmentos de rede",
                                    tint = if (segmentReport.status == StreamMappingStatus.MAPPING_VERIFIED) Color(0xFF4ADE80) else Color.White
                                )
                            }

                            // Picture-in-Picture
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                IconButton(
                                    onClick = triggerPiP,
                                    modifier = Modifier.size(38.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PictureInPictureAlt,
                                        contentDescription = "Modo Janela Flutuante (PiP)",
                                        tint = Color.White
                                    )
                                }
                            }

                            // Abrir em App Externo
                            if (onOpenExternal != null) {
                                IconButton(
                                    onClick = onOpenExternal,
                                    modifier = Modifier.size(38.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.OpenInNew,
                                        contentDescription = "Abrir em reprodutor externo",
                                        tint = Color.White
                                    )
                                }
                            }
                        }
                    }

                    // Controles Centrais: Replay 10s | Play/Pause | Forward 10s
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalArrangement = Arrangement.spacedBy(28.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                val target = (exoPlayer.currentPosition - 10000L).coerceAtLeast(0L)
                                exoPlayer.seekTo(target)
                                currentPositionMs = target
                            },
                            modifier = Modifier
                                .size(50.dp)
                                .background(Color.White.copy(alpha = 0.18f), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Replay10,
                                contentDescription = "Recuar 10 segundos",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                if (isPlaying) {
                                    exoPlayer.pause()
                                } else {
                                    if (exoPlayer.playbackState == Player.STATE_ENDED) {
                                        exoPlayer.seekTo(0L)
                                    }
                                    exoPlayer.play()
                                }
                            },
                            modifier = Modifier
                                .size(68.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape)
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pausar" else "Reproduzir",
                                tint = Color.White,
                                modifier = Modifier.size(38.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                val target = (exoPlayer.currentPosition + 10000L).coerceAtMost(durationMs)
                                exoPlayer.seekTo(target)
                                currentPositionMs = target
                            },
                            modifier = Modifier
                                .size(50.dp)
                                .background(Color.White.copy(alpha = 0.18f), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Forward10,
                                contentDescription = "Avançar 10 segundos",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }

                    // Barra Inferior: Progresso, Tempo, Mudo e Alternar Tela Cheia Imersiva
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(bottom = navBottomPadding + 8.dp, start = 16.dp, end = 16.dp)
                    ) {
                        // Slider de Busca com Scrubbing Seguro
                        val sliderPosition = if (isSeeking) seekProgress
                        else if (durationMs > 0) (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                        else 0f

                        Slider(
                            value = sliderPosition,
                            onValueChange = { newVal ->
                                isSeeking = true
                                seekProgress = newVal
                            },
                            onValueChangeFinished = {
                                isSeeking = false
                                if (durationMs > 0) {
                                    val target = (seekProgress * durationMs).toLong()
                                    exoPlayer.seekTo(target)
                                    currentPositionMs = target
                                }
                            },
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(28.dp)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${formatDurationMs(if (isSeeking) (seekProgress * durationMs).toLong() else currentPositionMs)} / ${formatDurationMs(durationMs)}",
                                color = Color.White.copy(alpha = 0.9f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                // Alternar Mudo
                                IconButton(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        isMuted = !isMuted
                                        exoPlayer.volume = if (isMuted) 0f else 1f
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeMute else Icons.AutoMirrored.Filled.VolumeUp,
                                        contentDescription = if (isMuted) "Ativar som" else "Silenciar",
                                        tint = Color.White
                                    )
                                }

                                // Botão de Tela Cheia Imersiva
                                if (!request.isAudioOnly && activity != null) {
                                    IconButton(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            isFullscreen = !isFullscreen
                                            toggleImmersiveMode(isFullscreen)
                                            if (isFullscreen) {
                                                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                                currentOrientationMode = ScreenOrientationMode.LANDSCAPE
                                                orientationNoticeText = "Tela Cheia Imersiva"
                                            } else {
                                                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                                                currentOrientationMode = ScreenOrientationMode.SENSOR
                                            }
                                        },
                                        modifier = Modifier
                                            .size(36.dp)
                                            .testTag("btn_fullscreen_toggle")
                                    ) {
                                        Icon(
                                            imageVector = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                            contentDescription = if (isFullscreen) "Sair de tela cheia" else "Tela cheia imersiva",
                                            tint = Color.White
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showSegmentInspector) {
            StreamSegmentInspectorModal(
                report = segmentReport,
                records = segmentRecords,
                onDismiss = { showSegmentInspector = false }
            )
        }
    }
}

@Composable
private fun AudioVisualizerOverlay(
    title: String,
    subtitle: String,
    isPlaying: Boolean
) {
    val infiniteTransition = rememberInfiniteTransition(label = "audio_anim")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF2A1B4E),
                        Color(0xFF130E26),
                        Color(0xFF090614)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                modifier = Modifier
                    .size(120.dp)
                    .scale(if (isPlaying) pulseScale else 1f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.GraphicEq else Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(56.dp)
                    )
                }
            }

            Text(
                text = title,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )

            Text(
                text = subtitle,
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

private fun formatDurationMs(millis: Long): String {
    if (millis <= 0) return "00:00"
    val totalSeconds = millis / 1000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }
}
