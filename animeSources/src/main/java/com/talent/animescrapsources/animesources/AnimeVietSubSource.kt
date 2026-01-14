package com.talent.animescrapsources.animesources

import com.google.gson.JsonParser
import com.talent.animescrap_common.model.AnimeDetails
import com.talent.animescrap_common.model.AnimeStreamLink
import com.talent.animescrap_common.model.SimpleAnime
import com.talent.animescrap_common.source.AnimeSource
import com.talent.animescrap_common.utils.Utils.getJsoup
import com.talent.animescrap_common.utils.Utils.post

import android.util.Base64
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import kotlin.collections.mapOf


class AnimeVietSubSource : AnimeSource {

    private val KEY = byteArrayOf(
        100, 109, 95, 116, 104, 97, 110, 103,
        95, 115, 117, 99, 95, 118, 97, 116,
        95, 103, 101, 116, 95, 108, 105, 110,
        107, 95, 97, 110, 95, 100, 98, 116
    )




    override suspend fun animeDetails(contentLink: String): AnimeDetails {
        val url = "${contentLink}xem-phim.html"
        val doc = getJsoup(url)

        val article = doc.selectFirst("article.TPost.Single")
            ?: throw IllegalArgumentException("Anime article not found")

        // Anime title
        val animeName = article
            .selectFirst("header h1.Title")
            ?.text()
            ?.trim()
            ?: throw IllegalArgumentException("Anime title not found")

        // Anime description (keep full text, normalize whitespace)
        val animeDesc = article
            .selectFirst("header .Description")
            ?.text()
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?: ""

        // Anime cover image
        val animeCover = article
            .selectFirst(".TPostBg img.TPostBg")
            ?.absUrl("src")
            ?.ifBlank { article.selectFirst("header .Image img")?.attr("src") }
            ?: ""

        val eps = doc
            .select("#list-server a.episode-link")
            .associate { a ->
                val title = a.text().trim()
                val hash = a.attr("data-hash").ifBlank { "" }
                title to hash
            }

        return AnimeDetails(
            animeName = animeName,
            animeDesc = animeDesc,
            animeCover = animeCover,
            animeEpisodes = mapOf("VietSub" to eps)
        )

    }

    override suspend fun searchAnime(searchedText: String): ArrayList<SimpleAnime> {
        return ArrayList()
    }

    override suspend fun latestAnime(): ArrayList<SimpleAnime> {
        val url = "https://animevietsub.now/anime-moi/"

        val doc = getJsoup(url)

        return doc.select("ul.MovieList li.TPostMv").mapNotNull { li ->
            val article = li.selectFirst("article") ?: return@mapNotNull null

            val linkElement = article.selectFirst("a") ?: return@mapNotNull null
            val animeLink = linkElement.absUrl("href").ifBlank {
                linkElement.attr("href")
            }

            val title = article.selectFirst("h2.Title")?.text()?.trim()
                ?: return@mapNotNull null

            val imageUrl = article
                .selectFirst("img")
                ?.absUrl("src")
                ?.ifBlank { article.selectFirst("img")?.attr("src") }
                ?: return@mapNotNull null

            SimpleAnime(
                animeName = title,
                animeImageURL = imageUrl,
                animeLink = animeLink
            )
        }.toCollection(ArrayList())

    }

    override suspend fun trendingAnime(): ArrayList<SimpleAnime> {
        return ArrayList()
    }

    override suspend fun streamLink(
        animeUrl: String,
        animeEpCode: String,
        extras: List<String>?
    ): AnimeStreamLink {
        val animeId = extractLargestNumber(animeUrl)
        val response = post("https://animevietsub.now/ajax/player", mapOf(
            "User-Agent" to "Mozilla/5.0 (iPhone; CPU iPhone OS 16_1_2 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) GSA/383.0.797833943 Mobile/15E148 Safari/604.1",
            "Referer" to "https://animevietsub.now/",
        ), mapOf(
            "link" to animeEpCode,
            "id" to animeId.toString()
        )
        )

        val playlist = JsonParser.parseString(response)
            .asJsonObject["link"]
            .asJsonArray[0]
            .asJsonObject["file"]
            .asString
        val decryptVideoSource = decryptVideoSource(playlist)
        val unquoted = JSONTokener(decryptVideoSource).nextValue() as String

        return AnimeStreamLink(
            link = "memory://playlist.m3u8",
            subsLink = "",
            isHls = true,
            rawPlaylist = unquoted,
            extraHeaders = hashMapOf(
                "User-Agent" to "Mozilla/5.0 (iPhone; CPU iPhone OS 16_1_2 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) GSA/383.0.797833943 Mobile/15E148 Safari/604.1",
                "Referer" to "https://animevietsub.now/",
            )
        )
    }

    private fun extractLargestNumber(text: String): Int {
        var max = 0
        var cur = 0

        for (ch in text) {
            if (ch in '0'..'9') {
                cur = cur * 10 + (ch - '0')
                if (cur >= max) {
                    max = cur
                }
            } else {
                cur = 0
            }
        }

        return max
    }

    fun decryptVideoSource(encryptedData: String): String {
        // === SHA-256(KEY) ===
        val sha256 = MessageDigest.getInstance("SHA-256")
        val keyBytes = sha256.digest(KEY)

        // === Base64 decode ===
        val dataBytes = Base64.decode(encryptedData, Base64.DEFAULT)
        require(dataBytes.size > 16) { "encrypted data must have at least 16 bytes" }

        // === Split IV and ciphertext ===
        val iv = dataBytes.copyOfRange(0, 16)
        val ciphertext = dataBytes.copyOfRange(16, dataBytes.size)

        // === AES-CBC decrypt (NO padding) ===
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        val secretKey = SecretKeySpec(keyBytes, "AES")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))
        val decrypted = cipher.doFinal(ciphertext)

        // === Raw DEFLATE decompression (Go flate.NewReader equivalent) ===
        val inflater = Inflater(/* nowrap = */ true)
        InflaterInputStream(ByteArrayInputStream(decrypted), inflater).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                output.write(buffer, 0, read)
            }
            return output.toString()
        }
    }

}