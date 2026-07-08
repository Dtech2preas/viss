package com.together.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import okhttp3.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object AppUpdater {
    private const val TAG = "AppUpdater"
    private const val GITHUB_API_URL = "https://api.github.com/repos/Dtech2preas/viss/releases/latest"

    fun checkForUpdate(context: Context, showToastNoUpdate: Boolean = true) {
        val client = OkHttpClient()
        val request = Request.Builder()
            .url(GITHUB_API_URL)
            .header("Accept", "application/vnd.github.v3+json")
            .build()

        Toast.makeText(context, "Checking for updates...", Toast.LENGTH_SHORT).show()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Failed to check for updates", e)
                showToast(context, "Failed to check for updates.")
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    Log.e(TAG, "Failed to fetch release info: \${response.code}")
                    showToast(context, "Failed to fetch update info.")
                    return
                }

                val body = response.body?.string() ?: return
                try {
                    val jsonResponse = Gson().fromJson(body, JsonObject::class.java)
                    val tagName = jsonResponse.get("tag_name")?.asString

                    if (tagName == null) {
                        Log.e(TAG, "tag_name is null in response")
                        showToast(context, "Invalid update response.")
                        return
                    }

                    val currentVersion = BuildConfig.GIT_TAG

                    if (currentVersion == tagName) {
                        if (showToastNoUpdate) {
                            showToast(context, "App is up to date!")
                        }
                        return
                    }

                    if (currentVersion == "dev-build" && !showToastNoUpdate) {
                        Log.d(TAG, "Dev build, ignoring automatic update.")
                        return
                    }

                    val assets = jsonResponse.getAsJsonArray("assets")
                    var downloadUrl: String? = null

                    for (assetElement in assets) {
                        val asset = assetElement.asJsonObject
                        val name = asset.get("name").asString
                        if (name.endsWith(".apk")) {
                            downloadUrl = asset.get("browser_download_url").asString
                            break
                        }
                    }

                    if (downloadUrl != null) {
                        showToast(context, "Downloading update...")
                        downloadAndInstallApk(context, downloadUrl)
                    } else {
                        Log.e(TAG, "No APK found in release assets")
                        showToast(context, "No APK found in update.")
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing JSON", e)
                    showToast(context, "Error parsing update info.")
                }
            }
        })
    }

    private fun downloadAndInstallApk(context: Context, downloadUrl: String) {
        val client = OkHttpClient()
        val request = Request.Builder()
            .url(downloadUrl)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Failed to download APK", e)
                showToast(context, "Failed to download update.")
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    Log.e(TAG, "Failed to download APK: \${response.code}")
                    showToast(context, "Failed to download update.")
                    return
                }

                try {
                    val apkFile = File(context.cacheDir, "update.apk")
                    if (apkFile.exists()) {
                        apkFile.delete()
                    }

                    val inputStream = response.body?.byteStream()
                    val outputStream = FileOutputStream(apkFile)

                    inputStream?.use { input ->
                        outputStream.use { output ->
                            input.copyTo(output)
                        }
                    }

                    installApk(context, apkFile)

                } catch (e: Exception) {
                    Log.e(TAG, "Error saving APK file", e)
                    showToast(context, "Error saving update file.")
                }
            }
        })
    }

    private fun installApk(context: Context, apkFile: File) {
        try {
            val apkUri: Uri = FileProvider.getUriForFile(
                context,
                "\${context.packageName}.fileprovider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }

            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error installing APK", e)
            showToast(context, "Error installing update.")
        }
    }

    private fun showToast(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}
