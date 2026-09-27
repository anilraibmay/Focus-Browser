package com.example.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.PermissionPolicy
import com.example.model.SitePermissionRecord
import com.example.model.WebPermissionType
import com.example.util.AppPermissionItem
import com.example.util.AppPermissionsHelper
import com.example.util.SitePermissionsManager
import com.example.viewmodel.BrowserViewModel

/**
 * Diálogo do Centro de Permissões Global (Por Site, Regras Globais, Sistema Android).
 */
@Composable
fun PermissionsCenterDialog(
    viewModel: BrowserViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }

    val preferences by viewModel.preferences.collectAsState()
    val sitePermissionsMap by viewModel.sitePermissions.collectAsState()
    val allSiteRecords = remember(sitePermissionsMap) {
        sitePermissionsMap.values.filter { it.hasAnyExplicitPolicy }.sortedByDescending { it.lastUpdated }
    }

    var siteFilterText by remember { mutableStateOf("") }
    val filteredSites = remember(allSiteRecords, siteFilterText) {
        if (siteFilterText.isBlank()) allSiteRecords
        else allSiteRecords.filter { it.origin.contains(siteFilterText, ignoreCase = true) }
    }

    var showClearAllConfirmation by remember { mutableStateOf(false) }

    var permissionsList by remember {
        mutableStateOf(AppPermissionsHelper.getAppPermissionStatusList(context))
    }
    val requestLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionsList = AppPermissionsHelper.getAppPermissionStatusList(context)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text("Centro de Permissões", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("Controlo por site e regras globais", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Tab Row
                TabRow(
                    selectedTabIndex = selectedTab,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                        text = { Text("Por Site (${allSiteRecords.size})", fontSize = 12.sp, maxLines = 1) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                        text = { Text("Regras Globais", fontSize = 12.sp, maxLines = 1) }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = {
                            selectedTab = 2
                            permissionsList = AppPermissionsHelper.getAppPermissionStatusList(context)
                        },
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                        text = { Text("Sistema", fontSize = 12.sp, maxLines = 1) }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                when (selectedTab) {
                    0 -> {
                        // TAB 0: Per-Site / Origin Permissions
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 380.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            item {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text(
                                            text = "Regras por Site",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.height(3.dp))
                                        Text(
                                            text = "As permissões concedidas aplicam-se individualmente a cada site. Os restantes sites continuam protegidos.",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            lineHeight = 15.sp
                                        )
                                    }
                                }
                            }

                            if (allSiteRecords.isNotEmpty()) {
                                item {
                                    OutlinedTextField(
                                        value = siteFilterText,
                                        onValueChange = { siteFilterText = it },
                                        placeholder = { Text("Filtrar por domínio...", fontSize = 12.sp) },
                                        leadingIcon = {
                                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                                        },
                                        trailingIcon = {
                                            if (siteFilterText.isNotEmpty()) {
                                                IconButton(
                                                    onClick = { siteFilterText = "" },
                                                    modifier = Modifier.size(48.dp)
                                                ) {
                                                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                                                }
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                }
                            }

                            if (allSiteRecords.isEmpty()) {
                                item {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 24.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Icon(
                                                imageVector = Icons.Default.CheckCircleOutline,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                                modifier = Modifier.size(40.dp)
                                            )
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                text = "Nenhum website com exceções guardadas.",
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = "Todos os sites estão a usar as Regras Globais padrão.",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            } else if (filteredSites.isEmpty()) {
                                item {
                                    Text(
                                        text = "Nenhum website corresponde à pesquisa.",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(16.dp)
                                    )
                                }
                            } else {
                                items(filteredSites, key = { it.origin }) { recordItem ->
                                    SiteRecordItemCard(
                                        record = recordItem,
                                        onUpdatePolicy = { type, policy ->
                                            viewModel.setSitePermission(recordItem.origin, type, policy)
                                        },
                                        onRemoveSite = {
                                            viewModel.removeSitePermissions(recordItem.origin)
                                        }
                                    )
                                }

                                item {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    OutlinedButton(
                                        onClick = { showClearAllConfirmation = true },
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = MaterialTheme.colorScheme.error
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .defaultMinSize(minHeight = 48.dp),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Limpar todas as permissões de sites", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }

                    1 -> {
                        // TAB 1: Global Default Policies
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 380.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            item {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text(
                                            text = "Comportamento Padrão para Novos Sites",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "Quando um website solicita acesso pela primeira vez, este comportamento global é adotado:",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            // Global Camera Policy
                            item {
                                GlobalPolicyConfigCard(
                                    icon = Icons.Default.CameraAlt,
                                    title = "Câmara",
                                    description = "Comportamento para pedidos de vídeo/câmara web",
                                    currentPolicy = preferences.defaultCameraPolicy,
                                    onSelectPolicy = { policy ->
                                        viewModel.updatePreferences { it.copy(defaultCameraPolicy = policy) }
                                    }
                                )
                            }

                            // Global Microphone Policy
                            item {
                                GlobalPolicyConfigCard(
                                    icon = Icons.Default.Mic,
                                    title = "Microfone",
                                    description = "Comportamento para captura e gravação de áudio",
                                    currentPolicy = preferences.defaultMicrophonePolicy,
                                    onSelectPolicy = { policy ->
                                        viewModel.updatePreferences { it.copy(defaultMicrophonePolicy = policy) }
                                    }
                                )
                            }

                            // Global Location Policy
                            item {
                                GlobalPolicyConfigCard(
                                    icon = Icons.Default.LocationOn,
                                    title = "Localização",
                                    description = "Comportamento para serviços de geolocalização web",
                                    currentPolicy = preferences.defaultLocationPolicy,
                                    onSelectPolicy = { policy ->
                                        viewModel.updatePreferences { it.copy(defaultLocationPolicy = policy) }
                                    }
                                )
                            }
                        }
                    }

                    2 -> {
                        // TAB 2: Device System Permissions (Android OS)
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 380.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            item {
                                Text(
                                    text = "Permissões do dispositivo necessárias para que os sites autorizados possam aceder à câmara, microfone ou localização:",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 15.sp
                                )
                            }

                            items(permissionsList) { item ->
                                PermissionItemCard(
                                    item = item,
                                    onRequest = {
                                        requestLauncher.launch(item.permissions.toTypedArray())
                                    },
                                    onOpenSettings = {
                                        AppPermissionsHelper.openAppSettings(context)
                                    }
                                )
                            }

                            item {
                                Spacer(modifier = Modifier.height(4.dp))
                                FilledTonalButton(
                                    onClick = { AppPermissionsHelper.openAppSettings(context) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .defaultMinSize(minHeight = 48.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Abrir Definições do Dispositivo", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
            ) {
                Text("Fechar")
            }
        }
    )

    if (showClearAllConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearAllConfirmation = false },
            icon = {
                Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            },
            title = { Text("Limpar todas as permissões?") },
            text = {
                Text(
                    "Isto removerá as exceções de todos os websites. Todas as origens voltarão a utilizar as regras globais padrão.",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearAllSitePermissions()
                        showClearAllConfirmation = false
                    },
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Limpar Tudo")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showClearAllConfirmation = false },
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                ) {
                    Text("Cancelar")
                }
            }
        )
    }
}

@Composable
private fun SiteRecordItemCard(
    record: SitePermissionRecord,
    onUpdatePolicy: (WebPermissionType, PermissionPolicy) -> Unit,
    onRemoveSite: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (SitePermissionsManager.isOriginSecure(record.origin))
                        Icons.Default.Lock else Icons.Default.LockOpen,
                    contentDescription = null,
                    tint = if (SitePermissionsManager.isOriginSecure(record.origin))
                        Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = record.origin,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onRemoveSite,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Remover regras deste site",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Summary Badges with min 48dp touch target
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PermissionBadge(
                    label = "Câm",
                    policy = record.camera,
                    onClick = { expanded = !expanded }
                )
                PermissionBadge(
                    label = "Mic",
                    policy = record.microphone,
                    onClick = { expanded = !expanded }
                )
                PermissionBadge(
                    label = "Loc",
                    policy = record.location,
                    onClick = { expanded = !expanded }
                )
            }

            // Expandable inline editor
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SitePermissionSelectorRow(
                        icon = Icons.Default.CameraAlt,
                        title = "Câmara",
                        currentPolicy = record.camera,
                        onPolicySelected = { onUpdatePolicy(WebPermissionType.CAMERA, it) }
                    )
                    SitePermissionSelectorRow(
                        icon = Icons.Default.Mic,
                        title = "Microfone",
                        currentPolicy = record.microphone,
                        onPolicySelected = { onUpdatePolicy(WebPermissionType.MICROPHONE, it) }
                    )
                    SitePermissionSelectorRow(
                        icon = Icons.Default.LocationOn,
                        title = "Localização",
                        currentPolicy = record.location,
                        onPolicySelected = { onUpdatePolicy(WebPermissionType.LOCATION, it) }
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionBadge(
    label: String,
    policy: PermissionPolicy,
    onClick: () -> Unit
) {
    val (bgColor, textColor, text) = when (policy) {
        PermissionPolicy.ALLOW -> Triple(Color(0xFF2E7D32).copy(alpha = 0.15f), Color(0xFF2E7D32), "$label: Permitir")
        PermissionPolicy.BLOCK -> Triple(MaterialTheme.colorScheme.error.copy(alpha = 0.15f), MaterialTheme.colorScheme.error, "$label: Bloquear")
        PermissionPolicy.ASK -> Triple(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant, "$label: Perguntar")
    }

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = bgColor,
        modifier = Modifier
            .defaultMinSize(minHeight = 36.dp)
            .clickable { onClick() }
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = textColor
            )
        }
    }
}

@Composable
private fun GlobalPolicyConfigCard(
    icon: ImageVector,
    title: String,
    description: String,
    currentPolicy: PermissionPolicy,
    onSelectPolicy: (PermissionPolicy) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(text = title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(text = description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Selector options with min 48dp touch targets
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PermissionPolicy.entries.forEach { policy ->
                    val isSelected = currentPolicy == policy
                    val (buttonColor, textColor) = when (policy) {
                        PermissionPolicy.ASK -> if (isSelected) MaterialTheme.colorScheme.primary to Color.White else Color.Transparent to MaterialTheme.colorScheme.onSurfaceVariant
                        PermissionPolicy.BLOCK -> if (isSelected) MaterialTheme.colorScheme.error to Color.White else Color.Transparent to MaterialTheme.colorScheme.onSurfaceVariant
                        PermissionPolicy.ALLOW -> if (isSelected) Color(0xFF2E7D32) to Color.White else Color.Transparent to MaterialTheme.colorScheme.onSurfaceVariant
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = buttonColor,
                        border = if (!isSelected) BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)) else null,
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .clickable { onSelectPolicy(policy) }
                    ) {
                        Box(
                            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = policy.label,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = textColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionItemCard(
    item: AppPermissionItem,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(
                        if (item.isGranted) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when (item.id) {
                        "notifications" -> Icons.Default.Notifications
                        "camera" -> Icons.Default.CameraAlt
                        "microphone" -> Icons.Default.Mic
                        "location" -> Icons.Default.LocationOn
                        else -> Icons.Default.Folder
                    },
                    contentDescription = null,
                    tint = if (item.isGranted) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = if (item.isGranted) Color(0xFF2E7D32).copy(alpha = 0.15f)
                        else MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = if (item.isGranted) "Ativa" else "Desativada",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (item.isGranted) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = item.description,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 15.sp
                )
            }

            if (!item.isGranted) {
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(
                    onClick = onRequest,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Autorizar", fontSize = 12.sp)
                }
            }
        }
    }
}
