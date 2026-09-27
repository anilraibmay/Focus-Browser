package com.example.ui

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun FullScreenVideoContainer(fullscreenView: View, onExit: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    
    // Read initial screen brightness safely from System settings
    val initialBrightness = remember {
        val currentSysBrightness = try {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (e: Exception) {
            128
        }
        currentSysBrightness / 255f
    }
    
    var brightness by remember { mutableStateOf(initialBrightness) }
    
    // System volume reading & control
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager }
    val maxVolume = remember { audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var volume by remember { mutableStateOf(audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) / maxVolume.toFloat()) }
    
    // Dynamic overlay indicator types
    var activeIndicator by remember { mutableStateOf<String?>(null) } // "brightness" or "volume"
    var showIndicator by remember { mutableStateOf(false) }
    var indicatorTimerJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    
    // Controls visibility state ("X" button)
    var showControls by remember { mutableStateOf(true) }
    
    val scope = rememberCoroutineScope()
    
    // Auto-hide controls after 4 seconds of inactivity
    LaunchedEffect(showControls) {
        if (showControls) {
            delay(4000)
            showControls = false
        }
    }
    
    // Smoothly apply brightness modifications through the Activity main Window attributes
    LaunchedEffect(brightness) {
        activity?.window?.let { window ->
            val layoutParams = window.attributes
            layoutParams.screenBrightness = brightness.coerceIn(0.01f, 1.0f)
            window.attributes = layoutParams
        }
    }
    
    // Enforce SCREEN_ON flags to ensure uninterrupted streams and hide system UI
    DisposableEffect(Unit) {
        val window = activity?.window
        val insetsController = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        insetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController?.hide(WindowInsetsCompat.Type.systemBars())
        
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            // Restore native user default brightness setup on exiting video stream
            activity?.window?.let { w ->
                val layoutParams = w.attributes
                layoutParams.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                w.attributes = layoutParams
            }
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        if (event.type == androidx.compose.ui.input.pointer.PointerEventType.Press) {
                            showControls = true
                        }
                    }
                }
            }
    ) {
        // Embed the actual fullscreen player View
        AndroidView(
            factory = { ctx ->
                FrameLayout(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    (fullscreenView.parent as? ViewGroup)?.removeView(fullscreenView)
                    addView(fullscreenView)
                    (ctx as? Activity)?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }
            },
            modifier = Modifier.fillMaxSize(),
            onRelease = {
                (it.context as? Activity)?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        )
        
        // Touch detector over the left 60.dp side strip for volume
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .windowInsetsPadding(WindowInsets.displayCutout)
                .width(60.dp)
                .fillMaxHeight()
                .pointerInput(maxVolume) {
                    detectVerticalDragGestures(
                        onDragStart = { 
                            activeIndicator = "volume"
                            showIndicator = true 
                            showControls = true
                        },
                        onDragEnd = {
                            indicatorTimerJob?.cancel()
                            indicatorTimerJob = scope.launch {
                                delay(2000)
                                showIndicator = false
                                activeIndicator = null
                            }
                        },
                        onDragCancel = { 
                            showIndicator = false 
                            activeIndicator = null
                        },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            val sensitivity = 0.003f
                            volume = (volume - dragAmount * sensitivity).coerceIn(0f, 1f)
                            audioManager.setStreamVolume(
                                android.media.AudioManager.STREAM_MUSIC,
                                (volume * maxVolume).toInt(),
                                0
                            )
                            activeIndicator = "volume"
                            showIndicator = true
                            indicatorTimerJob?.cancel()
                        }
                    )
                }
        )
        
        // Touch detector over the right 60.dp side strip for brightness
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .windowInsetsPadding(WindowInsets.displayCutout)
                .width(60.dp)
                .fillMaxHeight()
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { 
                            activeIndicator = "brightness"
                            showIndicator = true 
                            showControls = true
                        },
                        onDragEnd = {
                            indicatorTimerJob?.cancel()
                            indicatorTimerJob = scope.launch {
                                delay(2000)
                                showIndicator = false
                                activeIndicator = null
                            }
                        },
                        onDragCancel = { 
                            showIndicator = false 
                            activeIndicator = null
                        },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            val sensitivity = 0.003f
                            brightness = (brightness - dragAmount * sensitivity).coerceIn(0.01f, 1.0f)
                            activeIndicator = "brightness"
                            showIndicator = true
                            indicatorTimerJob?.cancel()
                        }
                    )
                }
        )
        
        // Center HUD overlay card
        AnimatedVisibility(
            visible = showIndicator && activeIndicator != null,
            enter = fadeIn(animationSpec = tween(300)),
            exit = fadeOut(animationSpec = tween(300)),
            modifier = Modifier
                .align(Alignment.Center)
                .padding(24.dp)
        ) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color.Black.copy(alpha = 0.82f)
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .width(220.dp)
                    .height(64.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isVol = activeIndicator == "volume"
                    Icon(
                        imageVector = if (isVol) Icons.AutoMirrored.Filled.VolumeUp else Icons.Default.WbSunny,
                        contentDescription = if (isVol) "Volume de Mídia" else "Brilho da Tela",
                        tint = if (isVol) Color(0xFF29B6F6) else Color(0xFFFFD54F),
                        modifier = Modifier.size(24.dp)
                    )
                    
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isVol) "Volume" else "Luminosidade",
                                fontSize = 11.sp,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            val displayPct = if (isVol) (volume * 100).toInt() else (brightness * 100).toInt()
                            Text(
                                text = "$displayPct%",
                                fontSize = 11.sp,
                                color = Color.White.copy(alpha = 0.85f),
                                fontWeight = FontWeight.Bold
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(6.dp))
                        
                        LinearProgressIndicator(
                            progress = { if (isVol) volume else brightness },
                            color = if (isVol) Color(0xFF29B6F6) else MaterialTheme.colorScheme.primary,
                            trackColor = Color.White.copy(alpha = 0.2f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                        )
                    }
                }
            }
        }
        
        // Top right close "X" button to escape fullscreen mode (48dp touch target)
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(animationSpec = tween(250)),
            exit = fadeOut(animationSpec = tween(250)),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(16.dp)
        ) {
            IconButton(
                onClick = { onExit() },
                modifier = Modifier
                    .size(48.dp)
                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                    .testTag("exit_fullscreen_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Sair do Fullscreen",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}
