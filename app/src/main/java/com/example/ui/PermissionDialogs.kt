package com.example.ui

import android.Manifest
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import com.example.util.AppPermissionsHelper
import com.example.util.RevokedPermissionAlert
import com.example.util.WebGeolocationRequestEvent
import com.example.util.WebPermissionRequestEvent
import com.example.viewmodel.BrowserViewModel

/**
 * Diálogo de permissão para pedidos WebRTC (Câmara / Microfone).
 */
@Composable
fun WebMediaPermissionPromptDialog(
    requestEvent: WebPermissionRequestEvent,
    viewModel: BrowserViewModel
) {
    val context = LocalContext.current
    val activity = context as? Activity
    var rememberChoice by remember { mutableStateOf(true) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            viewModel.grantPendingWebPermissionRequest(remember = rememberChoice)
        } else {
            val anyPermanentlyDenied = requestEvent.requiredAndroidPermissions.any { perm ->
                AppPermissionsHelper.isPermanentlyDenied(activity, perm)
            }
            if (anyPermanentlyDenied) {
                viewModel.showRevokedPermissionAlert(
                    RevokedPermissionAlert(
                        permissionTitle = "Câmara / Microfone Desativados",
                        rationale = "O acesso à câmara ou ao microfone está desativado nas definições do dispositivo. Reative a permissão para permitir a utilização neste site.",
                        origin = requestEvent.origin
                    )
                )
            }
            viewModel.clearPendingWebPermissionRequest(rememberBlock = rememberChoice)
        }
    }

    AlertDialog(
        onDismissRequest = { viewModel.clearPendingWebPermissionRequest(rememberBlock = false) },
        icon = {
            Icon(
                imageVector = Icons.Default.Security,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = "Permissão de Multimédia",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Este site solicita autorização para aceder aos seus dispositivos:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = requestEvent.origin,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Acesso restrito exclusivamente a este site",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Recursos solicitados:",
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (requestEvent.requiredAndroidPermissions.contains(Manifest.permission.CAMERA)) {
                        FilterChip(
                            selected = true,
                            onClick = {},
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                            label = { Text("Câmara", fontSize = 12.sp) },
                            leadingIcon = {
                                Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                        )
                    }
                    if (requestEvent.requiredAndroidPermissions.contains(Manifest.permission.RECORD_AUDIO)) {
                        FilterChip(
                            selected = true,
                            onClick = {},
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                            label = { Text("Microfone", fontSize = 12.sp) },
                            leadingIcon = {
                                Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .clickable { rememberChoice = !rememberChoice },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = rememberChoice,
                        onCheckedChange = { rememberChoice = it }
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Lembrar a minha escolha para este site",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val missing = requestEvent.requiredAndroidPermissions.filter { perm ->
                        androidx.core.content.ContextCompat.checkSelfPermission(context, perm) !=
                                android.content.pm.PackageManager.PERMISSION_GRANTED
                    }
                    if (missing.isNotEmpty()) {
                        permissionLauncher.launch(missing.toTypedArray())
                    } else {
                        viewModel.grantPendingWebPermissionRequest(remember = rememberChoice)
                    }
                },
                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
            ) {
                Text("Permitir")
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = { viewModel.clearPendingWebPermissionRequest(rememberBlock = rememberChoice) },
                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
            ) {
                Text("Bloquear")
            }
        }
    )
}

/**
 * Diálogo de permissão para Geolocation HTML5.
 */
@Composable
fun WebGeolocationPromptDialog(
    requestEvent: WebGeolocationRequestEvent,
    viewModel: BrowserViewModel
) {
    val context = LocalContext.current
    val activity = context as? Activity
    var rememberChoice by remember { mutableStateOf(true) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.grantPendingGeolocationRequest(remember = rememberChoice)
        } else {
            if (AppPermissionsHelper.isPermanentlyDenied(activity, Manifest.permission.ACCESS_FINE_LOCATION)) {
                viewModel.showRevokedPermissionAlert(
                    RevokedPermissionAlert(
                        permissionTitle = "Localização do Dispositivo Desativada",
                        rationale = "A permissão de localização está desativada no Android. Para que este site possa obter a sua localização, reative nas definições do sistema.",
                        origin = requestEvent.origin
                    )
                )
            }
            viewModel.clearPendingGeolocationRequest(rememberBlock = rememberChoice)
        }
    }

    AlertDialog(
        onDismissRequest = { viewModel.clearPendingGeolocationRequest(rememberBlock = false) },
        icon = {
            Icon(
                imageVector = Icons.Default.LocationOn,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = "Partilhar Localização?",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "O seguinte site solicita acesso à sua localização geográfica atual:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = requestEvent.origin,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Acesso restrito exclusivamente a este site",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp)
                        .clickable { rememberChoice = !rememberChoice },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = rememberChoice,
                        onCheckedChange = { rememberChoice = it }
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Lembrar a minha escolha para este site",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val hasLocation = androidx.core.content.ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (!hasLocation) {
                        permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    } else {
                        viewModel.grantPendingGeolocationRequest(remember = rememberChoice)
                    }
                },
                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
            ) {
                Text("Permitir")
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = { viewModel.clearPendingGeolocationRequest(rememberBlock = rememberChoice) },
                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
            ) {
                Text("Bloquear")
            }
        }
    )
}

/**
 * Alerta quando uma permissão do Android foi revogada ou permanentemente negada pelo utilizador.
 */
@Composable
fun RevokedPermissionAlertDialog(
    alert: RevokedPermissionAlert,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = alert.permissionTitle,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = alert.rationale,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!alert.origin.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Site: ${alert.origin}",
                            fontWeight = FontWeight.Medium,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Para voltar a utilizar este recurso, ative a autorização correspondente nas Definições do seu dispositivo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onDismiss()
                    AppPermissionsHelper.openAppSettings(context)
                },
                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
            ) {
                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Abrir Definições")
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
            ) {
                Text("Fechar")
            }
        }
    )
}
