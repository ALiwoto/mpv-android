package `is`.xyz.mpv

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URL
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

internal class TelegramUpload {
    @Volatile private var connection: HttpsURLConnection? = null
    @Volatile private var cancelled = false

    fun cancel() {
        cancelled = true
        connection?.disconnect()
    }

    fun send(file: File, settings: CaptureSettings, recording: Boolean) {
        val kind = when {
            recording && settings.sendAsGif -> "animation"
            settings.sendAsDocument -> "document"
            recording -> "video"
            else -> "photo"
        }
        val limit = if (kind == "photo") 10_000_000L else 50_000_000L
        check(file.length() <= limit) {
            if (kind == "photo") "The photo exceeds Telegram's 10 MB limit. Use Send as document or Local Storage."
            else "The clip exceeds Telegram's 50 MB bot upload limit. Choose a shorter clip or Local Storage."
        }
        val method = "send" + kind.replaceFirstChar { it.uppercase() }
        val boundary = "mpv-${UUID.randomUUID()}"
        val fields = linkedMapOf("chat_id" to settings.chatId, "caption" to settings.caption)
        if (kind == "video") {
            fields["supports_streaming"] = "true"
        }
        if (kind == "document") {
            fields["disable_content_type_detection"] = "true"
        }
        val mime = when (file.extension) {
            "png" -> "image/png"
            "jpg" -> "image/jpeg"
            "mp4" -> "video/mp4"
            else -> "video/x-matroska"
        }
        val prefix = buildString {
            for ((name, value) in fields) {
                append("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
            }
            append("--$boundary\r\nContent-Disposition: form-data; name=\"$kind\"; filename=\"${file.name}\"\r\n")
            append("Content-Type: $mime\r\n\r\n")
        }.toByteArray(Charsets.UTF_8)
        val suffix = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        val request = URL("https://api.telegram.org/bot${settings.botToken}/$method").openConnection() as HttpsURLConnection
        connection = request
        try {
            check(!cancelled) { "Upload cancelled." }
            request.requestMethod = "POST"
            request.instanceFollowRedirects = false
            request.connectTimeout = 30_000
            request.readTimeout = 120_000
            request.doOutput = true
            request.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            request.setFixedLengthStreamingMode(prefix.size.toLong() + file.length() + suffix.size)
            request.outputStream.buffered().use { output ->
                output.write(prefix)
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        check(!cancelled) { "Upload cancelled." }
                        val count = input.read(buffer)
                        if (count < 0) {
                            break
                        }
                        output.write(buffer, 0, count)
                    }
                }
                output.write(suffix)
            }
            val code = request.responseCode
            val stream = if (code in 200..299) request.inputStream else request.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = try {
                JSONObject(response)
            } catch (_: Exception) {
                throw IOException("Telegram returned HTTP $code without a valid response.")
            }
            if (code !in 200..299 || !json.optBoolean("ok")) {
                throw IOException("Telegram: ${json.optString("description", "HTTP $code")}")
            }
        } catch (error: IOException) {
            // Network exceptions can include the URL, whose path contains the bot token.
            throw IOException(error.message?.replace(settings.botToken, "[bot token]"))
        } finally {
            request.disconnect()
            connection = null
        }
    }
}
