package com.example.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.util.ParsedQr
import com.example.util.QrCodeHelper
import com.example.util.QrStandardType
import com.example.util.QrStandardsHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

private val BrandCyan = Color(0xFF00BCD4)
private val WifiGreen = Color(0xFF4CAF50)
private val ContactPurple = Color(0xFF9C27B0)
private val EmailBlue = Color(0xFF2196F3)
private val PhoneOrange = Color(0xFFFF9800)
private val SmsTeal = Color(0xFF009688)
private val GeoRed = Color(0xFFE91E63)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrCodeManagerDialog(
    initialText: String = "",
    onDismiss: () -> Unit,
    onOpenUrl: (String) -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    var selectedTabIndex by remember { mutableIntStateOf(0) } // 0: Gerar, 1: Ler
    var selectedStandard by remember { mutableStateOf(QrStandardType.LINK_TEXT) }

    // Estados dos formulários de geração
    var textInput by remember { mutableStateOf(initialText) }

    // Wi-Fi
    var wifiSsid by remember { mutableStateOf("") }
    var wifiPassword by remember { mutableStateOf("") }
    var wifiSecurityType by remember { mutableStateOf("WPA") }
    var wifiIsHidden by remember { mutableStateOf(false) }

    // Contacto (vCard)
    var contactName by remember { mutableStateOf("") }
    var contactPhone by remember { mutableStateOf("") }
    var contactEmail by remember { mutableStateOf("") }
    var contactOrg by remember { mutableStateOf("") }
    var contactWebsite by remember { mutableStateOf("") }

    // E-mail
    var emailRecipient by remember { mutableStateOf("") }
    var emailSubject by remember { mutableStateOf("") }
    var emailBody by remember { mutableStateOf("") }

    // Telefone
    var phoneNumber by remember { mutableStateOf("") }

    // SMS
    var smsNumber by remember { mutableStateOf("") }
    var smsMessage by remember { mutableStateOf("") }

    // Geo
    var geoLat by remember { mutableStateOf("") }
    var geoLng by remember { mutableStateOf("") }
    var geoLabel by remember { mutableStateOf("") }

    // QR Code Gerado
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isGenerating by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    // Leitor
    var scannedResult by remember { mutableStateOf<String?>(null) }

    // Cálculo do payload a ser codificado conforme o padrão selecionado
    val generatedPayload = remember(
        selectedStandard,
        textInput,
        wifiSsid, wifiPassword, wifiSecurityType, wifiIsHidden,
        contactName, contactPhone, contactEmail, contactOrg, contactWebsite,
        emailRecipient, emailSubject, emailBody,
        phoneNumber,
        smsNumber, smsMessage,
        geoLat, geoLng, geoLabel
    ) {
        when (selectedStandard) {
            QrStandardType.LINK_TEXT -> textInput.trim()
            QrStandardType.WIFI -> {
                if (wifiSsid.isNotBlank()) {
                    QrStandardsHelper.buildWifiString(wifiSsid, wifiPassword, wifiSecurityType, wifiIsHidden)
                } else ""
            }
            QrStandardType.VCARD -> {
                if (contactName.isNotBlank()) {
                    QrStandardsHelper.buildVCardString(contactName, contactPhone, contactEmail, contactOrg, contactWebsite)
                } else ""
            }
            QrStandardType.EMAIL -> {
                if (emailRecipient.isNotBlank()) {
                    QrStandardsHelper.buildEmailString(emailRecipient, emailSubject, emailBody)
                } else ""
            }
            QrStandardType.PHONE -> {
                if (phoneNumber.isNotBlank()) {
                    QrStandardsHelper.buildPhoneString(phoneNumber)
                } else ""
            }
            QrStandardType.SMS -> {
                if (smsNumber.isNotBlank()) {
                    QrStandardsHelper.buildSmsString(smsNumber, smsMessage)
                } else ""
            }
            QrStandardType.GEO -> {
                val lat = geoLat.toDoubleOrNull()
                val lng = geoLng.toDoubleOrNull()
                if (lat != null && lng != null) {
                    QrStandardsHelper.buildGeoString(lat, lng, geoLabel)
                } else ""
            }
        }
    }

    // Geração reativa do Bitmap
    LaunchedEffect(generatedPayload) {
        if (generatedPayload.isNotBlank()) {
            isGenerating = true
            withContext(Dispatchers.Default) {
                val bmp = QrCodeHelper.generateQrBitmap(generatedPayload, sizePx = 650)
                withContext(Dispatchers.Main) {
                    qrBitmap = bmp
                    isGenerating = false
                }
            }
        } else {
            qrBitmap = null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 480.dp)
                .heightIn(max = 640.dp)
                .padding(vertical = 14.dp)
                .testTag("dialog_qr_code_manager"),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp)
            ) {
                // Cabeçalho
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = BrandCyan.copy(alpha = 0.15f),
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.QrCode2,
                                    contentDescription = null,
                                    tint = BrandCyan,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }

                        Column {
                            Text(
                                text = "QR Code Offline",
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Wi-Fi, vCard, Links e Padrões Globais",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onDismiss()
                        },
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("btn_close_qr_dialog")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Fechar",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Abas Superiores (Gerar / Ler)
                PrimaryTabRow(
                    selectedTabIndex = selectedTabIndex,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                ) {
                    Tab(
                        selected = selectedTabIndex == 0,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            selectedTabIndex = 0
                        },
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(Icons.Default.QrCode, contentDescription = null, modifier = Modifier.size(16.dp))
                                Text("Gerar QR", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1)
                            }
                        }
                    )
                    Tab(
                        selected = selectedTabIndex == 1,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            selectedTabIndex = 1
                        },
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                                Text("Ler / Escanear", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1)
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Feedback Toast
                AnimatedVisibility(visible = statusMessage != null, enter = fadeIn(), exit = fadeOut()) {
                    statusMessage?.let { msg ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(15.dp)
                                )
                                Text(
                                    text = msg,
                                    fontSize = 11.5.sp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                // Conteúdo
                Box(modifier = Modifier.weight(1f)) {
                    if (selectedTabIndex == 0) {
                        QrGeneratorMultiStandardContent(
                            selectedStandard = selectedStandard,
                            onStandardSelected = { selectedStandard = it },
                            textInput = textInput,
                            onTextInputChange = { textInput = it },
                            initialText = initialText,
                            wifiSsid = wifiSsid,
                            onWifiSsidChange = { wifiSsid = it },
                            wifiPassword = wifiPassword,
                            onWifiPasswordChange = { wifiPassword = it },
                            wifiSecurityType = wifiSecurityType,
                            onWifiSecurityTypeChange = { wifiSecurityType = it },
                            wifiIsHidden = wifiIsHidden,
                            onWifiIsHiddenChange = { wifiIsHidden = it },
                            contactName = contactName,
                            onContactNameChange = { contactName = it },
                            contactPhone = contactPhone,
                            onContactPhoneChange = { contactPhone = it },
                            contactEmail = contactEmail,
                            onContactEmailChange = { contactEmail = it },
                            contactOrg = contactOrg,
                            onContactOrgChange = { contactOrg = it },
                            contactWebsite = contactWebsite,
                            onContactWebsiteChange = { contactWebsite = it },
                            emailRecipient = emailRecipient,
                            onEmailRecipientChange = { emailRecipient = it },
                            emailSubject = emailSubject,
                            onEmailSubjectChange = { emailSubject = it },
                            emailBody = emailBody,
                            onEmailBodyChange = { emailBody = it },
                            phoneNumber = phoneNumber,
                            onPhoneNumberChange = { phoneNumber = it },
                            smsNumber = smsNumber,
                            onSmsNumberChange = { smsNumber = it },
                            smsMessage = smsMessage,
                            onSmsMessageChange = { smsMessage = it },
                            geoLat = geoLat,
                            onGeoLatChange = { geoLat = it },
                            geoLng = geoLng,
                            onGeoLngChange = { geoLng = it },
                            geoLabel = geoLabel,
                            onGeoLabelChange = { geoLabel = it },
                            qrBitmap = qrBitmap,
                            isGenerating = isGenerating,
                            generatedPayload = generatedPayload,
                            onSave = { bmp ->
                                val savedUri = QrCodeHelper.saveQrBitmapToGallery(context, bmp)
                                statusMessage = if (savedUri != null) "Guardado em Imagens/QRCodes!" else "Erro ao guardar imagem."
                            },
                            onShare = { bmp ->
                                QrCodeHelper.shareQrCode(context, bmp, generatedPayload)
                            },
                            onCopyPayload = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("QR Code", generatedPayload)
                                clipboard.setPrimaryClip(clip)
                                statusMessage = "Conteúdo copiado com sucesso!"
                            }
                        )
                    } else {
                        QrScannerContent(
                            scannedResult = scannedResult,
                            onResultScanned = { result -> scannedResult = result },
                            onClearResult = { scannedResult = null },
                            onOpenUrl = { url ->
                                onOpenUrl(url)
                                onDismiss()
                            },
                            onSwitchToGenerate = { text ->
                                val parsed = QrStandardsHelper.parse(text)
                                when (parsed) {
                                    is ParsedQr.Wifi -> {
                                        selectedStandard = QrStandardType.WIFI
                                        wifiSsid = parsed.ssid
                                        wifiPassword = parsed.password
                                        wifiSecurityType = parsed.securityType
                                        wifiIsHidden = parsed.isHidden
                                    }
                                    is ParsedQr.Contact -> {
                                        selectedStandard = QrStandardType.VCARD
                                        contactName = parsed.name
                                        contactPhone = parsed.phone ?: ""
                                        contactEmail = parsed.email ?: ""
                                        contactOrg = parsed.organization ?: ""
                                        contactWebsite = parsed.website ?: ""
                                    }
                                    is ParsedQr.Email -> {
                                        selectedStandard = QrStandardType.EMAIL
                                        emailRecipient = parsed.email
                                        emailSubject = parsed.subject ?: ""
                                        emailBody = parsed.body ?: ""
                                    }
                                    is ParsedQr.Phone -> {
                                        selectedStandard = QrStandardType.PHONE
                                        phoneNumber = parsed.number
                                    }
                                    is ParsedQr.Sms -> {
                                        selectedStandard = QrStandardType.SMS
                                        smsNumber = parsed.number
                                        smsMessage = parsed.message ?: ""
                                    }
                                    is ParsedQr.Geo -> {
                                        selectedStandard = QrStandardType.GEO
                                        geoLat = parsed.latitude.toString()
                                        geoLng = parsed.longitude.toString()
                                        geoLabel = parsed.label ?: ""
                                    }
                                    else -> {
                                        selectedStandard = QrStandardType.LINK_TEXT
                                        textInput = text
                                    }
                                }
                                selectedTabIndex = 0
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QrGeneratorMultiStandardContent(
    selectedStandard: QrStandardType,
    onStandardSelected: (QrStandardType) -> Unit,
    textInput: String,
    onTextInputChange: (String) -> Unit,
    initialText: String,
    wifiSsid: String,
    onWifiSsidChange: (String) -> Unit,
    wifiPassword: String,
    onWifiPasswordChange: (String) -> Unit,
    wifiSecurityType: String,
    onWifiSecurityTypeChange: (String) -> Unit,
    wifiIsHidden: Boolean,
    onWifiIsHiddenChange: (Boolean) -> Unit,
    contactName: String,
    onContactNameChange: (String) -> Unit,
    contactPhone: String,
    onContactPhoneChange: (String) -> Unit,
    contactEmail: String,
    onContactEmailChange: (String) -> Unit,
    contactOrg: String,
    onContactOrgChange: (String) -> Unit,
    contactWebsite: String,
    onContactWebsiteChange: (String) -> Unit,
    emailRecipient: String,
    onEmailRecipientChange: (String) -> Unit,
    emailSubject: String,
    onEmailSubjectChange: (String) -> Unit,
    emailBody: String,
    onEmailBodyChange: (String) -> Unit,
    phoneNumber: String,
    onPhoneNumberChange: (String) -> Unit,
    smsNumber: String,
    onSmsNumberChange: (String) -> Unit,
    smsMessage: String,
    onSmsMessageChange: (String) -> Unit,
    geoLat: String,
    onGeoLatChange: (String) -> Unit,
    geoLng: String,
    onGeoLngChange: (String) -> Unit,
    geoLabel: String,
    onGeoLabelChange: (String) -> Unit,
    qrBitmap: Bitmap?,
    isGenerating: Boolean,
    generatedPayload: String,
    onSave: (Bitmap) -> Unit,
    onShare: (Bitmap?) -> Unit,
    onCopyPayload: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    var showWifiPassword by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Seletor de Tipo / Padrão em Chips Horizontais
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            StandardChip(
                icon = Icons.Default.Link,
                label = "Link / Texto",
                selected = selectedStandard == QrStandardType.LINK_TEXT,
                color = BrandCyan,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onStandardSelected(QrStandardType.LINK_TEXT)
                }
            )
            StandardChip(
                icon = Icons.Default.Wifi,
                label = "Wi-Fi",
                selected = selectedStandard == QrStandardType.WIFI,
                color = WifiGreen,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onStandardSelected(QrStandardType.WIFI)
                }
            )
            StandardChip(
                icon = Icons.Default.Person,
                label = "vCard",
                selected = selectedStandard == QrStandardType.VCARD,
                color = ContactPurple,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onStandardSelected(QrStandardType.VCARD)
                }
            )
            StandardChip(
                icon = Icons.Default.Email,
                label = "E-mail",
                selected = selectedStandard == QrStandardType.EMAIL,
                color = EmailBlue,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onStandardSelected(QrStandardType.EMAIL)
                }
            )
            StandardChip(
                icon = Icons.Default.Phone,
                label = "Telefone",
                selected = selectedStandard == QrStandardType.PHONE,
                color = PhoneOrange,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onStandardSelected(QrStandardType.PHONE)
                }
            )
            StandardChip(
                icon = Icons.Default.Sms,
                label = "SMS",
                selected = selectedStandard == QrStandardType.SMS,
                color = SmsTeal,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onStandardSelected(QrStandardType.SMS)
                }
            )
            StandardChip(
                icon = Icons.Default.LocationOn,
                label = "Localização",
                selected = selectedStandard == QrStandardType.GEO,
                color = GeoRed,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onStandardSelected(QrStandardType.GEO)
                }
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Formulário Dinâmico conforme o Padrão Escolhido
        when (selectedStandard) {
            QrStandardType.LINK_TEXT -> {
                OutlinedTextField(
                    value = textInput,
                    onValueChange = onTextInputChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Digite ou cole link ou texto...", fontSize = 12.5.sp) },
                    shape = RoundedCornerShape(12.dp),
                    trailingIcon = {
                        if (textInput.isNotEmpty()) {
                            IconButton(onClick = { onTextInputChange("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "Limpar", modifier = Modifier.size(17.dp))
                            }
                        } else {
                            IconButton(onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                                if (!clip.isNullOrBlank()) onTextInputChange(clip)
                            }) {
                                Icon(Icons.Default.ContentPaste, contentDescription = "Colar", modifier = Modifier.size(17.dp))
                            }
                        }
                    },
                    maxLines = 2
                )
                if (initialText.isNotBlank() && textInput != initialText) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onTextInputChange(initialText)
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(13.dp))
                                Text("Usar URL da Página Atual", fontSize = 10.5.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }

            QrStandardType.WIFI -> {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = wifiSsid,
                        onValueChange = onWifiSsidChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Nome da Rede (SSID)*", fontSize = 11.5.sp) },
                        placeholder = { Text("Ex: MinhaCasa_5G", fontSize = 12.sp) },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp)
                    )
                    OutlinedTextField(
                        value = wifiPassword,
                        onValueChange = onWifiPasswordChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Senha da Rede", fontSize = 11.5.sp) },
                        placeholder = { Text("Deixe vazio se for aberta", fontSize = 12.sp) },
                        singleLine = true,
                        visualTransformation = if (showWifiPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showWifiPassword = !showWifiPassword }) {
                                Icon(
                                    imageVector = if (showWifiPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = null,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        },
                        shape = RoundedCornerShape(10.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("WPA", "WEP", "nopass").forEach { type ->
                                val label = if (type == "nopass") "Aberta" else type
                                FilterChip(
                                    selected = wifiSecurityType == type,
                                    onClick = { onWifiSecurityTypeChange(type) },
                                    label = { Text(label, fontSize = 10.5.sp) }
                                )
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { onWifiIsHiddenChange(!wifiIsHidden) }
                        ) {
                            Checkbox(
                                checked = wifiIsHidden,
                                onCheckedChange = { onWifiIsHiddenChange(it) },
                                modifier = Modifier.size(28.dp)
                            )
                            Text("Oculta", fontSize = 11.sp)
                        }
                    }
                }
            }

            QrStandardType.VCARD -> {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = contactName,
                        onValueChange = onContactNameChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Nome Completo*", fontSize = 11.5.sp) },
                        placeholder = { Text("Ex: Ana Silva", fontSize = 12.sp) },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = contactPhone,
                            onValueChange = onContactPhoneChange,
                            modifier = Modifier.weight(1f),
                            label = { Text("Telemóvel / Tel.", fontSize = 11.5.sp) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp)
                        )
                        OutlinedTextField(
                            value = contactEmail,
                            onValueChange = onContactEmailChange,
                            modifier = Modifier.weight(1f),
                            label = { Text("E-mail", fontSize = 11.5.sp) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = contactOrg,
                            onValueChange = onContactOrgChange,
                            modifier = Modifier.weight(1f),
                            label = { Text("Empresa / Organização", fontSize = 11.5.sp) },
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp)
                        )
                        OutlinedTextField(
                            value = contactWebsite,
                            onValueChange = onContactWebsiteChange,
                            modifier = Modifier.weight(1f),
                            label = { Text("Website / Link", fontSize = 11.5.sp) },
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp)
                        )
                    }
                }
            }

            QrStandardType.EMAIL -> {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = emailRecipient,
                        onValueChange = onEmailRecipientChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Destinatário (E-mail)*", fontSize = 11.5.sp) },
                        placeholder = { Text("exemplo@empresa.com", fontSize = 12.sp) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp)
                    )
                    OutlinedTextField(
                        value = emailSubject,
                        onValueChange = onEmailSubjectChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Assunto", fontSize = 11.5.sp) },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp)
                    )
                    OutlinedTextField(
                        value = emailBody,
                        onValueChange = onEmailBodyChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Mensagem", fontSize = 11.5.sp) },
                        maxLines = 2,
                        shape = RoundedCornerShape(10.dp)
                    )
                }
            }

            QrStandardType.PHONE -> {
                OutlinedTextField(
                    value = phoneNumber,
                    onValueChange = onPhoneNumberChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Número de Telefone*", fontSize = 11.5.sp) },
                    placeholder = { Text("Ex: +351 912 345 678", fontSize = 12.sp) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp)
                )
            }

            QrStandardType.SMS -> {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = smsNumber,
                        onValueChange = onSmsNumberChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Número de Destino*", fontSize = 11.5.sp) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp)
                    )
                    OutlinedTextField(
                        value = smsMessage,
                        onValueChange = onSmsMessageChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Mensagem SMS", fontSize = 11.5.sp) },
                        maxLines = 2,
                        shape = RoundedCornerShape(10.dp)
                    )
                }
            }

            QrStandardType.GEO -> {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = geoLat,
                            onValueChange = onGeoLatChange,
                            modifier = Modifier.weight(1f),
                            label = { Text("Latitude*", fontSize = 11.5.sp) },
                            placeholder = { Text("38.7223", fontSize = 12.sp) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp)
                        )
                        OutlinedTextField(
                            value = geoLng,
                            onValueChange = onGeoLngChange,
                            modifier = Modifier.weight(1f),
                            label = { Text("Longitude*", fontSize = 11.5.sp) },
                            placeholder = { Text("-9.1393", fontSize = 12.sp) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp)
                        )
                    }
                    OutlinedTextField(
                        value = geoLabel,
                        onValueChange = onGeoLabelChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Nome do Local / Rótulo (Opcional)", fontSize = 11.5.sp) },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Visualização do QR Code Gerado
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.White,
            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            shadowElevation = 3.dp,
            modifier = Modifier
                .sizeIn(minWidth = 140.dp, maxWidth = 180.dp, minHeight = 140.dp, maxHeight = 180.dp)
                .aspectRatio(1f)
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                if (isGenerating) {
                    CircularProgressIndicator(modifier = Modifier.size(30.dp), color = BrandCyan)
                } else if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "QR Code Gerado",
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp)
                    )
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.QrCode2,
                            contentDescription = null,
                            tint = Color.Gray.copy(alpha = 0.5f),
                            modifier = Modifier.size(46.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Preencha os campos para gerar o código",
                            fontSize = 11.sp,
                            color = Color.DarkGray,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Botões de Ação
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Button(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    qrBitmap?.let { onSave(it) }
                },
                enabled = qrBitmap != null,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp)
                    .testTag("btn_save_qr_image")
            ) {
                Icon(Icons.Default.SaveAlt, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(3.dp))
                Text("Guardar", fontSize = 12.sp, maxLines = 1, softWrap = false)
            }

            FilledTonalButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onShare(qrBitmap)
                },
                enabled = generatedPayload.isNotBlank(),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp)
                    .testTag("btn_share_qr")
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(3.dp))
                Text("Partilhar", fontSize = 12.sp, maxLines = 1, softWrap = false)
            }

            OutlinedButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onCopyPayload()
                },
                enabled = generatedPayload.isNotBlank(),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp)
                    .testTag("btn_copy_qr_text")
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(3.dp))
                Text("Copiar", fontSize = 12.sp, maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
private fun StandardChip(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    color: Color,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) color.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, if (selected) color else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
        modifier = Modifier.clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) color else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = label,
                fontSize = 11.5.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) color else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun QrScannerContent(
    scannedResult: String?,
    onResultScanned: (String) -> Unit,
    onClearResult: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onSwitchToGenerate: (String) -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    var isTorchOn by remember { mutableStateOf(false) }
    var cameraControl by remember { mutableStateOf<Camera?>(null) }
    var galleryScanError by remember { mutableStateOf<String?>(null) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            val decoded = QrCodeHelper.decodeFromUri(context, uri)
            if (!decoded.isNullOrBlank()) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onResultScanned(decoded)
                galleryScanError = null
            } else {
                galleryScanError = "Nenhum código QR válido detectado na imagem."
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (scannedResult != null) {
            ScannedEnrichedResultCard(
                rawContent = scannedResult,
                onOpenUrl = { onOpenUrl(scannedResult) },
                onGenerateQr = { onSwitchToGenerate(scannedResult) },
                onScanAgain = onClearResult
            )
        } else if (!hasCameraPermission) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(56.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Acesso à Câmera Necessário",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.5.sp
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Permita o acesso à câmera para ler códigos QR ao vivo.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(14.dp))

                Button(
                    onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Conceder Permissão", fontSize = 13.sp)
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedButton(
                    onClick = {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Ler Imagem da Galeria", fontSize = 12.5.sp)
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.Black)
                ) {
                    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

                    AndroidView(
                        factory = { ctx ->
                            val previewView = PreviewView(ctx)
                            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                            cameraProviderFuture.addListener({
                                val cameraProvider = cameraProviderFuture.get()
                                val preview = Preview.Builder().build().also {
                                    it.setSurfaceProvider(previewView.surfaceProvider)
                                }

                                val imageAnalysis = ImageAnalysis.Builder()
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .build()

                                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                                    val qr = QrCodeHelper.decodeFromImageProxy(imageProxy)
                                    imageProxy.close()
                                    if (!qr.isNullOrBlank()) {
                                        cameraProvider.unbindAll()
                                        onResultScanned(qr)
                                    }
                                }

                                try {
                                    cameraProvider.unbindAll()
                                    val cam = cameraProvider.bindToLifecycle(
                                        lifecycleOwner,
                                        CameraSelector.DEFAULT_BACK_CAMERA,
                                        preview,
                                        imageAnalysis
                                    )
                                    cameraControl = cam
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }, ContextCompat.getMainExecutor(ctx))

                            previewView
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    // Retículo Animado de Escaneamento
                    Box(
                        modifier = Modifier
                            .size(180.dp)
                            .align(Alignment.Center)
                            .border(2.dp, BrandCyan, RoundedCornerShape(14.dp))
                    ) {
                        val infiniteTransition = rememberInfiniteTransition(label = "scan_line")
                        val translateY by infiniteTransition.animateFloat(
                            initialValue = 0f,
                            targetValue = 180f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1800, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "scan_laser"
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.5.dp)
                                .offset(y = translateY.dp)
                                .background(BrandCyan)
                        )
                    }

                    IconButton(
                        onClick = {
                            isTorchOn = !isTorchOn
                            cameraControl?.cameraControl?.enableTorch(isTorchOn)
                        },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(10.dp)
                            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    ) {
                        Icon(
                            imageVector = if (isTorchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                            contentDescription = "Lanterna",
                            tint = if (isTorchOn) Color.Yellow else Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (galleryScanError != null) {
                    Text(
                        text = galleryScanError ?: "",
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }

                FilledTonalButton(
                    onClick = {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Ler Imagem da Galeria", fontSize = 12.5.sp)
                }
            }
        }
    }
}

@Composable
private fun ScannedEnrichedResultCard(
    rawContent: String,
    onOpenUrl: () -> Unit,
    onGenerateQr: () -> Unit,
    onScanAgain: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scrollState = rememberScrollState()

    val parsed = remember(rawContent) {
        QrStandardsHelper.parse(rawContent)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val (badgeIcon, badgeColor, badgeTitle) = when (parsed) {
            is ParsedQr.Wifi -> Triple(Icons.Default.Wifi, WifiGreen, "Rede Wi-Fi")
            is ParsedQr.Contact -> Triple(Icons.Default.Person, ContactPurple, "Contacto (vCard)")
            is ParsedQr.Email -> Triple(Icons.Default.Email, EmailBlue, "E-mail")
            is ParsedQr.Phone -> Triple(Icons.Default.Phone, PhoneOrange, "Telefone")
            is ParsedQr.Sms -> Triple(Icons.Default.Sms, SmsTeal, "Mensagem SMS")
            is ParsedQr.Geo -> Triple(Icons.Default.LocationOn, GeoRed, "Localização")
            is ParsedQr.Url -> Triple(Icons.Default.Link, BrandCyan, "Link / URL Web")
            is ParsedQr.PlainText -> Triple(Icons.Default.TextFields, MaterialTheme.colorScheme.primary, "Texto")
        }

        Surface(
            shape = CircleShape,
            color = badgeColor.copy(alpha = 0.15f),
            modifier = Modifier.size(46.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = badgeIcon,
                    contentDescription = null,
                    tint = badgeColor,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "QR Code Reconhecido!",
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Card com Conteúdo Enriquecido
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Surface(
                    color = badgeColor.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = badgeTitle.uppercase(),
                        color = badgeColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                when (parsed) {
                    is ParsedQr.Wifi -> {
                        Text("SSID: ${parsed.ssid}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        if (parsed.password.isNotBlank()) {
                            Text("Senha: ${parsed.password}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Text("Rede Aberta (Sem Senha)", fontSize = 12.sp, color = WifiGreen)
                        }
                        Text("Segurança: ${parsed.securityType}", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
                    }

                    is ParsedQr.Contact -> {
                        Text(parsed.name, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        parsed.phone?.let { Text("📞 $it", fontSize = 13.sp) }
                        parsed.email?.let { Text("✉️ $it", fontSize = 13.sp) }
                        parsed.organization?.let { Text("🏢 $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        parsed.website?.let { Text("🌐 $it", fontSize = 12.sp, color = BrandCyan) }
                    }

                    is ParsedQr.Email -> {
                        Text("Para: ${parsed.email}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        parsed.subject?.let { Text("Assunto: $it", fontSize = 13.sp) }
                        parsed.body?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }

                    is ParsedQr.Phone -> {
                        Text(parsed.number, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }

                    is ParsedQr.Sms -> {
                        Text("Para: ${parsed.number}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        parsed.message?.let { Text("Mensagem: $it", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }

                    is ParsedQr.Geo -> {
                        Text("Coordenadas: ${parsed.latitude}, ${parsed.longitude}", fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                        parsed.label?.let { Text("Local: $it", fontSize = 13.sp) }
                    }

                    is ParsedQr.Url -> {
                        Text(parsed.url, fontWeight = FontWeight.Medium, fontSize = 13.5.sp, color = BrandCyan)
                    }

                    is ParsedQr.PlainText -> {
                        Text(parsed.text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Ação Primária Contextual
        when (parsed) {
            is ParsedQr.Wifi -> {
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        if (parsed.password.isNotBlank()) {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("WiFi Password", parsed.password))
                        }
                        QrStandardsHelper.openWifiSettings(context)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = WifiGreen)
                ) {
                    Icon(Icons.Default.Wifi, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Ligar ao Wi-Fi / Abrir Definições", fontSize = 13.sp)
                }
            }

            is ParsedQr.Contact -> {
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        QrStandardsHelper.addContact(context, parsed)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ContactPurple)
                ) {
                    Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Guardar nos Contactos", fontSize = 13.sp)
                }
            }

            is ParsedQr.Url -> {
                Button(
                    onClick = onOpenUrl,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Abrir no Navegador", fontSize = 13.sp)
                }
            }

            is ParsedQr.Phone -> {
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        QrStandardsHelper.dialPhone(context, parsed.number)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PhoneOrange)
                ) {
                    Icon(Icons.Default.Phone, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Fazer Chamada", fontSize = 13.sp)
                }
            }

            is ParsedQr.Sms -> {
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        QrStandardsHelper.sendSms(context, parsed.number, parsed.message)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SmsTeal)
                ) {
                    Icon(Icons.Default.Sms, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Enviar SMS", fontSize = 13.sp)
                }
            }

            is ParsedQr.Email -> {
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        QrStandardsHelper.sendEmail(context, parsed.email, parsed.subject, parsed.body)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = EmailBlue)
                ) {
                    Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Escrever E-mail", fontSize = 13.sp)
                }
            }

            is ParsedQr.Geo -> {
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        QrStandardsHelper.openMap(context, parsed.latitude, parsed.longitude, parsed.label)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = GeoRed)
                ) {
                    Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Abrir no Mapa", fontSize = 13.sp)
                }
            }

            is ParsedQr.PlainText -> {}
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Ações Secundárias (Copiar, Partilhar, Gerar QR)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilledTonalButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("QR Code", rawContent))
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                },
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp)
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(3.dp))
                Text("Copiar", fontSize = 12.sp, maxLines = 1, softWrap = false)
            }

            FilledTonalButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    QrCodeHelper.shareQrCode(context, null, rawContent)
                },
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp)
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(3.dp))
                Text("Partilhar", fontSize = 12.sp, maxLines = 1, softWrap = false)
            }

            OutlinedButton(
                onClick = onGenerateQr,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp)
            ) {
                Icon(Icons.Default.QrCode, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(3.dp))
                Text("Gerar QR", fontSize = 12.sp, maxLines = 1, softWrap = false)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        TextButton(
            onClick = onScanAgain,
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.Replay, contentDescription = null, modifier = Modifier.size(15.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Escanear Outro Código", fontSize = 12.5.sp)
        }
    }
}
