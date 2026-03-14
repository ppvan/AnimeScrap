package com.talent.animescrapsources

import com.talent.animescrap_common.source.AnimeSource
import com.talent.animescrapsources.animesources.AniVietSubSource

class SourceSelector() {
    companion object {
        val AvailableSources = listOf("animevietsub")
    }

    fun getSelectedSource(baseUrl: String): AnimeSource {
        return AniVietSubSource(baseUrl)
    }

}
