package cat.canigo.voxtral

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

object UpdateManager {
    private const val CURRENT_VERSION = 1

    // Firebase project REST API — no SDK needed, OkHttp already present
    private const val FIREBASE_PROJECT = "messaging-app-71a13"
    private const val FIREBASE_API_KEY = "AIzaSyAGPEmZd_JkunwoZqyqPOetr_NXkyq85Gc"
    private const val FIRESTORE_URL =
        "https://firestore.googleapis.com/v1/projects/$FIREBASE_PROJECT/databases/(default)/documents/config/voxtral_update?key=$FIREBASE_API_KEY"

    data class UpdateInfo(val version: Int, val url: String, val notes: String)

    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
            val req = Request.Builder().url(FIRESTORE_URL).build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return@withContext null
            val root = JSONObject(resp.body?.string() ?: return@withContext null)
            val fields = root.getJSONObject("fields")
            val remoteVersion = fields.getJSONObject("version").getString("integerValue").toInt()
            if (remoteVersion <= CURRENT_VERSION) return@withContext null
            UpdateInfo(
                version = remoteVersion,
                url = fields.getJSONObject("url").getString("stringValue"),
                notes = fields.optJSONObject("notes")?.optString("stringValue") ?: ""
            )
        } catch (e: Exception) { null }
    }

    fun showUpdateDialog(activity: Activity, info: UpdateInfo) {
        AlertDialog.Builder(activity)
            .setTitle("Mise à jour disponible (v${info.version})")
            .setMessage(info.notes.ifEmpty { "Une nouvelle version est disponible." })
            .setPositiveButton("Installer") { _, _ -> downloadAndInstall(activity, info.url) }
            .setNegativeButton("Plus tard", null)
            .show()
    }

    private fun downloadAndInstall(context: Context, url: String) {
        val fileName = "voxtral-update.apk"
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Mise à jour Voxtral")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
        val downloadId = dm.enqueue(request)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) == downloadId) {
                    ctx.unregisterReceiver(this)
                    val apkFile = File(
                        ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
                    val apkUri = FileProvider.getUriForFile(
                        ctx, "${ctx.packageName}.provider", apkFile)
                    val install = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(apkUri, "application/vnd.android.package-archive")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                    }
                    ctx.startActivity(install)
                }
            }
        }
        context.registerReceiver(
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            Context.RECEIVER_EXPORTED
        )
    }
}
