package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.BrowserTab
import com.example.model.PermissionPolicy
import com.example.model.SitePermissionRecord
import com.example.model.WebPermissionType
import com.example.util.SitePermissionsManager
import com.example.viewmodel.BrowserViewModel

/**
 * Informações da página e definições de permissão por site (Lock/Shield no topo).
 */
@Composable
fun SiteInfoBottomSheet(
    activeTab: BrowserTab,
    viewModel: BrowserViewModel,
    onDismiss: () -> Unit
) {
    val origin = remember(activeTab.url) {
        SitePermissionsManager.extractOrigin(activeTab.url) ?: activeTab.url
    }
    val isSecure = remember(origin) {
        SitePermissionsManager.isOriginSecure(origin)
    }

    val sitePermissionsMap by viewModel.sitePermissions.collectAsState()
    val record = sitePermissionsMap[origin] ?: SitePermissionRecord(origin = origin)
    var wasModified by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isSecure) Icons.Default.Lock else Icons.Default.LockOpen,
                    contentDescription = null,
                    tint = if (isSecure) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = if (isSecure) "Ligação Segura" else "Não Segura",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Text(
                        text = origin,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = if (isSecure)
                                "As informações enviadas (senhas, dados) são privadas e protegidas por HTTPS."
                            else
                                "Esta ligação não utiliza criptografia HTTPS. Não insira senhas ou dados sensíveis.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 15.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "As permissões abaixo aplicam-se apenas a este site.",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                            lineHeight = 15.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "Permissões deste website:",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Camera Policy Row
                SitePermissionSelectorRow(
                    icon = Icons.Default.CameraAlt,
                    title = "Câmara",
                    currentPolicy = record.camera,
                    onPolicySelected = { policy ->
                        viewModel.setSitePermission(origin, WebPermissionType.CAMERA, policy)
                        wasModified = true
                    }
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Microphone Policy Row
                SitePermissionSelectorRow(
                    icon = Icons.Default.Mic,
                    title = "Microfone",
                    currentPolicy = record.microphone,
                    onPolicySelected = { policy ->
                        viewModel.setSitePermission(origin, WebPermissionType.MICROPHONE, policy)
                        wasModified = true
                    }
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Location Policy Row
                SitePermissionSelectorRow(
                    icon = Icons.Default.LocationOn,
                    title = "Localização",
                    currentPolicy = record.location,
                    onPolicySelected = { policy ->
                        viewModel.setSitePermission(origin, WebPermissionType.LOCATION, policy)
                        wasModified = true
                    }
                )

                if (record.hasAnyExplicitPolicy) {
                    Spacer(modifier = Modifier.height(10.dp))
                    TextButton(
                        onClick = {
                            viewModel.removeSitePermissions(origin)
                            wasModified = true
                        },
                        modifier = Modifier
                            .align(Alignment.End)
                            .defaultMinSize(minHeight = 48.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Repor padrão deste site", fontSize = 12.sp)
                    }
                }

                if (wasModified) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Dica: Recarregue a página para aplicar as alterações de permissões.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (wasModified) {
                Button(
                    onClick = {
                        onDismiss()
                        viewModel.refresh(activeTab)
                    },
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Recarregar Página")
                }
            } else {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                ) {
                    Text("OK")
                }
            }
        },
        dismissButton = {
            if (wasModified) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                ) {
                    Text("Fechar")
                }
            }
        }
    )
}

@Composable
fun SitePermissionSelectorRow(
    icon: ImageVector,
    title: String,
    currentPolicy: PermissionPolicy,
    onPolicySelected: (PermissionPolicy) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )

            // Segmented Policy Buttons with 48dp minimum touch target
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PermissionPolicy.entries.forEach { policy ->
                    val isSelected = currentPolicy == policy
                    val buttonColor = when (policy) {
                        PermissionPolicy.ALLOW -> if (isSelected) Color(0xFF2E7D32) else Color.Transparent
                        PermissionPolicy.BLOCK -> if (isSelected) MaterialTheme.colorScheme.error else Color.Transparent
                        PermissionPolicy.ASK -> if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
                    }
                    val textColor = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = buttonColor,
                        border = if (!isSelected) BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)) else null,
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .clickable { onPolicySelected(policy) }
                            .testTag("policy_${policy.name.lowercase()}")
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 8.dp, vertical = 8.dp)
                                .align(Alignment.CenterVertically),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = when (policy) {
                                    PermissionPolicy.ASK -> "Perguntar"
                                    PermissionPolicy.ALLOW -> "Permitir"
                                    PermissionPolicy.BLOCK -> "Bloquear"
                                },
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = textColor
                            )
                        }
                    }
                }
            }
        }
    }
}
