package com.talent.animescrap.repo

import android.app.Application
import android.util.Log
import androidx.preference.PreferenceManager
import com.talent.animescrap.R
import com.talent.animescrap.room.FavRoomModel
import com.talent.animescrap.room.LinkDao
import com.talent.animescrap_common.model.AnimeDetails
import com.talent.animescrap_common.model.AnimeStreamLink
import com.talent.animescrap_common.model.SimpleAnime
import com.talent.animescrap_common.source.AnimeSource
import com.talent.animescrapsources.SourceSelector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton


interface AnimeRepository {
    // API operations
    suspend fun getAnimeDetailsFromSite(contentLink: String): AnimeDetails?
    suspend fun searchAnimeFromSite(searchUrl: String): ArrayList<SimpleAnime>
    suspend fun getLatestAnimeFromSite(): ArrayList<SimpleAnime>
    suspend fun getTrendingAnimeFromSite(): ArrayList<SimpleAnime>
    suspend fun getStreamLink(
        animeUrl: String,
        animeEpCode: String,
        extras: List<String>
    ): AnimeStreamLink

    // Room Operations
    suspend fun getFavoritesFromRoom(): Flow<List<SimpleAnime>>
    suspend fun checkFavoriteFromRoom(animeLink: String, sourceName: String): Boolean
    suspend fun removeFavFromRoom(animeLink: String, sourceName: String)
    suspend fun addFavToRoom(favRoomModel: FavRoomModel)
}

@Singleton
class AnimeRepositoryImpl @Inject constructor(
    private val linkDao: LinkDao,
    val application: Application
) : AnimeRepository {

    private val selectedSource = PreferenceManager
        .getDefaultSharedPreferences(application)
        .getString("source", "animevietsub")

    private var animeSource: AnimeSource? = null

    override suspend fun getAnimeDetailsFromSite(contentLink: String) =
        withContext(Dispatchers.IO) {
            try {
                return@withContext getSource().animeDetails(contentLink)
            } catch (e: Exception) {
                return@withContext null
            }

        }

    override suspend fun searchAnimeFromSite(searchUrl: String) = withContext(Dispatchers.IO) {
        try {
            return@withContext getSource().searchAnime(searchUrl)
        } catch (e: Exception) {
            return@withContext arrayListOf()
        }

    }

    private suspend fun getSource(): AnimeSource {

        if (animeSource == null) {
            val source = getLatestDomain()
            animeSource = SourceSelector().getSelectedSource(baseUrl = source)
        }

        return animeSource!!
    }

    private suspend fun getLatestDomain(): String =
        withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder()
                    .followRedirects(true)
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .build()

                val request = Request.Builder()
                    .url("https://bit.ly/animevietsubtv")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val finalUrl = response.request.url.toString().removeSuffix("/")
                        return@withContext finalUrl
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            return@withContext "https://animevietsub.mx"
        }

    override suspend fun getLatestAnimeFromSite(): ArrayList<SimpleAnime> =
        withContext(Dispatchers.IO) {
            try {
                return@withContext getSource().latestAnime()
            } catch (e: Exception) {
                return@withContext arrayListOf()
            }
        }

    override suspend fun getTrendingAnimeFromSite(): ArrayList<SimpleAnime> =
        withContext(Dispatchers.IO) {
            try {
                return@withContext getSource().trendingAnime()
            } catch (e: Exception) {
                return@withContext arrayListOf()
            }
        }

    override suspend fun getStreamLink(
        animeUrl: String,
        animeEpCode: String,
        extras: List<String>
    ): AnimeStreamLink =
        withContext(Dispatchers.IO) {
            try {
                return@withContext getSource().streamLink(animeUrl, animeEpCode, extras)
            } catch (e: Exception) {
                return@withContext AnimeStreamLink("", "", false)
            }

        }

    override suspend fun getFavoritesFromRoom() = withContext(Dispatchers.IO) {
        return@withContext linkDao.getLinks(selectedSource).map { animeList ->
            animeList.map {
                SimpleAnime(
                    it.nameString,
                    it.picLinkString,
                    it.linkString
                )
            }
        }
    }

    override suspend fun checkFavoriteFromRoom(animeLink: String, sourceName: String): Boolean =
        withContext(Dispatchers.IO) {
            return@withContext linkDao.isItFav(animeLink, sourceName)
        }

    override suspend fun removeFavFromRoom(animeLink: String, sourceName: String) =
        withContext(Dispatchers.IO) {
            val foundFav = linkDao.getFav(animeLink, sourceName)
            linkDao.deleteOne(foundFav)
        }

    override suspend fun addFavToRoom(
        favRoomModel: FavRoomModel
    ) = withContext(Dispatchers.IO) {
        linkDao.insert(favRoomModel)
    }
}