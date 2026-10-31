package com.refayatul.fityah.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.refayatul.fityah.BuildConfig
import com.refayatul.fityah.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

object UpdateChecker {

    private const val RELEASES_URL =
        "https://api.github.com/repos/Refayatul/Fityah/releases/latest"
    private const val CHANNEL_ID = "fityah_app_updates"

    data class GitHubAsset(
        @SerializedName("browser_download_url") val downloadUrl: String? = null,
        val name: String? = null
    )

    data class GitHubRelease(
        @SerializedName("tag_name") val tagName: String? = null,
        @SerializedName("html_url") val htmlUrl: String? = null,
        val assets: List<GitHubAsset> = emptyList(),
        val body: String? = null
    )

    data class UpdateInfo(
        val version: String,
        val downloadUrl: String,
        val changelog: String?
    )

    private val client = OkHttpClient()
    private val gson = Gson()

    suspend fun check(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(RELEASES_URL)
                .header("Accept", "application/vnd.github+json")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val release = gson.fromJson(body, GitHubRelease::class.java) ?: return@withContext null
                val remote = release.tagName?.trim()?.removePrefix("v") ?: return@withContext null
                if (!isNewer(remote, BuildConfig.VERSION_NAME)) return@withContext null
                val url = release.assets.firstOrNull { it.name?.endsWith(".apk", ignoreCase = true) == true }?.downloadUrl
                    ?: release.htmlUrl
                    ?: return@withContext null
                UpdateInfo(remote, url, release.body)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun notify(context: Context, info: UpdateInfo) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(info.downloadUrl)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pi = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Fityah update available: v${info.version}")
            .setContentText("Tap to download the latest version.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(info.changelog ?: "A new version is available."))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(9001, notification)
    }

    private fun isNewer(remote: String, current: String): Boolean {
        val r = remote.split(".").mapNotNull { it.filter { c -> c.isDigit() }.toIntOrNull() }
        val c = current.split(".").mapNotNull { it.filter { ch -> ch.isDigit() }.toIntOrNull() }
        val n = maxOf(r.size, c.size)
        for (i in 0 until n) {
            val rv = r.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (rv != cv) return rv > cv
        }
        return false
    }
}
