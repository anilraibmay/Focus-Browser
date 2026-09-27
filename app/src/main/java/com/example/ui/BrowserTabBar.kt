package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.BrowserTab

@Composable
fun BrowserTabBar(
    tabs: List<BrowserTab>,
    activeTabId: String,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onNewTab: () -> Unit,
    onSyncTabs: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Horizontal scroll list of tabs
        LazyRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
        ) {
            items(tabs) { tab ->
                val isActive = tab.id == activeTabId
                val isBackgroundFocusTab = tab.openedInBackground

                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = if (isActive) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(
                        width = if (isActive) 1.5.dp else 1.dp,
                        color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .clickable { onSelectTab(tab.id) }
                        .testTag("tab_item_${tab.id}")
                ) {
                    Row(
                        modifier = Modifier
                            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Status dot / Icon
                        if (isBackgroundFocusTab) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                            )
                        } else {
                            Icon(
                                imageVector = if (tab.url.startsWith("https")) Icons.Default.Lock else Icons.Default.Public,
                                contentDescription = "Security",
                                tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                        }

                        // Stack Title and Last Updated timestamp
                        Column(
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.weight(1f, fill = false)
                        ) {
                            Text(
                                text = tab.title.ifEmpty { "Sem título" },
                                fontSize = 12.sp,
                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                                color = if (isActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 110.dp)
                            )
                            if (tab.lastUpdated != null) {
                                Text(
                                    text = "Sinc: ${tab.lastUpdated}",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Normal,
                                    color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        }

                        // Close Button with minimum 48dp touch target
                        IconButton(
                            onClick = { onCloseTab(tab.id) },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("tab_bar_close_${tab.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Fechar guia",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.width(6.dp))

        // Sync Background Tabs Button (48dp touch target)
        IconButton(
            onClick = onSyncTabs,
            modifier = Modifier
                .size(48.dp)
                .testTag("tab_bar_sync_button")
        ) {
            Icon(
                imageVector = Icons.Default.Sync,
                contentDescription = "Sincronizar abas",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }

        // Add Tab Button (48dp touch target)
        IconButton(
            onClick = onNewTab,
            modifier = Modifier
                .size(48.dp)
                .testTag("tab_bar_new_tab_button")
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Nova Guia",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}
