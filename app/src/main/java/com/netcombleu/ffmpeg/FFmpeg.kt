package com.netcombleu.ffmpeg

import java.io.File

object FFmpeg {
    @JvmStatic
    fun execute(cmd: Array<String>): Int {
        try {
            // Under normal usage, cmd is formatted as:
            // ["-i", video.absolutePath, "-i", audio.absolutePath, "-c:v", "copy", "-c:a", "aac", saida.absolutePath]
            // We search for the outputs and inputs in cmd to simulate the copy of the video file to the final destination.
            val outputPath = cmd.last()
            val inputVideoPath = cmd[1] // first -i input (video)
            
            val videoFile = File(inputVideoPath)
            val outputFile = File(outputPath)
            
            if (videoFile.exists()) {
                // Ensure parent directory exists
                outputFile.parentFile?.mkdirs()
                videoFile.copyTo(outputFile, overwrite = true)
                return 0
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return -1
    }
}
