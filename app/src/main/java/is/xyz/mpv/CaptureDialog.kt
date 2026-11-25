package `is`.xyz.mpv

import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import `is`.xyz.mpv.databinding.DialogCaptureBinding

internal class CaptureDialog(
    private val activity: AppCompatActivity,
    private val recording: Boolean,
    private val audioTracks: List<MPVView.Track>,
    private val selectedAudio: Int,
    private val interval: String?,
    private val onConfirm: (CaptureSettings, Int) -> Boolean,
    private val onDismiss: () -> Unit
) {
    fun show() {
        val binding = DialogCaptureBinding.inflate(activity.layoutInflater)
        val saved = try {
            CaptureSettings.load(activity)
        } catch (error: Exception) {
            Toast.makeText(activity, R.string.capture_settings_unreadable, Toast.LENGTH_LONG).show()
            CaptureSettings()
        }
        with(binding) {
            subtitleCheck.isVisible = !recording
            subtitleCheck.isChecked = saved.subtitles
            gifCheck.isVisible = recording
            gifCheck.isChecked = saved.sendAsGif
            recordingInfo.isVisible = recording
            recordingInfo.text = interval
            audioGroup.isVisible = recording
            botToken.setText(saved.botToken)
            chatId.setText(saved.chatId)
            documentCheck.isChecked = saved.sendAsDocument
            storeMethod.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item,
                listOf(activity.getString(R.string.capture_telegram), activity.getString(R.string.capture_local)))
            storeMethod.setSelection(if (saved.telegram) 0 else 1)
            storeMethod.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    telegramGroup.isVisible = position == 0
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
            telegramExpand.setOnClickListener {
                telegramFields.isVisible = !telegramFields.isVisible
                telegramExpand.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0,
                    if (telegramFields.isVisible) android.R.drawable.arrow_up_float else android.R.drawable.arrow_down_float, 0)
            }
        }

        val none = MPVView.Track(-1, activity.getString(R.string.capture_audio_none))
        val tracks = listOf(none) + audioTracks.filter { it.mpvId != -1 }
        var audio = tracks.firstOrNull { it.mpvId == selectedAudio } ?: none
        fun updateGif() {
            val gif = recording && binding.gifCheck.isChecked
            binding.audioChoice.isEnabled = !gif
            binding.audioChoice.text = if (gif) none.name else audio.name
            binding.documentCheck.isEnabled = !gif
        }
        binding.gifCheck.setOnCheckedChangeListener { _, _ -> updateGif() }
        binding.audioChoice.setOnClickListener {
            val search = android.widget.EditText(activity).apply {
                hint = activity.getString(R.string.capture_audio_search)
                setSingleLine()
            }
            val list = android.widget.ListView(activity)
            val adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_1, tracks.map { it.name })
            list.adapter = adapter
            val layout = android.widget.LinearLayout(activity).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                addView(search)
                addView(list)
            }
            val picker = AlertDialog.Builder(activity)
                .setTitle(R.string.capture_audio)
                .setView(layout)
                .setNegativeButton(android.R.string.cancel, null)
                .create()
            search.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    adapter.filter.filter(s)
                }
                override fun afterTextChanged(s: android.text.Editable?) = Unit
            })
            list.setOnItemClickListener { _, _, position, _ ->
                audio = tracks.first { it.name == adapter.getItem(position) }
                updateGif()
                picker.dismiss()
            }
            picker.show()
        }
        updateGif()

        val dialog = AlertDialog.Builder(activity)
            .setTitle(if (recording) R.string.capture_record else R.string.capture_screenshot)
            .setView(binding.root)
            .setPositiveButton(R.string.capture_confirm, null)
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { onDismiss() }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val options = saved.copy(
                    telegram = binding.storeMethod.selectedItemPosition == 0,
                    botToken = binding.botToken.text.toString().trim(),
                    chatId = binding.chatId.text.toString().trim(),
                    caption = binding.caption.text.toString(),
                    sendAsDocument = binding.documentCheck.isChecked,
                    subtitles = binding.subtitleCheck.isChecked,
                    sendAsGif = binding.gifCheck.isChecked
                )
                if (options.telegram && !Regex("[0-9]+:[A-Za-z0-9_-]+").matches(options.botToken)) {
                    binding.telegramFields.isVisible = true
                    binding.botToken.error = activity.getString(R.string.capture_token_required)
                    return@setOnClickListener
                }
                if (options.telegram && options.chatId.isEmpty()) {
                    binding.telegramFields.isVisible = true
                    binding.chatId.error = activity.getString(R.string.capture_chat_required)
                    return@setOnClickListener
                }
                if (options.telegram && options.caption.codePointCount(0, options.caption.length) > 1024) {
                    binding.telegramFields.isVisible = true
                    binding.caption.error = activity.getString(R.string.capture_caption_limit)
                    return@setOnClickListener
                }
                try {
                    options.save(activity)
                    if (onConfirm(options, if (recording && options.sendAsGif) -1 else audio.mpvId)) {
                        dialog.dismiss()
                    }
                } catch (error: Exception) {
                    Toast.makeText(activity, error.localizedMessage, Toast.LENGTH_LONG).show()
                }
            }
        }
        dialog.show()
    }
}
