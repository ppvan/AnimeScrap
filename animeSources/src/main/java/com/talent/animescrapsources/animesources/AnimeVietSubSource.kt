package com.talent.animescrapsources.animesources

import android.util.Base64
import com.talent.animescrap_common.model.AnimeDetails
import com.talent.animescrap_common.model.AnimeStreamLink
import com.talent.animescrap_common.model.SimpleAnime
import com.talent.animescrap_common.source.AnimeSource
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import okhttp3.HttpUrl.Companion.toHttpUrl

class AniVietSubSource(private val domain: String = BASE_URL) : AnimeSource {

    companion object {
        private val KEY = byteArrayOf(
            100, 109, 95, 116, 104, 97, 110, 103,
            95, 115, 117, 99, 95, 118, 97, 116,
            95, 103, 101, 116, 95, 108, 105, 110,
            107, 95, 97, 110, 95, 100, 98, 116
        )

        private const val USER_AGENT =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 16_1_2 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) GSA/383.0.797833943 Mobile/15E148 Safari/604.1"
        private const val BASE_URL      = "https://animevietsub.how"
        private const val SEARCH_API    = "ajax/suggest"
        private const val PLAYLIST_API  = "ajax/player"
        private const val TRENDING_API  = "bang-xep-hang/season.html"
        private const val LATEST_API    = "anime-moi"

        private const val FAKE_PNG_HEADER_TO_SKIP = 128
        private const val RATE_LIMIT_DELAY_MS     = 500L
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    // -------------------------------------------------------------------------
    // AnimeSource interface implementation
    // -------------------------------------------------------------------------

    /**
     * Fetch full anime details.
     * [contentLink] is the anime page URL; the numeric ID is extracted from it.
     * Episodes are returned as a map of server name → (episode title → hash).
     */
    override suspend fun animeDetails(contentLink: String): AnimeDetails {
        val movieId = extractTrailingNumber(contentLink)
        val url = joinPath(domain, "phim", "-$movieId", "xem-phim.html")
        val html = client.newCall(Request.Builder().url(url).build()).execute()
            .use { it.body!!.string() }
        return parseAnimeDetails(movieId, html)
    }

    /** Search anime by keyword. */
    override suspend fun searchAnime(searchedText: String): ArrayList<SimpleAnime> {
        val url = joinPath(domain, SEARCH_API)
        val body = FormBody.Builder()
            .add("ajaxSearch", "1")
            .add("keysearch", searchedText)
            .build()
        val req = Request.Builder()
            .url(url)
            .post(body)
            .header("User-Agent", USER_AGENT)
            .build()

        val html = client.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "API error: ${resp.code}" }
            resp.body!!.string()
        }

        return ArrayList(extractMovies(html))
    }

    /** Fetch the latest/newest anime list. */
    override suspend fun latestAnime(): ArrayList<SimpleAnime> {
        val url = joinPath(domain, LATEST_API)
        val html = client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            check(resp.isSuccessful) { "API error: ${resp.code}" }
            resp.body!!.string()
        }
        return ArrayList(extractLatestMovies(html))
    }

    /** Fetch the trending/ranking list. */
    override suspend fun trendingAnime(): ArrayList<SimpleAnime> {
        val url = joinPath(domain, TRENDING_API)
        val html = client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            check(resp.isSuccessful) { "API error: ${resp.code}" }
            resp.body!!.string()
        }
        return ArrayList(extractTrendingMovies(html))
    }

    /**
     * Fetch and decrypt the M3U8 playlist for an episode, returned as an [AnimeStreamLink].
     * [animeUrl]    – the anime page URL (used to extract the numeric movie ID)
     * [animeEpCode] – the episode hash (data-hash attribute value)
     * [extras]      – unused
     */
    override suspend fun streamLink(
        animeUrl: String,
        animeEpCode: String,
        extras: List<String>?
    ): AnimeStreamLink {
        val movieId = extractTrailingNumber(animeUrl)
        val apiUrl = joinPath(domain, PLAYLIST_API)

        val body = FormBody.Builder()
            .add("link", animeEpCode)
            .add("id", movieId.toString())
            .build()
        val req = Request.Builder()
            .url(apiUrl)
            .post(body)
            .header("User-Agent", USER_AGENT)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .build()

        val responseText = client.newCall(req).execute().use { it.body!!.string() }

        val json      = JSONObject(responseText)
        val fileStr   = json.getJSONArray("link").getJSONObject(0).getString("file")
        val decrypted = decryptVideoSource(fileStr)

        // Unquote the JSON string (matches Go's strconv.Unquote)
        val playlist = JSONTokener(decrypted).nextValue() as String

        return AnimeStreamLink(
            link         = "memory://playlist-${movieId}-${animeEpCode}.m3u8",
            subsLink     = "",
            isHls        = true,
            rawPlaylist  = playlist,
            extraHeaders = hashMapOf(
                "User-Agent" to USER_AGENT,
                "Referer"    to domain
            )
        )
    }

    // -------------------------------------------------------------------------
    // Download helpers (not part of the interface, kept for direct use)
    // -------------------------------------------------------------------------

    /**
     * Download a full episode by streaming all HLS segments into [out].
     * [callback] receives progress in 0.0–1.0.
     */
    fun download(animeUrl: String, animeEpCode: String, out: OutputStream, callback: (Float) -> Unit) {
        val movieId  = extractTrailingNumber(animeUrl)
        // Re-use the blocking OkHttp path (call from a coroutine dispatcher if needed)
        val playlist = fetchPlaylistBlocking(movieId, animeEpCode)
        val segmentUrls = extractSegmentUrls(playlist)
        require(segmentUrls.isNotEmpty()) { "No segment URLs found in playlist" }

        segmentUrls.forEachIndexed { index, segUrl ->
            val data = downloadSegment(segUrl)
            out.write(data)
            callback((index + 1).toFloat() / segmentUrls.size)
        }
    }

    /**
     * Download a single .ts segment.
     * Skips the fake 128-byte PNG header prepended by the server and applies
     * a small rate-limit delay (matching the Go implementation).
     */
    fun downloadSegment(url: String): ByteArray {
        val req = Request.Builder()
            .url(url)
            .header("Referer", domain)
            .header("User-Agent", USER_AGENT)
            .build()

        val bytes = client.newCall(req).execute().use { resp ->
            val raw = resp.body!!.bytes()
            require(raw.size > FAKE_PNG_HEADER_TO_SKIP) { "Segment too small to strip header" }
            raw.copyOfRange(FAKE_PNG_HEADER_TO_SKIP, raw.size)
        }

        Thread.sleep(RATE_LIMIT_DELAY_MS)
        return bytes
    }

    // -------------------------------------------------------------------------
    // Parsing helpers
    // -------------------------------------------------------------------------

    private fun parseAnimeDetails(movieId: Int, html: String): AnimeDetails {
        val doc = Jsoup.parse(html)

        val eps = doc.select("#list-server li.episode > a.btn-episode").associate { a ->
            val title = a.attr("title")
            val hash  = a.attr("data-hash")
            title to hash
        }

        val article     = doc.selectFirst("article.TPost")
        val animeName   = article?.selectFirst("h1.Title")?.text()?.trim() ?: ""
        val animeDesc   = article?.selectFirst("div.Description")?.text()
            ?.replace(Regex("\\s+"), " ")?.trim() ?: ""
        val animeCover  = article?.selectFirst(".TPostBg img")?.absUrl("src") ?: ""
        val animeThumbnail = article?.selectFirst("header .Image img")?.attr("src")?: ""

        return AnimeDetails(
            animeName     = animeName,
            animeDesc     = animeDesc,
            animeCover    = animeCover,
            animeThumbnail = animeThumbnail,
            animeEpisodes = mapOf("VietSub" to eps)
        )
    }

    private fun extractMovies(html: String): List<SimpleAnime> {
        val doc = Jsoup.parse(html)
        val bgUrlRegex = Regex("""url\(['"]?(.*?)['"]?\)""")
        return doc.select("li:not(.ss-bottom)").mapNotNull { el ->
            val titleEl = el.selectFirst(".ss-title") ?: return@mapNotNull null
            val title   = titleEl.text().trim()
            val href    = titleEl.attr("href")
            if (title.isEmpty() || href.isEmpty()) return@mapNotNull null
            val style      = el.selectFirst(".thumb")?.attr("style") ?: ""
            val imageUrl   = bgUrlRegex.find(style)?.groupValues?.get(1) ?: ""
            SimpleAnime(animeName = title, animeImageURL = imageUrl, animeLink = href)
        }
    }

    private fun extractLatestMovies(html: String): List<SimpleAnime> {
        val doc = Jsoup.parse(html)
        return doc.select("ul.MovieList li.TPostMv").mapNotNull { li ->
            val article     = li.selectFirst("article") ?: return@mapNotNull null
            val linkEl      = article.selectFirst("a") ?: return@mapNotNull null
            val animeLink   = linkEl.absUrl("href").ifBlank { linkEl.attr("href") }
            val title       = article.selectFirst("h2.Title")?.text()?.trim() ?: return@mapNotNull null
            val imageUrl    = article.selectFirst("img")?.absUrl("src")
                ?.ifBlank { article.selectFirst("img")?.attr("src") } ?: return@mapNotNull null
            SimpleAnime(animeName = title, animeImageURL = imageUrl, animeLink = animeLink)
        }
    }

    private fun extractTrendingMovies(html: String): List<SimpleAnime> {
        val doc = Jsoup.parse(html)
        return doc.select("ul.bxh-movie-phimletv li").mapNotNull { el ->
            val a         = el.selectFirst("h3.title-item a") ?: return@mapNotNull null
            val title     = a.text().trim()
            val href      = a.attr("href")
            val thumbnail = el.selectFirst("a.thumb img")?.attr("src") ?: ""
            if (title.isEmpty() || href.isEmpty()) return@mapNotNull null
            SimpleAnime(animeName = title, animeImageURL = thumbnail, animeLink = href)
        }
    }

    // -------------------------------------------------------------------------
    // Crypto (identical algorithm to Go's decryptVideoSource)
    // -------------------------------------------------------------------------

    private fun decryptVideoSource(encryptedData: String): String {
        val keyBytes  = MessageDigest.getInstance("SHA-256").digest(KEY)
        val dataBytes = Base64.decode(encryptedData, Base64.DEFAULT)
        require(dataBytes.size > 16) { "Encrypted data must have at least 16 bytes" }

        val iv         = dataBytes.copyOfRange(0, 16)
        val ciphertext = dataBytes.copyOfRange(16, dataBytes.size)

        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
        val decrypted = cipher.doFinal(ciphertext)

        // Raw DEFLATE (nowrap=true  ≡  Go's flate.NewReader)
        InflaterInputStream(ByteArrayInputStream(decrypted), Inflater(true)).use { input ->
            val out    = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val n = input.read(buffer)
                if (n == -1) break
                out.write(buffer, 0, n)
            }
            return out.toString("UTF-8")
        }
    }

    // -------------------------------------------------------------------------
    // Misc utilities
    // -------------------------------------------------------------------------

    /** Blocking playlist fetch used by [download]. */
    private fun fetchPlaylistBlocking(movieId: Int, hash: String): String {
        val url  = joinPath(domain, PLAYLIST_API)
        val body = FormBody.Builder()
            .add("link", hash)
            .add("id", movieId.toString())
            .build()
        val req = Request.Builder()
            .url(url).post(body)
            .header("User-Agent", USER_AGENT)
            .build()

        val responseText = client.newCall(req).execute().use { it.body!!.string() }
        val fileStr      = JSONObject(responseText).getJSONArray("link")
            .getJSONObject(0).getString("file")
        val decrypted    = decryptVideoSource(fileStr)
        return JSONTokener(decrypted).nextValue() as String
    }

    /** Parse M3U8 text and return every HTTP(S) segment URL. */
    private fun extractSegmentUrls(playlist: String): List<String> =
        playlist.lines().map { it.trim() }.filter { it.startsWith("http") }

    private fun extractTrailingNumber(text: String): Int {
        var last = 0
        var cur = 0
        for (ch in text) {
            if (ch in '0'..'9') {
                cur = cur * 10 + (ch - '0')
            } else {
                if (cur > 0) last = cur
                cur = 0
            }
        }
        // Handle number at end of string
        if (cur > 0) last = cur
        return last
    }

    /** Join URL path segments safely (mirrors Go's url.JoinPath). */
    private fun joinPath(base: String, vararg segments: String): String {
        var builder = base.toHttpUrl().newBuilder()
        segments.forEach { seg ->
            seg.trim('/').split("/").forEach { part ->
                if (part.isNotEmpty()) builder = builder.addPathSegment(part)
            }
        }
        return builder.build().toString()
    }
}