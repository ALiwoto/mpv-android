package `is`.xyz.mpv

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

internal class CaptureController(
    private val activity: MPVActivity,
    private val player: MPVView,
    private val pauseForDialog: () -> StateRestoreCallback,
    private val onMenuVisibilityChanged: () -> Unit
) {
    private data class Marker(val path: String, val generation: Long, val time: Double)

    @Volatile private var generation = 0L
    @Volatile private var marker: Marker? = null
    @Volatile private var closed = false
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var exporter: ClipExporter? = null
    private var upload: TelegramUpload? = null
    private var progress: AlertDialog? = null
    private var restorePlayback: StateRestoreCallback? = null
    private var busy = false
    private var pendingSave: File? = null
    private var menu: PopupWindow? = null

    val isMenuShowing: Boolean
        get() = menu?.isShowing == true

    private val saveLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val file = pendingSave ?: return@registerForActivityResult
        pendingSave = null
        val destination = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || destination == null) {
            offerSave(file, activity.getString(R.string.capture_save_cancelled))
        } else {
            save(file, destination)
        }
    }

    fun onFileStarted() {
        generation++
        marker = null
    }

    fun close() {
        closed = true
        menu?.dismiss()
        exporter?.cancel()
        upload?.cancel()
        progress?.dismiss()
        executor.shutdownNow()
    }

    fun showMenu(anchor: View) {
        if (isMenuShowing) {
            menu?.dismiss()
            return
        }
        val density = activity.resources.displayMetrics.density
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (12 * density).toInt()
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(android.graphics.Color.rgb(40, 40, 40))
        }
        val popup = PopupWindow(content,
            (260 * activity.resources.displayMetrics.density).toInt(),
            LinearLayout.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            elevation = 12 * activity.resources.displayMetrics.density
            isOutsideTouchable = true
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            setOnDismissListener {
                menu = null
                if (!closed) {
                    onMenuVisibilityChanged()
                }
            }
        }
        fun measureMenu() {
            // PopupWindow positions WRAP_CONTENT using the negative layout constant,
            // not the content height. Measure before asking it to fit above/below the anchor.
            content.measure(
                View.MeasureSpec.makeMeasureSpec(popup.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(popup.getMaxAvailableHeight(anchor), View.MeasureSpec.AT_MOST)
            )
        }
        fun button(parent: LinearLayout, title: Int, action: () -> Unit): Button {
            val button = Button(activity, null, android.R.attr.borderlessButtonStyle).apply {
                setText(title)
                setTextColor(android.graphics.Color.WHITE)
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setOnClickListener { action() }
            }
            parent.addView(button, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            return button
        }
        button(content, R.string.capture_screenshot) {
            popup.dismiss()
            showSettings(false)
        }
        val record = button(content, R.string.capture_record) {}
        val submenu = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding((16 * resources.displayMetrics.density).toInt(), 0, 0, 0)
        }
        content.addView(submenu)
        fun expandRecord() {
            if (submenu.visibility == View.VISIBLE) {
                return
            }
            submenu.visibility = View.VISIBLE
            measureMenu()
            popup.update(anchor, popup.width, content.measuredHeight)
        }
        record.setOnClickListener { expandRecord() }
        record.setOnHoverListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_HOVER_ENTER) {
                expandRecord()
            }
            false
        }
        button(submenu, R.string.capture_begin) {
            popup.dismiss()
            markBegin()
        }
        button(submenu, R.string.capture_end) {
            popup.dismiss()
            showSettings(true)
        }
        measureMenu()
        popup.height = content.measuredHeight
        menu = popup
        popup.showAsDropDown(anchor, 0, 0, Gravity.END)
        onMenuVisibilityChanged()
    }

    private fun markBegin() {
        val path = MPVLib.getPropertyString("path")
        val time = player.timePos
        if (path == null || time == null || MPVLib.getPropertyBoolean("seekable") != true) {
            toast(R.string.capture_seekable_required)
            return
        }
        marker = Marker(path, generation, time)
        Toast.makeText(activity, activity.getString(R.string.capture_marked, timestamp(time)), Toast.LENGTH_LONG).show()
    }

    private fun showSettings(recording: Boolean) {
        if (busy || pendingSave != null) {
            toast(R.string.capture_busy)
            return
        }
        val path = MPVLib.getPropertyString("path")
        if (path == null || player.vid == -1) {
            toast(R.string.capture_video_required)
            return
        }
        val start = marker
        val end = player.timePos
        val currentGeneration = generation
        if (recording && (start == null || start.path != path || start.generation != currentGeneration)) {
            toast(R.string.capture_begin_required)
            return
        }
        if (recording && (end == null || end <= start!!.time)) {
            toast(R.string.capture_invalid_interval)
            return
        }
        val restore = pauseForDialog()
        var captureStarted = false
        player.loadTracks()
        val interval = if (recording) activity.getString(R.string.capture_interval,
            timestamp(start!!.time), timestamp(end!!)) else null
        CaptureDialog(activity, recording, player.tracks.getValue("audio").toList(), player.aid, interval,
            onConfirm = { settings, audio ->
                if (generation != currentGeneration || MPVLib.getPropertyString("path") != path) {
                    toast(R.string.capture_source_changed)
                    false
                } else {
                    confirm(settings, recording, path, start?.time ?: 0.0, end ?: 0.0, audio)
                    captureStarted = true
                    restorePlayback = {
                        if (generation == currentGeneration) {
                            restore()
                        }
                    }
                    true
                }
            },
            onDismiss = {
                if (!closed && !captureStarted && generation == currentGeneration) {
                    restore()
                }
            }).show()
    }

    private fun confirm(settings: CaptureSettings, recording: Boolean, path: String, start: Double, end: Double, audioId: Int) {
        val clip = if (recording) {
            val video = CaptureTrack.read("video", player.vid)
            val width = MPVLib.getPropertyInt("video-params/w") ?: 0
            val height = MPVLib.getPropertyInt("video-params/h") ?: 0
            val pixelFormat = MPVLib.getPropertyString("video-params/hw-pixelformat")
                ?: MPVLib.getPropertyString("video-params/pixelformat")
            val gifCanCopy = video.codec == "h264" && width in 2..1280 && height in 2..1280 &&
                width % 2 == 0 && height % 2 == 0 &&
                MPVLib.getPropertyDouble("video-params/par") == 1.0 &&
                pixelFormat in setOf("yuv420p", "nv12")
            val headers = MPVLib.getPropertyInt("http-header-fields/count")?.let { count ->
                (0 until count).mapNotNull { MPVLib.getPropertyString("http-header-fields/$it") }
            }.orEmpty()
            ClipRequest(path, start, end, video,
                if (audioId == -1) null else CaptureTrack.read("audio", audioId),
                gifCanCopy,
                MPVLib.getPropertyString("user-agent"), MPVLib.getPropertyString("referrer"), headers)
        } else null
        val extension = if (recording) {
            if (settings.sendAsGif || (settings.telegram && !settings.sendAsDocument)) "mp4" else "mkv"
        } else {
            if (settings.telegram && !settings.sendAsDocument) "jpg" else "png"
        }
        val directory = File(activity.cacheDir, "captures")
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create the capture directory." }
        val output = File.createTempFile("mpv-", ".$extension", directory)
        try {
            if (!recording) {
                // Capture the displayed frame on Confirm, before the dialog resumes playback.
                val status = MPVLib.commandWithResult(arrayOf("screenshot-to-file", output.path,
                    if (settings.subtitles) "subtitles" else "video"))
                check(status >= 0 && output.length() > 0) { "mpv could not capture the current frame (error $status)." }
            }
        } catch (error: Exception) {
            output.delete()
            throw error
        }
        val clipExporter = ClipExporter(activity.applicationContext)
        val telegramUpload = TelegramUpload()
        exporter = clipExporter
        upload = telegramUpload
        showProgress(when {
            recording -> R.string.capture_exporting
            settings.telegram -> R.string.capture_uploading
            else -> R.string.capture_saving
        }) {
            clipExporter.cancel()
            telegramUpload.cancel()
        }
        executor.execute {
            var exported = !recording
            try {
                if (clip != null) {
                    clipExporter.export(clip, settings, output)
                    exported = true
                }
                if (settings.telegram) {
                    onUi { progress?.setMessage(activity.getString(R.string.capture_uploading)) }
                    telegramUpload.send(output, settings, recording)
                    output.delete()
                    onUi {
                        finishProgress()
                        toast(R.string.capture_sent)
                    }
                } else {
                    onUi {
                        dismissProgress()
                        saveLocally(output)
                    }
                }
            } catch (error: Exception) {
                if (!exported) {
                    output.delete()
                }
                onUi {
                    if (exported) {
                        dismissProgress()
                        offerSave(output, error.localizedMessage ?: "Capture delivery failed.")
                    } else {
                        finishProgress()
                        AlertDialog.Builder(activity).setTitle(R.string.capture_failed)
                            .setMessage(error.localizedMessage)
                            .setPositiveButton(android.R.string.ok, null).show()
                    }
                }
            }
        }
    }

    private fun chooseDestination(file: File) {
        busy = true
        pendingSave = file
        try {
            saveLauncher.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = mimeType(file)
                putExtra(Intent.EXTRA_TITLE, file.name)
            })
        } catch (error: Exception) {
            pendingSave = null
            offerSave(file, activity.getString(R.string.capture_picker_failed, error.localizedMessage))
        }
    }

    private fun saveLocally(file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            save(file)
        } else {
            chooseDestination(file)
        }
    }

    private fun mimeType(file: File): String = when (file.extension) {
        "png" -> "image/png"
        "jpg" -> "image/jpeg"
        "mp4" -> "video/mp4"
        else -> "video/x-matroska"
    }

    private fun save(file: File, destination: Uri? = null) {
        showProgress(R.string.capture_saving)
        executor.execute {
            var created: Uri? = null
            try {
                val resolver = activity.contentResolver
                val uri = if (destination != null) {
                    destination
                } else {
                    check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    val video = mimeType(file).startsWith("video/")
                    val collection = if (video) {
                        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    } else {
                        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    }
                    val directory = if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                        put(MediaStore.MediaColumns.MIME_TYPE, mimeType(file))
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "$directory/mpv")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    checkNotNull(resolver.insert(collection, values)) { "Cannot create a file in Local Storage." }
                        .also { created = it }
                }
                resolver.openOutputStream(uri, "wt").use { output ->
                    checkNotNull(output) { "Cannot open the selected destination." }
                    file.inputStream().use { it.copyTo(output, 64 * 1024) }
                }
                if (created != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    resolver.update(uri, ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }, null, null)
                }
                file.delete()
                onUi {
                    finishProgress()
                    toast(R.string.capture_saved)
                }
            } catch (error: Exception) {
                created?.let { uri ->
                    try {
                        activity.contentResolver.delete(uri, null, null)
                    } catch (_: Exception) {
                        // Preserve the original write failure for the recovery dialog.
                    }
                }
                onUi {
                    dismissProgress()
                    offerSave(file, error.localizedMessage ?: "Could not save the capture.")
                }
            }
        }
    }

    private fun offerSave(file: File, message: String) {
        busy = true
        AlertDialog.Builder(activity)
            .setTitle(R.string.capture_save_copy)
            .setMessage(message)
            .setPositiveButton(R.string.capture_local) { _, _ -> saveLocally(file) }
            .setNegativeButton(R.string.capture_discard) { _, _ ->
                file.delete()
                finishProgress()
            }
            .setCancelable(false)
            .show()
    }

    private fun showProgress(message: Int, cancel: (() -> Unit)? = null) {
        busy = true
        val builder = AlertDialog.Builder(activity)
            .setMessage(message)
            .setCancelable(false)
        if (cancel != null) {
            builder.setNegativeButton(android.R.string.cancel, null)
        }
        progress = builder.create().also { dialog ->
            dialog.setOnShowListener {
                if (cancel != null) {
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
                        dialog.setMessage(activity.getString(R.string.capture_cancelling))
                        cancel()
                    }
                }
            }
            dialog.show()
        }
    }

    private fun dismissProgress() {
        progress?.dismiss()
        progress = null
        exporter = null
        upload = null
        busy = false
    }

    private fun finishProgress() {
        dismissProgress()
        restorePlayback?.invoke()
        restorePlayback = null
    }

    private fun onUi(action: () -> Unit) {
        handler.post {
            if (!closed && !activity.isFinishing && !activity.isDestroyed) {
                action()
            }
        }
    }

    private fun toast(message: Int) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }

    private fun timestamp(seconds: Double): String {
        val whole = seconds.toLong()
        return String.format(Locale.ROOT, "%02d:%02d:%06.3f", whole / 3600, whole / 60 % 60, seconds % 60)
    }
}
