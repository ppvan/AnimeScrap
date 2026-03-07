package com.talent.animescrapsources

import android.content.Context
import com.talent.animescrap_common.source.AnimeSource
import com.talent.animescrapsources.animesources.AniVietSubSource
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class SourceSelector(context: Context) {
    val sourceMap: Map<String, AnimeSource> = mapOf(
        "animevietsub" to AniVietSubSource(),
    )

    fun getSelectedSource(selectedSource: String): AnimeSource {
        try {
            val client = OkHttpClient.Builder()
                .followRedirects(true)
                .connectTimeout(10, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder().url("https://bit.ly/animevietsubtv").build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val finalUrl = response.request.url.toString().removeSuffix("/")
                    return AniVietSubSource(finalUrl)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return sourceMap["animevietsub"]!!
    }
}
