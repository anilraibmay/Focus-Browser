package com.example.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import com.example.util.AdvancedDownloadItem
import com.example.util.DownloadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Categorias de arquivo suportadas para determinação inteligente de miniaturas e ícones.
 */
enum class FileCategory {
    IMAGE,
    VIDEO,
    AUDIO,
    PDF,
    APK,
    DOCUMENT,
    SPREADSHEET,
    PRESENTATION,
    ARCHIVE,
    CODE,
    GENERIC
}

data class FileCategoryDetails(
    val category: FileCategory,
    val icon: ImageVector,
    val iconColor: Color,
    val containerColor: Color,
    val extensionLabel: String
)

/**
 * Resolve os detalhes visuais (ícone semântico, paleta e etiqueta) baseando-se no nome e no MIME type do arquivo.
 */
fun resolveFileCategoryDetails(fileName: String, mimeType: String): FileCategoryDetails {
    val ext = if (fileName.contains('.')) {
        fileName.substringAfterLast('.').lowercase(Locale.ROOT)
    } else {
        ""
    }
    val lowerMime = mimeType.lowercase(Locale.ROOT)

    val category = when {
        ext in listOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg") || lowerMime.startsWith("image/") ->
            FileCategory.IMAGE

        ext in listOf("mp4", "webm", "mkv", "mov", "3gp", "ts", "m4v", "avi", "flv") || lowerMime.startsWith("video/") ->
            FileCategory.VIDEO

        ext in listOf("mp3", "wav", "ogg", "m4a", "aac", "flac", "opus", "mid") || lowerMime.startsWith("audio/") ->
            FileCategory.AUDIO

        ext == "pdf" || lowerMime.contains("pdf") ->
            FileCategory.PDF

        ext in listOf("apk", "xapk", "aab") || lowerMime.contains("android.package-archive") ->
            FileCategory.APK

        ext in listOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz") || lowerMime.contains("zip") || lowerMime.contains("tar") ->
            FileCategory.ARCHIVE

        ext in listOf("doc", "docx", "txt", "rtf", "odt", "md") || lowerMime.contains("document") || lowerMime.contains("text/plain") ->
            FileCategory.DOCUMENT

        ext in listOf("xls", "xlsx", "csv", "ods") || lowerMime.contains("spreadsheet") || lowerMime.contains("excel") ->
            FileCategory.SPREADSHEET

        ext in listOf("ppt", "pptx", "odp") || lowerMime.contains("presentation") || lowerMime.contains("powerpoint") ->
            FileCategory.PRESENTATION

        ext in listOf("html", "htm", "json", "xml", "js", "ts", "css", "kt", "java", "py", "c", "cpp", "sh") ->
            FileCategory.CODE

        else -> FileCategory.GENERIC
    }

    val label = if (ext.isNotEmpty()) ext.uppercase(Locale.ROOT).take(4) else "FILE"

    return when (category) {
        FileCategory.IMAGE -> FileCategoryDetails(
            category = category,
            icon = Icons.Default.Image,
            iconColor = Color(0xFF00897B),
            containerColor = Color(0xFFE0F2F1),
            extensionLabel = label
        )
        FileCategory.VIDEO -> FileCategoryDetails(
            category = category,
            icon = Icons.Default.Movie,
            iconColor = Color(0xFF1E88E5),
            containerColor = Color(0xFFE3F2FD),
            extensionLabel = label
        )
        FileCategory.AUDIO -> FileCategoryDetails(
            category = category,
            icon = Icons.Default.Audiotrack,
            iconColor = Color(0xFF8E24AA),
            containerColor = Color(0xFFF3E5F5),
            extensionLabel = label
        )
        FileCategory.PDF -> FileCategoryDetails(
            category = category,
            icon = Icons.Default.PictureAsPdf,
            iconColor = Color(0xFFE53935),
            containerColor = Color(0xFFFFEBEE),
            extensionLabel = "PDF"
        )
        FileCategory.APK -> FileCategoryDetails(
            category = category,
            icon = Icons.Default.Android,
            iconColor = Color(0xFF43A047),
            containerColor = Color(0xFFE8F5E9),
            extensionLabel = "APK"
        )
        FileCategory.ARCHIVE -> FileCategoryDetails(
            category = category,
            icon = Icons.Default.FolderZip,
            iconColor = Color(0xFFFB8C00),
            containerColor = Color(0xFFFFF3E0),
            extensionLabel = label
        )
        FileCategory.DOCUMENT -> FileCategoryDetails(
            category = category,
            icon = Icons.Default.Description,
            iconColor = Color(0xFF3949AB),
            containerColor = Color(0xFFE8EAF6),
            extensionLabel = label
        )
        FileCategory.SPREADSHEET -> FileCategoryDetails(
            category = category,
            icon = Icons.Default.TableChart,
            iconColor = Color(0xFF2E7D32),
            containerColor = Color(0xFFE8F5E9),
            extensionLabel = label
        )
        FileCategory.PRESENTATION -> FileCategoryDetails(
            category = category,
            icon = Icons.Default.Slideshow,
            iconColor = Color(0xFFE65100),
            containerColor = Color(0xFFFBE9E7),
            extensionLabel = label
        )
        FileCategory.CODE -> FileCategoryDetails(
            category = category,
            icon = Icons.Default.Code,
            iconColor = Color(0xFF00ACC1),
            containerColor = Color(0xFFE0F7FA),
            extensionLabel = label
        )
        FileCategory.GENERIC -> FileCategoryDetails(
            category = category,
            icon = Icons.AutoMirrored.Filled.InsertDriveFile,
            iconColor = Color(0xFF757575),
            containerColor = Color(0xFFEEEEEE),
            extensionLabel = label
        )
    }
}

/**
 * Componente de miniatura adaptativo:
 * - Tenta gerar a pré-visualização real (Imagem, Frame de Vídeo, Primeira página de PDF, Ícone de APK ou Capa de Áudio).
 * - Caso o arquivo ainda esteja a descarregar, falhe ou não suporte pré-visualização, renderiza um ícone temático
 *   elegante estilizado rigorosamente de acordo com o formato do ficheiro.
 */
@Composable
fun DownloadThumbnailView(
    item: AdvancedDownloadItem,
    size: Dp = 60.dp,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val categoryDetails = remember(item.fileName, item.mimeType) {
        resolveFileCategoryDetails(item.fileName, item.mimeType)
    }

    val isCompleted = item.state == DownloadState.COMPLETED
    val file = item.file
    val fileExists = remember(file, isCompleted) {
        isCompleted && file != null && file.exists() && file.length() > 0
    }

    val imageLoader = remember(context) {
        ImageLoader.Builder(context)
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .crossfade(true)
            .build()
    }

    // Estado para carregamentos especiais (PDF, Áudio, APK)
    val specialThumbnail by produceState<Bitmap?>(initialValue = null, key1 = file?.absolutePath, key2 = fileExists) {
        if (!fileExists || file == null) {
            value = null
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            when (categoryDetails.category) {
                FileCategory.PDF -> extractPdfFirstPage(file)
                FileCategory.AUDIO -> extractAudioCover(file)
                else -> null
            }
        }
    }

    val apkDrawable by produceState<Drawable?>(initialValue = null, key1 = file?.absolutePath, key2 = fileExists) {
        if (!fileExists || file == null || categoryDetails.category != FileCategory.APK) {
            value = null
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            extractApkIcon(context, file)
        }
    }

    var imageLoadFailed by remember(item.id, fileExists) { mutableStateOf(false) }

    val shape = RoundedCornerShape(10.dp)
    val clickableModifier = if (onClick != null) {
        modifier.clickable(onClick = onClick)
    } else {
        modifier
    }

    Box(
        modifier = clickableModifier
            .size(size)
            .clip(shape)
            .background(categoryDetails.containerColor)
            .border(1.dp, categoryDetails.iconColor.copy(alpha = 0.2f), shape),
        contentAlignment = Alignment.Center
    ) {
        when {
            // Caso 1: Imagem ou Vídeo concluído e disponível no disco
            fileExists && file != null && (categoryDetails.category == FileCategory.IMAGE || categoryDetails.category == FileCategory.VIDEO) && !imageLoadFailed -> {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(file)
                        .crossfade(true)
                        .build(),
                    imageLoader = imageLoader,
                    contentDescription = item.fileName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    onError = {
                        imageLoadFailed = true
                    }
                )

                // Selo indicativo de vídeo sobre a miniatura
                if (categoryDetails.category == FileCategory.VIDEO) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.25f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            color = Color.Black.copy(alpha = 0.6f),
                            shape = CircleShape,
                            modifier = Modifier.size(size * 0.42f)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Vídeo",
                                    tint = Color.White,
                                    modifier = Modifier.size(size * 0.28f)
                                )
                            }
                        }
                    }
                }
            }

            // Caso 2: PDF com miniatura gerada da 1ª página
            fileExists && specialThumbnail != null && categoryDetails.category == FileCategory.PDF -> {
                Image(
                    bitmap = specialThumbnail!!.asImageBitmap(),
                    contentDescription = item.fileName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                // Pequeno selo identificador PDF no canto
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(2.dp),
                    contentAlignment = Alignment.BottomEnd
                ) {
                    Surface(
                        color = categoryDetails.iconColor,
                        shape = RoundedCornerShape(3.dp)
                    ) {
                        Text(
                            text = "PDF",
                            color = Color.White,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp)
                        )
                    }
                }
            }

            // Caso 3: Áudio com imagem de capa extraída
            fileExists && specialThumbnail != null && categoryDetails.category == FileCategory.AUDIO -> {
                Image(
                    bitmap = specialThumbnail!!.asImageBitmap(),
                    contentDescription = item.fileName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(2.dp),
                    contentAlignment = Alignment.BottomEnd
                ) {
                    Surface(
                        color = categoryDetails.iconColor,
                        shape = RoundedCornerShape(3.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Audiotrack,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(10.dp).padding(1.dp)
                        )
                    }
                }
            }

            // Caso 4: APK com ícone do aplicativo
            fileExists && apkDrawable != null && categoryDetails.category == FileCategory.APK -> {
                val apkBitmap = remember(apkDrawable) {
                    drawableToBitmap(apkDrawable!!)
                }
                if (apkBitmap != null) {
                    Image(
                        bitmap = apkBitmap.asImageBitmap(),
                        contentDescription = item.fileName,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(6.dp)
                    )
                } else {
                    FileFormatIconPlaceholder(categoryDetails, size)
                }
            }

            // Caso 5: Fallback ou arquivos sem suporte a thumbnail (ícone semântico pelo formato)
            else -> {
                FileFormatIconPlaceholder(categoryDetails, size)
            }
        }

        // Se estiver em processo de transferência ou pendente, exibe sutil anel de progresso
        if (item.state == DownloadState.DOWNLOADING || item.state == DownloadState.PROCESSING) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(size * 0.45f),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.dp
                )
            }
        }
    }
}

/**
 * Placeholder estilizado contendo o ícone temático e a etiqueta do formato do arquivo.
 */
@Composable
private fun FileFormatIconPlaceholder(details: FileCategoryDetails, size: Dp) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(4.dp)
    ) {
        Icon(
            imageVector = details.icon,
            contentDescription = details.extensionLabel,
            tint = details.iconColor,
            modifier = Modifier.size(size * 0.45f)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Surface(
            color = details.iconColor.copy(alpha = 0.18f),
            shape = RoundedCornerShape(4.dp)
        ) {
            Text(
                text = details.extensionLabel,
                color = details.iconColor,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }
    }
}

/**
 * Diálogo modal para pré-visualização completa do arquivo descarregado (Imagem / Vídeo / Documento).
 */
@Composable
fun DownloadPreviewModal(
    item: AdvancedDownloadItem,
    onDismiss: () -> Unit,
    onOpenFile: () -> Unit,
    onShareFile: () -> Unit,
    onRename: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val categoryDetails = remember(item.fileName, item.mimeType) {
        resolveFileCategoryDetails(item.fileName, item.mimeType)
    }
    val file = item.file

    val imageLoader = remember(context) {
        ImageLoader.Builder(context)
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .crossfade(true)
            .build()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            val isCompactHeight = maxHeight < 550.dp
            val isCompactWidth = maxWidth < 360.dp
            val previewHeight = if (isCompactHeight) 140.dp else if (isCompactWidth) 190.dp else 230.dp

            Card(
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth(0.95f)
                    .wrapContentHeight(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(if (isCompactWidth) 14.dp else 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Header com Título e Botões de Ação
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Surface(
                                color = categoryDetails.containerColor,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.size(30.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = categoryDetails.icon,
                                        contentDescription = null,
                                        tint = categoryDetails.iconColor,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            Text(
                                text = "Pré-visualização",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (onRename != null) {
                                IconButton(onClick = onRename, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Default.Edit, contentDescription = "Renomear", modifier = Modifier.size(18.dp))
                                }
                            }
                            if (onDelete != null) {
                                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                                    Icon(
                                        Icons.Default.DeleteOutline,
                                        contentDescription = "Eliminar",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Fechar", modifier = Modifier.size(18.dp))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Área visual central de pré-visualização
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(previewHeight)
                            .clip(RoundedCornerShape(14.dp)),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (file != null && file.exists() && (categoryDetails.category == FileCategory.IMAGE || categoryDetails.category == FileCategory.VIDEO)) {
                                AsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(file)
                                        .crossfade(true)
                                        .build(),
                                    imageLoader = imageLoader,
                                    contentDescription = item.fileName,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize()
                                )

                                if (categoryDetails.category == FileCategory.VIDEO) {
                                    Surface(
                                        color = Color.Black.copy(alpha = 0.55f),
                                        shape = CircleShape,
                                        modifier = Modifier
                                            .size(50.dp)
                                            .clickable(onClick = onOpenFile)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Default.PlayArrow,
                                                contentDescription = "Reproduzir",
                                                tint = Color.White,
                                                modifier = Modifier.size(32.dp)
                                            )
                                        }
                                    }
                                }
                            } else {
                                DownloadThumbnailView(
                                    item = item,
                                    size = if (isCompactHeight) 80.dp else 100.dp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Informações do Arquivo
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                            .padding(10.dp)
                    ) {
                        Text(
                            text = item.fileName,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val sizeFormatted = formatFileSize(if (item.totalBytes > 0) item.totalBytes else file?.length() ?: 0L)
                            Text(
                                text = "Tamanho: $sizeFormatted",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Surface(
                                color = categoryDetails.iconColor.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = categoryDetails.extensionLabel,
                                    color = categoryDetails.iconColor,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        if (file != null && file.exists()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = file.parent ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 11.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Botões de Ação Responsivos
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onShareFile,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Partilhar", fontSize = if (isCompactWidth) 12.sp else 13.sp, maxLines = 1)
                        }

                        Button(
                            onClick = onOpenFile,
                            modifier = Modifier.weight(1.2f),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Abrir", fontSize = if (isCompactWidth) 12.sp else 13.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

// ---- Utilitários Nativos de Extração de Miniaturas ----

private fun extractPdfFirstPage(file: File, width: Int = 200, height: Int = 260): Bitmap? {
    return try {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        if (renderer.pageCount > 0) {
            val page = renderer.openPage(0)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            renderer.close()
            pfd.close()
            bitmap
        } else {
            renderer.close()
            pfd.close()
            null
        }
    } catch (e: Throwable) {
        null
    }
}

private fun extractAudioCover(file: File): Bitmap? {
    return try {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val art = retriever.embeddedPicture
            if (art != null) {
                BitmapFactory.decodeByteArray(art, 0, art.size)
            } else null
        } finally {
            try {
                retriever.release()
            } catch (ignored: Throwable) {}
        }
    } catch (e: Throwable) {
        null
    }
}

private fun extractApkIcon(context: Context, file: File): Drawable? {
    return try {
        val pm = context.packageManager
        val info = pm.getPackageArchiveInfo(file.absolutePath, 0) ?: return null
        val appInfo = info.applicationInfo ?: return null
        appInfo.sourceDir = file.absolutePath
        appInfo.publicSourceDir = file.absolutePath
        appInfo.loadIcon(pm)
    } catch (e: Throwable) {
        null
    }
}

private fun drawableToBitmap(drawable: Drawable): Bitmap? {
    return try {
        val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 96
        val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 96
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        bitmap
    } catch (e: Throwable) {
        null
    }
}

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return "0 KB"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
        mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
        kb >= 1.0 -> {
            if (kb >= 100.0) String.format(Locale.US, "%.0f KB", kb)
            else String.format(Locale.US, "%.1f KB", kb)
        }
        else -> "< 1 KB"
    }
}
