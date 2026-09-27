package com.example.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.DownloadPayload
import com.example.util.DownloadStorageHelper

@Composable
fun DownloadApprovalDialog(
    payload: DownloadPayload,
    defaultDirectoryName: String = "Downloads (Padrão)",
    defaultDirectoryUri: String? = null,
    onConfirm: (Context, DownloadPayload) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    
    val initialEstimatedName = remember(payload) {
        if (!payload.customFileName.isNullOrBlank()) {
            payload.customFileName
        } else {
            android.webkit.URLUtil.guessFileName(payload.url, payload.contentDisposition, payload.mimeType) ?: "ficheiro"
        }
    }

    var editedFileName by remember(payload) { mutableStateOf(initialEstimatedName) }
    var targetFolderUri by remember(payload, defaultDirectoryUri) { 
        mutableStateOf(payload.targetTreeUri ?: defaultDirectoryUri) 
    }
    var targetFolderName by remember(payload, defaultDirectoryName) { 
        mutableStateOf(payload.targetDirectoryName ?: defaultDirectoryName) 
    }

    var showFolderSelector by remember { mutableStateOf(false) }

    val openFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: Exception) {}

            val friendlyName = DownloadStorageHelper.getDisplayName(context, uri.toString())
            targetFolderUri = uri.toString()
            targetFolderName = friendlyName
        }
    }

    val extension = if (editedFileName.contains('.')) editedFileName.substringAfterLast('.').lowercase() else ""
    val isDangerous = extension in listOf("apk", "bin", "exe", "js", "sh", "bat", "msi")

    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isDangerous) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Aviso de Segurança",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
                Text(
                    text = if (isDangerous) "Transferência Perigosa" else "Confirmar Transferência",
                    color = if (isDangerous) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Escolha o nome e a pasta de destino para guardar o ficheiro:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Editable File Name
                OutlinedTextField(
                    value = editedFileName,
                    onValueChange = { editedFileName = it },
                    label = { Text("Nome do Ficheiro") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Destination Folder Selector Card
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showFolderSelector = true }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = "Pasta",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Guardar em:",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = targetFolderName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        TextButton(
                            onClick = { showFolderSelector = true },
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text("Alterar", fontSize = 12.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Domain/Origin Information
                val host = try {
                    java.net.URL(payload.url).host.ifBlank { "Origem Direta" }
                } catch (_: Exception) {
                    "Origem Direta"
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Origem: ",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = host,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (isDangerous) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Alerta: Ficheiros com extensão (.$extension) podem conter código executável. Só prossiga se confiar plenamente no website.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val finalFileName = editedFileName.trim().ifEmpty { initialEstimatedName }
                    val finalPayload = payload.copy(
                        customFileName = finalFileName,
                        targetDirectoryName = targetFolderName,
                        targetTreeUri = targetFolderUri
                    )
                    onConfirm(context, finalPayload)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isDangerous) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            ) {
                Text("Transferir")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onCancel) {
                Text("Cancelar")
            }
        }
    )

    if (showFolderSelector) {
        val presets = remember { DownloadStorageHelper.getStandardPresets() }
        AlertDialog(
            onDismissRequest = { showFolderSelector = false },
            title = {
                Text("Escolher Pasta de Destino", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(presets) { preset ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    targetFolderName = preset.name
                                    targetFolderUri = preset.uriOrPath
                                    showFolderSelector = false
                                }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = when (preset.id) {
                                    "documents" -> Icons.Default.Description
                                    "movies" -> Icons.Default.Movie
                                    "music" -> Icons.Default.MusicNote
                                    "pictures" -> Icons.Default.Image
                                    else -> Icons.Default.Folder
                                },
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = preset.name,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = preset.description,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    }

                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showFolderSelector = false
                                    openFolderLauncher.launch(null)
                                }
                                .padding(vertical = 12.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.CreateNewFolder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Escolher outra pasta no dispositivo...",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                                Text(
                                    text = "Abrir explorador do sistema para selecionar qualquer pasta",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showFolderSelector = false }) {
                    Text("Fechar")
                }
            }
        )
    }
}

// Backward-compatibility overload
@Composable
fun DownloadApprovalDialog(
    payload: DownloadPayload,
    onConfirm: (Context) -> Unit,
    onCancel: () -> Unit
) {
    DownloadApprovalDialog(
        payload = payload,
        onConfirm = { ctx, _ -> onConfirm(ctx) },
        onCancel = onCancel
    )
}
