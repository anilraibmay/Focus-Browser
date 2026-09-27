package com.example.util

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.nio.ByteBuffer

/**
 * Utilitário para remuxing e fusão de streams de vídeo de alta resolução (DASH/HLS)
 * e áudio desacoplado em contêiner final MP4 utilizando FFmpegKit nativo
 * com fallback transparente para Android MediaMuxer.
 */
object AudioVideoRemuxer {

    private const val TAG = "AudioVideoRemuxer"
    private const val DEFAULT_BUFFER_SIZE = 1024 * 1024 // 1 MB

    private fun logD(tag: String, msg: String) {
        try { Log.d(tag, msg) } catch (_: Throwable) { println("[$tag] $msg") }
    }
    private fun logI(tag: String, msg: String) {
        try { Log.i(tag, msg) } catch (_: Throwable) { println("[$tag] $msg") }
    }
    private fun logW(tag: String, msg: String, tr: Throwable? = null) {
        try { Log.w(tag, msg, tr) } catch (_: Throwable) { println("[$tag] $msg") }
    }
    private fun logE(tag: String, msg: String, tr: Throwable? = null) {
        try { Log.e(tag, msg, tr) } catch (_: Throwable) { println("[$tag] $msg") }
    }

    /**
     * Une um arquivo de vídeo (DASH/HLS/MP4) e um arquivo de áudio (AAC/Opus/MP4/WebM)
     * gerando um arquivo final MP4 perfeitamente sincronizado.
     * Tenta primeiro via FFmpegKit nativo (suporta todos os formatos DASH, inclusive VP9 e Opus);
     * caso o FFmpeg falhe ou não consiga inicializar, faz fallback para o MediaMuxer do Android.
     */
    fun mux(videoFile: File, audioFile: File, outputFile: File): Boolean {
        if (!videoFile.exists() || videoFile.length() == 0L) {
            logE(TAG, "Arquivo de vídeo de entrada inválido ou inexistente: ${videoFile.path}")
            return false
        }
        if (!audioFile.exists() || audioFile.length() == 0L) {
            logE(TAG, "Arquivo de áudio de entrada inválido ou inexistente: ${audioFile.path}")
            return false
        }

        outputFile.parentFile?.mkdirs()

        // 1. Tentar com FFmpegKit nativo
        try {
            val ffmpegSuccess = muxWithFFmpeg(videoFile, audioFile, outputFile)
            if (ffmpegSuccess && outputFile.exists() && outputFile.length() > 0L) {
                logI(TAG, "Remuxing concluído via FFmpegKit com sucesso: ${outputFile.length()} bytes")
                return true
            }
        } catch (e: Throwable) {
            logW(TAG, "FFmpegKit encontrou erro, tentando fallback MediaMuxer", e)
        }

        // 2. Fallback para Android MediaMuxer
        logI(TAG, "Executando fallback com MediaMuxer...")
        return muxWithMediaMuxer(videoFile, audioFile, outputFile)
    }

    /**
     * Executa a união com FFmpegKit.
     * Tenta inicialmente `-c copy` (lossless, instantâneo).
     * Se falhar (ex: áudio Opus de DASH em WebM que requer AAC em MP4),
     * transcodifica a faixa de áudio para AAC mantendo o vídeo intocado (`-c:v copy -c:a aac`).
     */
    fun muxWithFFmpeg(
        videoFile: File,
        audioFile: File,
        outputFile: File
    ): Boolean {
        if (!videoFile.exists() || videoFile.length() == 0L || !audioFile.exists() || audioFile.length() == 0L) {
            return false
        }

        outputFile.parentFile?.mkdirs()
        if (outputFile.exists()) {
            outputFile.delete()
        }

        try {
            // Tentativa 1: Stream copy direto (-c:v copy -c:a copy)
            val cmdCopy = "-y -i \"${videoFile.absolutePath}\" -i \"${audioFile.absolutePath}\" -c:v copy -c:a copy -movflags +faststart \"${outputFile.absolutePath}\""
            logD(TAG, "Executando FFmpeg (-c copy): $cmdCopy")
            val session1 = FFmpegKit.execute(cmdCopy)

            if (session1 != null && ReturnCode.isSuccess(session1.returnCode) && outputFile.exists() && outputFile.length() > 0L) {
                logI(TAG, "FFmpeg stream copy concluído com êxito: ${outputFile.length()} bytes")
                return true
            }

            logW(TAG, "FFmpeg -c copy retornou código ${session1?.returnCode}. Tentando transcodificação de áudio para AAC...")

            if (outputFile.exists()) {
                outputFile.delete()
            }

            // Tentativa 2: Transcodificar áudio para AAC (necessário para faixas DASH Opus/Vorbis dentro de MP4)
            val cmdAac = "-y -i \"${videoFile.absolutePath}\" -i \"${audioFile.absolutePath}\" -c:v copy -c:a aac -b:a 192k -movflags +faststart \"${outputFile.absolutePath}\""
            logD(TAG, "Executando FFmpeg (-c:a aac): $cmdAac")
            val session2 = FFmpegKit.execute(cmdAac)

            if (session2 != null && ReturnCode.isSuccess(session2.returnCode) && outputFile.exists() && outputFile.length() > 0L) {
                logI(TAG, "FFmpeg com áudio AAC concluído com êxito: ${outputFile.length()} bytes")
                return true
            }

            logE(TAG, "FFmpeg falhou após tentativas. Logs:\n${session2?.allLogsAsString}")
            return false
        } catch (t: Throwable) {
            logE(TAG, "Falha na execução do FFmpegKit: ${t.message}", t)
            return false
        }
    }

    /**
     * Fallback utilizando as APIs de mídia padrão do Android (MediaExtractor + MediaMuxer).
     */
    fun muxWithMediaMuxer(videoFile: File, audioFile: File, outputFile: File): Boolean {
        if (outputFile.exists()) {
            outputFile.delete()
        }

        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null

        try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            audioExtractor.setDataSource(audioFile.absolutePath)

            // Localizar track de vídeo
            var videoTrackIndex = -1
            var videoFormat: MediaFormat? = null
            for (i in 0 until videoExtractor.trackCount) {
                val format = videoExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoTrackIndex = i
                    videoFormat = format
                    break
                }
            }

            // Localizar track de áudio
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until audioExtractor.trackCount) {
                val format = audioExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }

            if (videoTrackIndex == -1 || videoFormat == null) {
                logE(TAG, "Nenhuma faixa de vídeo encontrada no arquivo: ${videoFile.path}")
                return false
            }

            if (audioTrackIndex == -1 || audioFormat == null) {
                logE(TAG, "Nenhuma faixa de áudio encontrada no arquivo: ${audioFile.path}")
                return false
            }

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerVideoTrack = muxer.addTrack(videoFormat)
            val muxerAudioTrack = muxer.addTrack(audioFormat)

            muxer.start()

            // Copiar amostras de vídeo
            videoExtractor.selectTrack(videoTrackIndex)
            val videoBuffer = ByteBuffer.allocate(
                videoFormat.getIntegerOrNull(MediaFormat.KEY_MAX_INPUT_SIZE) ?: DEFAULT_BUFFER_SIZE
            )
            val videoBufferInfo = MediaCodec.BufferInfo()

            while (true) {
                val sampleSize = videoExtractor.readSampleData(videoBuffer, 0)
                if (sampleSize < 0) break
                videoBufferInfo.offset = 0
                videoBufferInfo.size = sampleSize
                videoBufferInfo.presentationTimeUs = videoExtractor.sampleTime
                videoBufferInfo.flags = videoExtractor.sampleFlags

                muxer.writeSampleData(muxerVideoTrack, videoBuffer, videoBufferInfo)
                videoExtractor.advance()
            }

            // Copiar amostras de áudio
            audioExtractor.selectTrack(audioTrackIndex)
            val audioBuffer = ByteBuffer.allocate(
                audioFormat.getIntegerOrNull(MediaFormat.KEY_MAX_INPUT_SIZE) ?: DEFAULT_BUFFER_SIZE
            )
            val audioBufferInfo = MediaCodec.BufferInfo()

            while (true) {
                val sampleSize = audioExtractor.readSampleData(audioBuffer, 0)
                if (sampleSize < 0) break
                audioBufferInfo.offset = 0
                audioBufferInfo.size = sampleSize
                audioBufferInfo.presentationTimeUs = audioExtractor.sampleTime
                audioBufferInfo.flags = audioExtractor.sampleFlags

                muxer.writeSampleData(muxerAudioTrack, audioBuffer, audioBufferInfo)
                audioExtractor.advance()
            }

            logI(TAG, "MediaMuxer concluído com sucesso: ${outputFile.absolutePath} (${outputFile.length()} bytes)")
            return true

        } catch (e: Exception) {
            logE(TAG, "Falha ao realizar remuxing com MediaMuxer: ${e.message}", e)
            if (outputFile.exists()) {
                outputFile.delete()
            }
            return false
        } finally {
            try {
                videoExtractor.release()
            } catch (ignored: Throwable) {}
            try {
                audioExtractor.release()
            } catch (ignored: Throwable) {}
            try {
                muxer?.stop()
            } catch (ignored: Throwable) {}
            try {
                muxer?.release()
            } catch (ignored: Throwable) {}
        }
    }

    private fun MediaFormat.getIntegerOrNull(key: String): Int? {
        return try {
            if (containsKey(key)) getInteger(key) else null
        } catch (e: Exception) {
            null
        }
    }
}
