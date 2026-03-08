package com.talent.animescrap.ui.viewmodels

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.talent.animescrap.BuildConfig
import com.talent.animescrap_common.model.UpdateDetails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class UpdateViewModel : ViewModel() {
    private val owner = "ppvan"
    private val repo = "AnimeScrap"
    private val apiUrl = "https://api.github.com/repos/$owner/$repo/releases/latest"

    private val _isUpdateAvailable = MutableLiveData<UpdateDetails>().apply {
        checkForNewUpdate()
    }
    val isUpdateAvailable: LiveData<UpdateDetails> = _isUpdateAvailable

    fun checkForNewUpdate() {
        val currentVersion = BuildConfig.VERSION_NAME
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val json = fetchJson(apiUrl)
                    val tagName = json.getString("tag_name")          // e.g. "v1.2.3"
                    val body = json.optString("body", "No update description found.")
                    val latestVersion = tagName.trimStart('v')           // strip leading 'v'

                    // Prefer the APK asset's browser_download_url if present, fall back to constructed URL
                    val apkUrl = runCatching {
                        val assets = json.getJSONArray("assets")
                        (0 until assets.length())
                            .map { assets.getJSONObject(it) }
                            .first { it.getString("name").endsWith(".apk") }
                            .getString("browser_download_url")
                    }.getOrElse {
                        "https://github.com/$owner/$repo/releases/download/$tagName/AnimeScrap-v$latestVersion.apk"
                    }

                    println("current=$currentVersion latest=$latestVersion")
                    _isUpdateAvailable.postValue(
                        UpdateDetails(
                            isUpdateAvailable = latestVersion != currentVersion,
                            link = apkUrl,
                            description = body
                        )
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun fetchJson(urlString: String): JSONObject {
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        return try {
            JSONObject(connection.inputStream.bufferedReader().readText())
        } finally {
            connection.disconnect()
        }
    }
}