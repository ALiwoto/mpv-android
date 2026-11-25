package `is`.xyz.mpv

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class CaptureSettings(
    val telegram: Boolean = true,
    val botToken: String = "",
    val chatId: String = "",
    val sendAsDocument: Boolean = false,
    val subtitles: Boolean = true,
    val sendAsGif: Boolean = false,
    val caption: String = ""
) {
    companion object {
        private const val KEY_ALIAS = "mpv-capture-settings"

        private fun key(): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val existing = store.getKey(KEY_ALIAS, null) as? SecretKey
            if (existing != null) {
                return existing
            }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build())
            }.generateKey()
        }

        fun load(context: Context): CaptureSettings {
            val file = File(context.noBackupFilesDir, "capture-settings")
            if (!file.exists()) {
                return CaptureSettings()
            }
            val bytes = android.util.AtomicFile(file).readFully()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            val json = JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
            return CaptureSettings(
                telegram = json.optBoolean("telegram", true),
                botToken = json.optString("botToken"),
                chatId = json.optString("chatId"),
                sendAsDocument = json.optBoolean("document"),
                subtitles = json.optBoolean("subtitles", true),
                sendAsGif = json.optBoolean("gif")
            )
        }
    }

    fun save(context: Context) {
        val json = JSONObject()
            .put("telegram", telegram)
            .put("botToken", botToken)
            .put("chatId", chatId)
            .put("document", sendAsDocument)
            .put("subtitles", subtitles)
            .put("gif", sendAsGif)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        val file = android.util.AtomicFile(File(context.noBackupFilesDir, "capture-settings"))
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }
}
