package `is`.xyz.mpv

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import java.io.Closeable
import java.io.File
import java.io.IOException

internal data class CaptureTrack(
    val index: Int,
    val codec: String,
    val externalFile: String?
) {
    companion object {
        fun read(type: String, id: Int): CaptureTrack {
            val count = MPVLib.getPropertyInt("track-list/count") ?: 0
            for (i in 0 until count) {
                val prefix = "track-list/$i"
                if (MPVLib.getPropertyString("$prefix/type") == type &&
                    MPVLib.getPropertyInt("$prefix/id") == id) {
                    val index = MPVLib.getPropertyInt("$prefix/ff-index")
                        ?: error("This track cannot be mapped to an export stream.")
                    check(index >= 0) { "This track has no exportable stream index." }
                    return CaptureTrack(index, MPVLib.getPropertyString("$prefix/codec").orEmpty(),
                        MPVLib.getPropertyString("$prefix/external-filename"))
                }
            }
            error("The selected $type track is no longer available.")
        }
    }
}

internal data class ClipRequest(
    val path: String,
    val start: Double,
    val end: Double,
    val video: CaptureTrack,
    val audio: CaptureTrack?,
    val gifCanCopy: Boolean,
    val userAgent: String?,
    val referrer: String?,
    val headers: List<String>
)

internal class ClipExporter(private val context: Context) {
    @Volatile private var process: java.lang.Process? = null
    @Volatile private var cancelled = false

    fun cancel() {
        cancelled = true
        process?.destroy()
    }

    fun export(request: ClipRequest, settings: CaptureSettings, output: File) {
        val executable = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        check(executable.canExecute()) { "The APK is missing its FFmpeg export executable. Rebuild the native dependencies." }
        val gif = settings.sendAsGif
        val telegramVideo = settings.telegram && !settings.sendAsDocument && !gif
        val encodeVideo = (gif && !request.gifCanCopy) || (telegramVideo && request.video.codec != "h264")
        check(!encodeVideo || Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            "Hardware video conversion requires Android 8 or later. Save the original clip as a document instead."
        }

        Input(request.video.externalFile ?: request.path).use { videoInput ->
            val audioFile = request.audio?.externalFile
            val audioInput = audioFile?.let { Input(it) }
            try {
                val command = mutableListOf(executable.path, "-hide_banner", "-nostdin", "-y", "-loglevel", "warning")
                fun addInput(input: Input) {
                    command += listOf("-ss", request.start.toString())
                    if (input.path.startsWith("https://") || input.path.startsWith("http://")) {
                        command += listOf("-rw_timeout", "30000000")
                        request.userAgent?.let { command += listOf("-user_agent", it) }
                        request.referrer?.let { command += listOf("-referer", it) }
                        if (request.headers.isNotEmpty()) {
                            command += listOf("-headers", request.headers.joinToString("\r\n", postfix = "\r\n"))
                        }
                        command += listOf("-tls_verify", "1", "-ca_file", File(context.filesDir, "cacert.pem").path)
                    }
                    command += listOf("-i", input.path)
                }
                addInput(videoInput)
                if (audioInput != null) {
                    addInput(audioInput)
                }
                command += listOf("-t", (request.end - request.start).toString(),
                    "-map", "0:${request.video.index}", "-sn", "-dn", "-map_metadata", "-1", "-map_chapters", "-1")
                if (request.audio == null || gif) {
                    command += "-an"
                } else {
                    command += listOf("-map", "${if (audioInput == null) 0 else 1}:${request.audio.index}")
                    if (telegramVideo && request.audio.codec != "aac") {
                        command += listOf("-c:a", "aac", "-b:a", "192k", "-ac", "2")
                    } else {
                        command += listOf("-c:a", "copy")
                    }
                }
                if (encodeVideo) {
                    // Clamp both axes together. reset_sar also preserves anamorphic display aspect.
                    val filter = if (gif) {
                        "scale=w='min(iw,1280)':h='min(ih,1280)':force_original_aspect_ratio=decrease:force_divisible_by=2:reset_sar=1,format=nv12"
                    } else {
                        "scale=w='min(iw,1920)':h='min(ih,1920)':force_original_aspect_ratio=decrease:force_divisible_by=2:reset_sar=1,format=nv12"
                    }
                    command += listOf("-vf", filter, "-c:v", "h264_mediacodec", "-ndk_codec", "1",
                        "-b:v", if (gif) "2500k" else "6000k", "-fpsmax", "30")
                } else {
                    command += listOf("-c:v", "copy")
                }
                command += listOf("-avoid_negative_ts", "make_zero")
                if (output.extension == "mp4") {
                    command += listOf("-movflags", "+faststart")
                }
                command += output.path
                check(!cancelled) { "Export cancelled." }
                val builder = ProcessBuilder(command).redirectErrorStream(true)
                builder.environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir
                val running = builder.start()
                process = running
                if (cancelled) {
                    running.destroy()
                }
                // Drain stderr continuously without retaining a video's worth of logs in memory.
                val diagnostics = StringBuilder()
                try {
                    running.inputStream.bufferedReader().use { reader ->
                        val buffer = CharArray(2048)
                        while (true) {
                            val count = reader.read(buffer)
                            if (count < 0) {
                                break
                            }
                            diagnostics.append(buffer, 0, count)
                            if (diagnostics.length > 8192) {
                                diagnostics.delete(0, diagnostics.length - 8192)
                            }
                        }
                    }
                    val status = running.waitFor()
                    check(!cancelled) { "Export cancelled." }
                    if (status != 0 || output.length() == 0L) {
                        val suggestion = if (encodeVideo) {
                            "\nTry Local Storage or Send as document with GIF turned off to save the original video without conversion."
                        } else {
                            "\nCheck that the source is still available and supports seeking."
                        }
                        throw IOException("Clip export failed (FFmpeg $status).$suggestion\n${diagnostics.toString().takeLast(3000)}")
                    }
                } finally {
                    running.destroy()
                    process = null
                }
            } finally {
                audioInput?.close()
            }
        }
    }

    private inner class Input(source: String) : Closeable {
        private val descriptor: ParcelFileDescriptor?
        val path: String

        init {
            if (source.startsWith("content://")) {
                descriptor = context.contentResolver.openFileDescriptor(Uri.parse(source), "r")
                    ?: throw IOException("Cannot open the selected video.")
                // Reopen the held descriptor in the child, without copying the source video.
                path = "/proc/${Process.myPid()}/fd/${descriptor.fd}"
            } else {
                descriptor = null
                path = if (source.startsWith("file://")) Uri.parse(source).path!! else source
            }
        }

        override fun close() {
            descriptor?.close()
        }
    }
}
