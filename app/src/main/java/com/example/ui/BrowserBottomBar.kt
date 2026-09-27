package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val CyanBrand = Color(0xFF00BCD4)
private val CyanBright = Color(0xFF00E5FF)

/**
 * Modern Sleek Bottom Navigation Bar for Focus Browser
 * Matching the exact visual design requested:
 * [ <- ]   [ -> ]   [ ⌂ ]   [ ⬇ (count) ]   [ + ]
 */
@Composable
fun BrowserBottomBar(
    canGoBack: Boolean,
    canGoForward: Boolean,
    extractedVideoCount: Int,
    isPrivate: Boolean = false,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onHome: () -> Unit,
    onOpenDownloadsOrMedia: () -> Unit,
    onNewTab: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val navBarBottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val safeBottomInset = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
    val effectiveBottomInset = maxOf(navBarBottomInset, safeBottomInset)
    val bottomPadding = if (effectiveBottomInset > 0.dp) effectiveBottomInset else 4.dp

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
        color = if (isPrivate) Color(0xFF14131F) else Color(0xFF0D1117),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(
            0.8.dp,
            if (isPrivate) Color(0xFF7C4DFF).copy(alpha = 0.25f) else Color(0xFF30363D).copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 8.dp,
                    end = 8.dp,
                    top = 4.dp,
                    bottom = bottomPadding + 2.dp
                ),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. Back Arrow Button [ <- ]
            IconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onBack()
                },
                enabled = canGoBack,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("bottom_nav_back")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Voltar",
                    tint = if (canGoBack) Color.White else Color.White.copy(alpha = 0.3f),
                    modifier = Modifier.size(22.dp)
                )
            }

            // 2. Forward Arrow Button [ -> ]
            IconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onForward()
                },
                enabled = canGoForward,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("bottom_nav_forward")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Avançar",
                    tint = if (canGoForward) Color.White else Color.White.copy(alpha = 0.3f),
                    modifier = Modifier.size(22.dp)
                )
            }

            // 3. Home Button [ ⌂ ]
            IconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onHome()
                },
                modifier = Modifier
                    .size(48.dp)
                    .testTag("bottom_nav_home")
            ) {
                Icon(
                    imageVector = Icons.Default.Home,
                    contentDescription = "Página Inicial",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }

            // 4. Media Extractor / Downloads Badge Button [ ⬇ (count) ]
            IconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onOpenDownloadsOrMedia()
                },
                modifier = Modifier
                    .size(48.dp)
                    .testTag("bottom_nav_media_download")
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(36.dp)
                ) {
                    if (extractedVideoCount > 0) {
                        // Styled cyan badge shape with count matching user screenshot
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = CyanBrand,
                            shadowElevation = 2.dp,
                            modifier = Modifier
                                .defaultMinSize(minWidth = 26.dp, minHeight = 22.dp)
                                .wrapContentSize()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ArrowDownward,
                                    contentDescription = null,
                                    tint = Color.Black,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Text(
                                    text = if (extractedVideoCount > 99) "99+" else extractedVideoCount.toString(),
                                    color = Color.Black,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Black
                                )
                            }
                        }
                    } else {
                        // Standby download icon
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Downloads e Mídias",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            // 5. New Tab Button [ + ]
            IconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onNewTab()
                },
                modifier = Modifier
                    .size(48.dp)
                    .testTag("bottom_nav_new_tab")
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Nova Guia",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}
