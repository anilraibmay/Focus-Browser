package com.example.util

import com.example.extractor.ExtractedVideo
import com.example.extractor.MediaTitleHelper
import com.example.extractor.VideoType

/**
 * Utilitário responsável por gerar nomes de arquivos organizados para vídeos
 * baixados de uma página web, com suporte nativo à nomenclatura inteligente de séries/episódios (ABYS Toolkit):
 * - Série/Episódio: "A Shop for Killers - S01E01 - 720p.mp4"
 * - 1 vídeo comum: páginatítulo.mp4
 * - Múltiplos vídeos: páginatítulo.mp4, páginatítulo(1).mp4, páginatítulo(2).mp4, etc.
 * Também evita colisões com downloads já existentes, incrementando o índice sequencialmente.
 */
object VideoFileNameGenerator {

    /**
     * Gera uma lista de nomes de arquivos correspondente a cada vídeo selecionado para download.
     */
    fun generateVideoFileNames(
        pageTitle: String?,
        videos: List<ExtractedVideo>,
        existingNames: Set<String>
    ): List<String> {
        val sanitizedBaseTitle = sanitizePageTitle(pageTitle)
        val usedNames = existingNames.map { it.lowercase() }.toMutableSet()
        val result = mutableListOf<String>()

        videos.forEachIndexed { index, video ->
            val extension = when {
                video.type == VideoType.AUDIO -> {
                    if (video.url.contains(".m4a", ignoreCase = true) || video.quality?.contains("M4A", ignoreCase = true) == true) "m4a"
                    else if (video.url.contains(".mp3", ignoreCase = true)) "mp3"
                    else "m4a"
                }
                video.url.contains(".webm", ignoreCase = true) -> "webm"
                else -> "mp4"
            }

            val baseTitleForVideo = if (video.type == VideoType.ABYS || video.url.contains(".fd") || video.url.contains("/sora/")) {
                val slug = video.headers["X-Abys-Slug"] ?: AbysNameResolver.extractSlug(video.url)
                val cleanRes = video.quality?.substringBefore(' ')
                val meta = AbysNameResolver.resolveMetadata(
                    title = video.title.takeIf { !it.startsWith("Vídeo ABYS") } ?: pageTitle,
                    slug = slug,
                    md5Id = video.headers["X-Abys-Md5Id"],
                    userId = video.headers["X-Abys-UserId"],
                    overrideResolution = cleanRes
                )
                meta.suggestedFileName.removeSuffix(".mp4")
            } else {
                val parsedEp = AbysNameResolver.parseFields(video.title)
                if (parsedEp.season != null && parsedEp.episode != null) {
                    val meta = AbysNameResolver.resolveMetadata(
                        title = video.title,
                        overrideResolution = video.quality?.substringBefore(' ')
                    )
                    meta.suggestedFileName.removeSuffix(".mp4")
                } else {
                    sanitizedBaseTitle
                }
            }

            val fileName = resolveNextAvailableName(baseTitleForVideo, extension, index, usedNames)
            usedNames.add(fileName.lowercase())
            result.add(fileName)
        }

        return result
    }

    /**
     * Sanitiza o título da página removendo caracteres proibidos no sistema de arquivos,
     * sufixos redundantes de plataformas e espaços duplicados.
     */
    fun sanitizePageTitle(rawTitle: String?): String {
        val cleaned = MediaTitleHelper.cleanMediaTitle(rawTitle, "")
        val sanitized = cleaned
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.', ' ')
            .take(80)
        return if (sanitized.isNotBlank()) sanitized else "video"
    }

    private fun resolveNextAvailableName(
        baseTitle: String,
        extension: String,
        batchIndex: Int,
        usedNames: Set<String>
    ): String {
        // Se for o primeiro do lote e o nome base puro estiver livre, usa diretamente
        if (batchIndex == 0) {
            val candidate = "$baseTitle.$extension"
            if (!usedNames.contains(candidate.lowercase())) {
                return candidate
            }
        }

        // Se for o segundo em diante (ou se o nome base já existir), numera com (1), (2), etc.
        var counter = if (batchIndex == 0) 1 else batchIndex
        while (true) {
            val candidate = "$baseTitle($counter).$extension"
            if (!usedNames.contains(candidate.lowercase())) {
                return candidate
            }
            counter++
        }
    }
}
