package cat.canigo.voxtral

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.util.concurrent.TimeUnit

object UpdateManager {
    private const val CURRENT_VERSION = 10
    private const val VERSION_JSON_URL =
        "https://storage.googleapis.com/messaging-app-71a13.firebasestorage.app/version/voxtral.json"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    data class UpdateInfo(val version: Int, val url: String, val notes: String)

    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val req  = Request.Builder().url(VERSION_JSON_URL).build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return@withContext null
            val json = JSONObject(resp.body?.string() ?: return@withContext null)
            val remoteVersion = json.getInt("version")
            if (remoteVersion <= CURRENT_VERSION) return@withContext null
            UpdateInfo(
                version = remoteVersion,
                url     = json.getString("url"),
                notes   = json.optString("notes", "")
            )
        } catch (_: Exception) { null }
    }

    suspend fun downloadAndInstall(context: Context, url: String) {
        withContext(Dispatchers.IO) {
            try {
                val apkFile = File(context.filesDir, "update.apk")
                URL(url).openStream().use { input ->
                    apkFile.outputStream().use { input.copyTo(it) }
                }
                withContext(Dispatchers.Main) {
                    val uri = FileProvider.getUriForFile(
                        context, "${context.packageName}.provider", apkFile)
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
            } catch (_: Exception) { }
        }
    }
}
