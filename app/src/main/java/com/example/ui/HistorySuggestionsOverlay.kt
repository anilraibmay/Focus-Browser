package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.database.HistoryEntity
import java.net.URI
import java.net.URLEncoder

/**
 * Privacy-friendly interactive search/autocomplete dropdown matching Chrome Omnibox.
 *
 * PRIVACY DESIGN:
 * - When query is empty: Does NOT display any browsing history, safeguarding the user's private records.
 * - When user types (query >= 1 char): Dynamically searches history matches against title/URL/domain
 *   and provides immediate Chrome-like search and completion suggestions.
 */
@Composable
fun HistorySuggestionsOverlay(
    query: String,
    history: List<HistoryEntity>,
    onSelectUrl: (String) -> Unit,
    onInsertUrl: (String) -> Unit,
    onDeleteHistory: (Long) -> Unit,
    onDismiss: () -> Unit,
    onPlayStream: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val trimmedQuery = query.trim()

    // STRICT PRIVACY: If the user hasn't typed anything yet, never expose private history
    if (trimmedQuery.isEmpty()) {
        return
    }

    // Filter unique matching history entries
    val matchingHistory = remember(trimmedQuery, history) {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<HistoryEntity>()

        for (item in history) {
            val normalizedUrl = item.url.trim().lowercase().removeSuffix("/")
            if (normalizedUrl.isEmpty()) continue

            val matches = item.title.contains(trimmedQuery, ignoreCase = true) ||
                    item.url.contains(trimmedQuery, ignoreCase = true)

            if (matches && seen.add(normalizedUrl)) {
                result.add(item)
                if (result.size >= 6) break
            }
        }
        result
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(onClick = onDismiss)
            .testTag("history_suggestions_overlay")
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .clickable(enabled = false) {}, // Consume click inside panel
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            shadowElevation = 8.dp,
            shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                // Direct Stream Candidate (HLS .m3u8, DASH .mpd, MP4, etc.)
                val detectedStream = com.example.util.StreamUrlHelper.detectStreamType(trimmedQuery)
                if (detectedStream != null) {
                    val streamTitle = "▶ Reproduzir no Reprodutor Nativo (${detectedStream.badge})"
                    Surface(
                        color = Color(0xFFE53935).copy(alpha = 0.12f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        SuggestionRow(
                            icon = Icons.Default.PlayCircle,
                            title = streamTitle,
                            subtitle = trimmedQuery,
                            highlightQuery = "",
                            onClick = {
                                if (onPlayStream != null) {
                                    onPlayStream(trimmedQuery)
                                } else {
                                    onSelectUrl(trimmedQuery)
                                }
                            },
                            onInsert = { onInsertUrl(trimmedQuery) }
                        )
                    }
                    HorizontalDivider(color = Color(0xFFE53935).copy(alpha = 0.35f))
                }

                // Direct Web URL candidate (if user typed something looking like a domain/url)
                val isLikelyUrl = trimmedQuery.contains(".") && !trimmedQuery.contains(" ")
                val directUrl = if (isLikelyUrl) {
                    if (!trimmedQuery.startsWith("http://") && !trimmedQuery.startsWith("https://")) {
                        "https://$trimmedQuery"
                    } else {
                        trimmedQuery
                    }
                } else null

                if (directUrl != null) {
                    SuggestionRow(
                        icon = Icons.Default.Language,
                        title = directUrl,
                        subtitle = "Ir diretamente para o site",
                        highlightQuery = trimmedQuery,
                        onClick = { onSelectUrl(directUrl) },
                        onInsert = { onInsertUrl(directUrl) }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }

                // Default Search Engine Action (Google Search)
                val searchUrl = "https://www.google.com/search?q=" + try {
                    URLEncoder.encode(trimmedQuery, "UTF-8")
                } catch (e: Exception) {
                    trimmedQuery
                }

                SuggestionRow(
                    icon = Icons.Default.Search,
                    title = trimmedQuery,
                    subtitle = "Pesquisar no Google",
                    highlightQuery = trimmedQuery,
                    onClick = { onSelectUrl(searchUrl) },
                    onInsert = { onInsertUrl(trimmedQuery) }
                )

                if (matchingHistory.isNotEmpty()) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "SUGESTÕES DO HISTÓRICO",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            letterSpacing = 0.8.sp
                        )
                    }

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 340.dp)
                    ) {
                        items(matchingHistory, key = { it.id }) { item ->
                            HistorySuggestionItem(
                                item = item,
                                highlightQuery = trimmedQuery,
                                onClick = { onSelectUrl(item.url) },
                                onInsert = { onInsertUrl(item.url) },
                                onDelete = { onDeleteHistory(item.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    highlightQuery: String,
    onClick: () -> Unit,
    onInsert: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = highlightText(title, highlightQuery, MaterialTheme.colorScheme.primary),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        IconButton(
            onClick = onInsert,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.Default.NorthWest,
                contentDescription = "Preencher na barra",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun HistorySuggestionItem(
    item: HistoryEntity,
    highlightQuery: String,
    onClick: () -> Unit,
    onInsert: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("history_suggestion_${item.id}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.History,
                contentDescription = "Histórico",
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = highlightText(
                    text = item.title.ifEmpty { item.url },
                    query = highlightQuery,
                    highlightColor = MaterialTheme.colorScheme.primary
                ),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = highlightText(
                    text = item.url,
                    query = highlightQuery,
                    highlightColor = MaterialTheme.colorScheme.primary
                ),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onInsert,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.NorthWest,
                    contentDescription = "Preencher URL",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Remover do histórico",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/**
 * Builds an AnnotatedString highlighting characters matching the typed query.
 */
private fun highlightText(text: String, query: String, highlightColor: Color): AnnotatedString {
    if (query.isEmpty()) return AnnotatedString(text)

    val startIndex = text.indexOf(query, ignoreCase = true)
    if (startIndex == -1) return AnnotatedString(text)

    return buildAnnotatedString {
        append(text.substring(0, startIndex))
        pushStyle(SpanStyle(fontWeight = FontWeight.Bold, color = highlightColor))
        append(text.substring(startIndex, startIndex + query.length))
        pop()
        append(text.substring(startIndex + query.length))
    }
}
