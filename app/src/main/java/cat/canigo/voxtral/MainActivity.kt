package cat.canigo.voxtral

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
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

    // ── Géolocalisation ───────────────────────────────────────────────────────
    private var currentLocation: Location? = null
    private lateinit var locationManager: LocationManager
    private val locationListener = LocationListener { location -> currentLocation = location }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    companion object {
        const val PERMISSION_REQUEST = 100
        val API_KEY get() = BuildConfig.MISTRAL_API_KEY
        const val PRICE_PER_SEC = 0.004 / 60.0
        val COLOR_PURPLE = 0xFF9C27B0.toInt()
        val COLOR_RED    = 0xFFD32F2F.toInt()
        val COLOR_GREY   = 0xFF9E9E9E.toInt()
        private const val LOCATION_INTERVAL_MS = 2 * 60 * 1000L  // 2 min
        private const val LOCATION_MIN_DIST_M  = 10f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("voxtral_stats", Context.MODE_PRIVATE)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        btnRecord            = findViewById(R.id.btnRecord)
        tvStatus             = findViewById(R.id.tvStatus)
        tvRecordLabel        = findViewById(R.id.tvRecordLabel)
        progressBar          = findViewById(R.id.progressBar)
        transcriptionsContainer = findViewById(R.id.transcriptionsContainer)
        tvStats              = findViewById(R.id.tvStats)
        tvTitle              = findViewById(R.id.tvTitle)

        tvTitle.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - eggLastTapMs > 1000) eggTapCount = 0
            eggLastTapMs = now
            if (++eggTapCount >= 5) {
                eggTapCount = 0
                val t = Toast.makeText(this, "\uD83E\uDD5A Joyeuses P\u00E2ques !", Toast.LENGTH_LONG)
                t.setGravity(android.view.Gravity.CENTER, 0, 0)
                t.show()
            }
        }

        try {
            tts = TextToSpeech(this) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val res = tts?.setLanguage(Locale.FRENCH) ?: TextToSpeech.LANG_NOT_SUPPORTED
                    if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED)
                        tts?.setLanguage(Locale.getDefault())
                }
            }
        } catch (e: Exception) { tts = null }

        btnRecord.setOnClickListener {
            if (!hasAudioPermission()) { requestPermissions(); return@setOnClickListener }
            if (isRecording) stopRecording() else startRecording()
        }

        requestPermissions()
        updateStatsDisplay()

        CoroutineScope(Dispatchers.IO).launch {
            val update = UpdateManager.checkForUpdate()
            if (update != null) UpdateManager.downloadAndInstall(this@MainActivity, update.url)
        }
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private fun hasAudioPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        val needed = mutableListOf<String>()
        if (!hasAudioPermission())    needed.add(Manifest.permission.RECORD_AUDIO)
        if (!hasLocationPermission()) needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (needed.isNotEmpty())
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERMISSION_REQUEST)
        else
            startLocationUpdates()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST && hasLocationPermission())
            startLocationUpdates()
    }

    // ── Géolocalisation ───────────────────────────────────────────────────────

    private fun startLocationUpdates() {
        if (!hasLocationPermission()) return
        try {
            // Use NETWORK_PROVIDER (fonctionne sans GPS, compatible GrapheneOS)
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    LOCATION_INTERVAL_MS,
                    LOCATION_MIN_DIST_M,
                    locationListener
                )
                // Initialise avec la dernière position connue
                currentLocation = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            }
            // GPS en complément si disponible
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    LOCATION_INTERVAL_MS,
                    LOCATION_MIN_DIST_M,
                    locationListener
                )
                if (currentLocation == null)
                    currentLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            }
        } catch (e: SecurityException) { /* permission refusée */ }
    }

    override fun onDestroy() {
        try { locationManager.removeUpdates(locationListener) } catch (_: Exception) {}
        try { tts?.stop(); tts?.shutdown() } catch (e: Exception) {}
        super.onDestroy()
    }

    private suspend fun resolveAddress(location: Location): String = withContext(Dispatchers.IO) {
        try {
            val geocoder = Geocoder(this@MainActivity, Locale.getDefault())
            @Suppress("DEPRECATION")
            val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
            if (!addresses.isNullOrEmpty()) {
                val addr = addresses[0]
                buildString {
                    addr.locality?.let { append(it) }
                    addr.subLocality?.let {
                        if (isNotEmpty()) append(", ")
                        append(it)
                    }
                    if (isEmpty()) addr.adminArea?.let { append(it) }
                }.ifEmpty { "%.5f, %.5f".format(location.latitude, location.longitude) }
            } else "%.5f, %.5f".format(location.latitude, location.longitude)
        } catch (_: Exception) {
            "%.5f, %.5f".format(location.latitude, location.longitude)
        }
    }

    // ── Stats ─────────────────────────────────────────────────────────────────

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
        val costEur  = costUsd * 0.92
        val min  = (totalSec / 60).toInt()
        val sec  = (totalSec % 60).toInt()
        val dur  = if (min > 0) "${min}m ${sec}s" else "${sec}s"
        tvStats.text = "📊 $count transcription(s)  •  $dur  •  \$${"%.4f".format(costUsd)}  (~€${"%.4f".format(costEur)})"
    }

    // ── TTS ───────────────────────────────────────────────────────────────────

    private fun speak(text: String) {
        try { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utt_${System.currentTimeMillis()}") }
        catch (e: Exception) {}
    }

    // ── Recording ─────────────────────────────────────────────────────────────

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
        btnRecord.backgroundTintList = android.content.res.ColorStateList.valueOf(COLOR_RED)
        tvStatus.text = "⏺ Enregistrement..."
        tvRecordLabel.text = "Appuyer pour arrêter"
    }

    private fun stopRecording() {
        val durationSec = (SystemClock.elapsedRealtime() - recordingStartMs) / 1000f
        val locationAtStop = currentLocation  // capture position au moment de l'arrêt
        recorder?.apply { stop(); release() }
        recorder = null
        isRecording = false

        btnRecord.isEnabled = false
        btnRecord.text = "⏳"
        btnRecord.backgroundTintList = android.content.res.ColorStateList.valueOf(COLOR_GREY)
        tvStatus.text = "🦄 Transcription Voxtral..."
        tvRecordLabel.text = ""
        progressBar.visibility = View.VISIBLE

        audioFile?.let { file ->
            CoroutineScope(Dispatchers.IO).launch {
                val result = transcribe(file)
                // Resolve address in parallel
                val address = locationAtStop?.let { resolveAddress(it) }
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    btnRecord.isEnabled = true
                    btnRecord.text = "🎙️"
                    btnRecord.backgroundTintList = android.content.res.ColorStateList.valueOf(COLOR_PURPLE)
                    tvRecordLabel.text = "Appuyer pour enregistrer"

                    if (result != null) {
                        addStats(durationSec)
                        updateStatsDisplay()
                        CoroutineScope(Dispatchers.IO).launch {
                            val update = UpdateManager.checkForUpdate()
                            if (update != null) UpdateManager.downloadAndInstall(this@MainActivity, update.url)
                        }
                        tvStatus.text = "✨ Prêt"
                        addTranscription(result, durationSec, locationAtStop, address)
                    } else {
                        tvStatus.text = "⚠️ Erreur de transcription"
                    }
                }
                file.delete()
            }
        }
    }

    // ── API ───────────────────────────────────────────────────────────────────

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

    // ── UI ────────────────────────────────────────────────────────────────────

    private fun addTranscription(
        text: String,
        durationSec: Float,
        location: Location? = null,
        address: String? = null
    ) {
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
        card.addView(TextView(this).apply {
            this.text = "$ts  •  ${"%.1f".format(durationSec)}s  •  \$${"%.4f".format(costUsd)}"
            textSize = 11f
            setTextColor(0xFFAB47BC.toInt())
        })

        // Ligne géolocalisation
        if (location != null) {
            val locLabel = address ?: "%.5f, %.5f".format(location.latitude, location.longitude)
            card.addView(TextView(this).apply {
                this.text = "📍 $locLabel"
                textSize = 11f
                setTextColor(0xFF7B7B7B.toInt())
                setPadding(0, (2 * dp).toInt(), 0, 0)
            })
        }

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
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFCE93D8.toInt())
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
