package cat.canigo.voxtral

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private var recorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var isRecording = false
    private var recordingStartMs = 0L
    private var tts: TextToSpeech? = null
    private var eggTapCount = 0
    private var eggLastTapMs = 0L

    private lateinit var prefs: SharedPreferences
    private lateinit var btnRecord: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvRecordLabel: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var transcriptionsContainer: LinearLayout
    private lateinit var tvStats: TextView
    private lateinit var tvTitle: TextView

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    companion object {
        const val PERMISSION_REQUEST = 100
        val API_KEY get() = BuildConfig.MISTRAL_API_KEY
        // Voxtral Mini pricing: $0.004 / minute = $0.00006667 / second
        const val PRICE_PER_SEC = 0.004 / 60.0
        val COLOR_PURPLE = 0xFF9C27B0.toInt()
        val COLOR_RED    = 0xFFD32F2F.toInt()
        val COLOR_GREY   = 0xFF9E9E9E.toInt()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("voxtral_stats", Context.MODE_PRIVATE)

        btnRecord = findViewById(R.id.btnRecord)
        tvStatus  = findViewById(R.id.tvStatus)
        tvRecordLabel = findViewById(R.id.tvRecordLabel)
        progressBar = findViewById(R.id.progressBar)
        transcriptionsContainer = findViewById(R.id.transcriptionsContainer)
        tvStats = findViewById(R.id.tvStats)
        tvTitle = findViewById(R.id.tvTitle)
        tvTitle.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - eggLastTapMs > 1000) eggTapCount = 0
            eggLastTapMs = now
            eggTapCount++
            if (eggTapCount >= 5) {
                eggTapCount = 0
                val toast = Toast.makeText(this, "\uD83E\uDD5A Joyeuses P\u00E2ques !", Toast.LENGTH_LONG)
                toast.setGravity(android.view.Gravity.CENTER, 0, 0)
                toast.show()
            }
        }

        try {
            tts = TextToSpeech(this) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val res = tts?.setLanguage(Locale.FRENCH) ?: TextToSpeech.LANG_NOT_SUPPORTED
                    if (res == TextToSpeech.LANG_MISSING_DATA ||
                        res == TextToSpeech.LANG_NOT_SUPPORTED) {
                        tts?.setLanguage(Locale.getDefault())
                    }
                }
            }
        } catch (e: Exception) { tts = null }

        btnRecord.setOnClickListener {
            if (!hasPermission()) { requestPermission(); return@setOnClickListener }
            if (isRecording) stopRecording() else startRecording()
        }

        updateStatsDisplay()

        // OTA check
        CoroutineScope(Dispatchers.IO).launch {
            val update = UpdateManager.checkForUpdate()
            update?.let { withContext(Dispatchers.Main) { UpdateManager.showUpdateDialog(this@MainActivity, it) } }
        }
    }

    // ── Stats ──────────────────────────────────────────────────────────────

    private fun addStats(durationSec: Float) {
        val prevSec   = prefs.getFloat("total_sec", 0f)
        val prevCount = prefs.getInt("count", 0)
        prefs.edit()
            .putFloat("total_sec", prevSec + durationSec)
            .putInt("count", prevCount + 1)
            .apply()
    }

    private fun updateStatsDisplay() {
        val totalSec = prefs.getFloat("total_sec", 0f)
        val count    = prefs.getInt("count", 0)
        val costUsd  = totalSec * PRICE_PER_SEC
        val costEur  = costUsd * 0.92  // approx EUR

        val min  = (totalSec / 60).toInt()
        val sec  = (totalSec % 60).toInt()
        val dur  = if (min > 0) "${min}m ${sec}s" else "${sec}s"

        tvStats.text = "📊 $count transcription(s)  •  $dur  •  \$${"%.4f".format(costUsd)}  (~€${"%.4f".format(costEur)})"
    }

    // ── TTS ───────────────────────────────────────────────────────────────

    private fun speak(text: String) {
        try { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null,
            "utt_${System.currentTimeMillis()}") } catch (e: Exception) {}
    }

    override fun onDestroy() {
        try { tts?.stop(); tts?.shutdown() } catch (e: Exception) {}
        super.onDestroy()
    }

    // ── Recording ─────────────────────────────────────────────────────────

    private fun hasPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    private fun requestPermission() {
        ActivityCompat.requestPermissions(this,
            arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSION_REQUEST)
    }

    private fun startRecording() {
        audioFile = File(cacheDir, "rec_${System.currentTimeMillis()}.m4a")
        recorder = MediaRecorder(this).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(16000)
            setAudioEncodingBitRate(96000)
            setOutputFile(audioFile!!.absolutePath)
            prepare()
            start()
        }
        recordingStartMs = SystemClock.elapsedRealtime()
        isRecording = true
        btnRecord.text = "⏹"
        btnRecord.backgroundTintList =
            android.content.res.ColorStateList.valueOf(COLOR_RED)
        tvStatus.text = "⏺ Enregistrement..."
        tvRecordLabel.text = "Appuyer pour arrêter"
    }

    private fun stopRecording() {
        val durationSec = (SystemClock.elapsedRealtime() - recordingStartMs) / 1000f
        recorder?.apply { stop(); release() }
        recorder = null
        isRecording = false

        btnRecord.isEnabled = false
        btnRecord.text = "⏳"
        btnRecord.backgroundTintList =
            android.content.res.ColorStateList.valueOf(COLOR_GREY)
        tvStatus.text = "🦄 Transcription Voxtral..."
        tvRecordLabel.text = ""
        progressBar.visibility = View.VISIBLE

        audioFile?.let { file ->
            CoroutineScope(Dispatchers.IO).launch {
                val result = transcribe(file)
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    btnRecord.isEnabled = true
                    btnRecord.text = "🎙️"
                    btnRecord.backgroundTintList =
                        android.content.res.ColorStateList.valueOf(COLOR_PURPLE)
                    tvRecordLabel.text = "Appuyer pour enregistrer"

                    if (result != null) {
                        addStats(durationSec)
                        updateStatsDisplay()

        // OTA check
        CoroutineScope(Dispatchers.IO).launch {
            val update = UpdateManager.checkForUpdate()
            update?.let { withContext(Dispatchers.Main) { UpdateManager.showUpdateDialog(this@MainActivity, it) } }
        }
                        tvStatus.text = "✨ Prêt"
                        addTranscription(result, durationSec)
                    } else {
                        tvStatus.text = "⚠️ Erreur de transcription"
                    }
                }
                file.delete()
            }
        }
    }

    // ── API ───────────────────────────────────────────────────────────────

    private fun transcribe(file: File): String? {
        return try {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", "voxtral-mini-latest")
                .addFormDataPart("language", "fr")
                .addFormDataPart("file", file.name,
                    file.asRequestBody("audio/mp4".toMediaTypeOrNull()))
                .build()

            val request = Request.Builder()
                .url("https://api.mistral.ai/v1/audio/transcriptions")
                .addHeader("Authorization", "Bearer $API_KEY")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: return null
            if (response.isSuccessful) JSONObject(responseBody).getString("text").trim()
            else null
        } catch (e: Exception) { null }
    }

    // ── UI ────────────────────────────────────────────────────────────────

    private fun addTranscription(text: String, durationSec: Float) {
        val dp = resources.displayMetrics.density
        val costUsd = durationSec * PRICE_PER_SEC

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFCE4EC.toInt())
            val p = (12 * dp).toInt()
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (8 * dp).toInt() }
        }

        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val secStr = "%.1f".format(durationSec)
        val costStr = "${"%.4f".format(costUsd)}"

        card.addView(TextView(this).apply {
            this.text = "$ts  •  ${secStr}s  •  \$$costStr"
            textSize = 11f
            setTextColor(0xFFAB47BC.toInt())
        })
        card.addView(TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(0xFF212121.toInt())
            setPadding(0, (4 * dp).toInt(), 0, (8 * dp).toInt())
            isLongClickable = true
            setOnLongClickListener {
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("transcription", text))
                tvStatus.text = "📋 Copié !"
                true
            }
        })
        card.addView(Button(this).apply {
            this.text = "🔊 Écouter"
            textSize = 12f
            backgroundTintList =
                android.content.res.ColorStateList.valueOf(0xFFCE93D8.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, (40 * dp).toInt())
            setOnClickListener { speak(text) }
        })

        transcriptionsContainer.addView(card, 0)
        (transcriptionsContainer.parent as ScrollView).post {
            (transcriptionsContainer.parent as ScrollView).scrollTo(0, 0)
        }
        speak(text)
    }
}
