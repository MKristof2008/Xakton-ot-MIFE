package ru.mifi.neurosteng

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.mifi.neurosteng.databinding.ActivityMainBinding
import java.io.File

/**
 * Экран приложения: выбрать аудио -> дождаться расшифровки -> увидеть реплики.
 * Всё локально, сеть не используется (пункт «жёсткие ограничения» ТЗ).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: TranscriptAdapter
    private lateinit var whisper: WhisperBridge
    private var lastResult: List<HexJson.Segment> = emptyList()
    private var lastWav: File? = null

    private val pickAudio = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { onAudioPicked(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = TranscriptAdapter()
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        whisper = WhisperBridge(this)

        binding.buttonPick.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "audio/*"
            }
            pickAudio.launch(intent)
        }

        binding.buttonExport.setOnClickListener {
            val file = saveJson()
            Toast.makeText(this, if (file != null) "Сохранено: ${file.name}" else "Нечего сохранять",
                Toast.LENGTH_LONG).show()
        }
    }

    private fun onAudioPicked(uri: Uri) {
        val name = queryDisplayName(uri) ?: "audio"
        binding.status.text = getString(R.string.status_converting)

        lifecycleScope.launch {
            val wav = withContext(Dispatchers.IO) { AudioConverter.toWav16kMono(this@MainActivity, uri) }
            if (wav == null) {
                binding.status.text = getString(R.string.status_convert_failed)
                return@launch
            }
            lastWav = wav
            binding.status.text = getString(R.string.status_loading_model)

            val ok = withContext(Dispatchers.IO) { whisper.load() }
            if (!ok) {
                binding.status.text = getString(R.string.status_model_missing)
                return@launch
            }

            binding.progress.visibility = View.VISIBLE
            binding.status.text = getString(R.string.status_transcribing)
            val durationMs = withContext(Dispatchers.IO) { AudioConverter.durationMs(wav) }

            val segments = withContext(Dispatchers.IO) { whisper.transcribe(wav.absolutePath) }
            val cleaned = HexJson.dropOutOfBounds(HexJson.normalizeSpeakers(segments), durationMs)

            binding.progress.visibility = View.GONE
            lastResult = cleaned
            adapter.submit(cleaned)
            binding.status.text = if (cleaned.isEmpty()) {
                getString(R.string.status_empty)
            } else {
                getString(R.string.status_done, cleaned.size, name)
            }
        }
    }

    private fun saveJson(): File? {
        if (lastResult.isEmpty()) return null
        val errors = HexJson.validate(lastResult)
        if (errors.isNotEmpty()) {
            Toast.makeText(this, errors.first(), Toast.LENGTH_LONG).show()
            return null
        }
        val file = File(getExternalFilesDir(null), "result.hex.json")
        file.writeText(HexJson.encode(lastResult))
        return file
    }

    private fun queryDisplayName(uri: Uri): String? =
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }

    override fun onDestroy() {
        super.onDestroy()
        whisper.release()
    }
}