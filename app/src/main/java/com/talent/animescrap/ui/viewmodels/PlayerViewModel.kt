package com.talent.animescrap.ui.viewmodels

import android.app.Application
import android.media.session.PlaybackState
import androidx.media3.session.MediaSession
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.talent.animescrap_common.model.AnimeStreamLink
import com.talent.animescrap.repo.AnimeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.CipherSuite
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltViewModel
@UnstableApi
class PlayerViewModel @Inject constructor(
    val app: Application,
    private val animeRepository: AnimeRepository,
    private val savedStateHandle: SavedStateHandle,
    val player: ExoPlayer
) : ViewModel() {

    private val settingsPreferenceManager = PreferenceManager.getDefaultSharedPreferences(app)

    private val isVideoCacheEnabled = settingsPreferenceManager.getBoolean("video_cache", true)
    private val isAutoPlayEnabled = settingsPreferenceManager.getBoolean("auto_play", true)

    val isLoading = MutableLiveData(true)
    val keepScreenOn = MutableLiveData(false)
    val showSubsBtn = MutableLiveData(false)
    val playNextEp = MutableLiveData(false)
    val isError = MutableLiveData(false)

    private val _animeStreamLink: MutableLiveData<AnimeStreamLink> = MutableLiveData()
    private val animeStreamLink: LiveData<AnimeStreamLink> = _animeStreamLink

    var qualityTrackGroup: Tracks.Group? = null
    private var qualityMapUnsorted: MutableMap<String, Int> = mutableMapOf()
    var qualityMapSorted: MutableMap<String, Int> = mutableMapOf()

    private var mediaSession: MediaSession

    private var simpleCache: SimpleCache? = null
    private val databaseProvider = StandaloneDatabaseProvider(app)

    private val savedDone = savedStateHandle.getStateFlow("done", false)

    init {
        player.prepare()
        player.playWhenReady = true

        mediaSession = MediaSession.Builder(app, player)
            .setId("AnimeScrap Media Session")
            .build()

        player.addListener(getCustomPlayerListener())

        simpleCache?.release()
        simpleCache = SimpleCache(
            File(app.cacheDir, "exoplayer").also { it.deleteOnExit() },
            LeastRecentlyUsedCacheEvictor(300L * 1024L * 1024L),
            databaseProvider
        )
    }

    fun setAnimeLink(
        animeUrl: String,
        animeEpCode: String,
        extras: List<String>,
        getNextEp: Boolean = false
    ) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                println("STREAM GET LINK")
                animeRepository.getStreamLink(animeUrl, animeEpCode, extras).apply {
                    _animeStreamLink.postValue(this@apply)
                    withContext(Dispatchers.Main) {
                        if (!savedDone.value || getNextEp) {
                            println("prepare Media Source")
                            prepareMediaSource()
                            savedStateHandle["done"] = true
                        }
                    }
                }
            }
        }
    }

    private fun getCustomPlayerListener(): Player.Listener {
        return object : Player.Listener {

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == PlaybackState.STATE_NONE ||
                    playbackState == PlaybackState.STATE_CONNECTING ||
                    playbackState == PlaybackState.STATE_STOPPED
                )
                    isLoading.postValue(true)
                else
                    isLoading.postValue(false)

                super.onPlaybackStateChanged(playbackState)
            }

            override fun onPlayerError(error: PlaybackException) {
                super.onPlayerError(error)
                isError.postValue(true)
                Toast.makeText(app, error.localizedMessage, Toast.LENGTH_SHORT).show()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                super.onIsPlayingChanged(isPlaying)
                keepScreenOn.postValue(isPlaying)

                val progress = player.duration - player.currentPosition
                if (progress <= 0 && isAutoPlayEnabled && !isPlaying)
                    playNextEp.postValue(true)
            }

            override fun onTracksChanged(tracks: Tracks) {
                for (trackGroup in tracks.groups) {
                    if (trackGroup.type == C.TRACK_TYPE_VIDEO) {
                        for (i in 0 until trackGroup.length) {
                            val trackFormat = trackGroup.getTrackFormat(i).height
                            if (trackGroup.isTrackSupported(i) && trackGroup.isTrackSelected(i)) {
                                qualityMapUnsorted["${trackFormat}p"] = i
                            }
                        }

                        qualityMapUnsorted.entries
                            .sortedBy { it.key.replace("p", "").toInt() }
                            .reversed()
                            .forEach { qualityMapSorted[it.key] = it.value }

                        qualityTrackGroup = trackGroup
                    }
                }
            }
        }
    }

    private fun releaseCache() {
        simpleCache?.release()
        simpleCache = null
    }

    private fun setMediaSource(mediaSource: MediaSource) {
        println("Set media Source")

        player.stop()
        player.prepare()

        qualityMapSorted = mutableMapOf()
        qualityMapUnsorted = mutableMapOf()
        qualityTrackGroup = null

        player.setMediaSource(mediaSource)
    }

    override fun onCleared() {
        super.onCleared()
        releasePlayer()
        releaseCache()
    }

    private fun releasePlayer() {
        player.release()
        mediaSession.release()
    }


    private fun createCombinedDataSourceFactory(
        m3u8Content: String,
        httpDataSourceFactory: DataSource.Factory
    ): DataSource.Factory {

        val inMemoryDataSourceFactory = DataSource.Factory {
            object : DataSource {
                private var inputStream: ByteArrayInputStream? = null
                override fun addTransferListener(transferListener: TransferListener) {}
                override fun open(dataSpec: DataSpec): Long {
                    val data = m3u8Content.toByteArray(Charsets.UTF_8)
                    inputStream = ByteArrayInputStream(data)
                    return data.size.toLong()
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    inputStream?.read(buffer, offset, length) ?: -1
                override fun getUri(): Uri? = Uri.parse("memory://playlist.m3u8")
                override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()
                override fun close() { inputStream?.close() }
            }
        }

        return DataSource.Factory {
            object : DataSource {
                private var currentDataSource: DataSource? = null
                private var isSegment = false
                private var headerSkipped = false
                private val FAKE_HEADER_SIZE = 128

                override fun addTransferListener(transferListener: TransferListener) {
                    currentDataSource?.addTransferListener(transferListener)
                }

                override fun open(dataSpec: DataSpec): Long {
                    val uri = dataSpec.uri.toString()

                    isSegment = !uri.contains(".m3u8")
                    headerSkipped = false

                    currentDataSource = if (!isSegment) {
                        inMemoryDataSourceFactory.createDataSource()
                    } else {
                        httpDataSourceFactory.createDataSource()
                    }

                    val bytesRead = currentDataSource?.open(dataSpec) ?: 0L

                    // Skip the 128-byte fake PNG header for segments
                    if (isSegment && bytesRead != C.LENGTH_UNSET.toLong()) {
                        val skipBuffer = ByteArray(FAKE_HEADER_SIZE)
                        var skipped = 0
                        while (skipped < FAKE_HEADER_SIZE) {
                            val n = currentDataSource?.read(skipBuffer, skipped, FAKE_HEADER_SIZE - skipped) ?: -1
                            if (n == -1) break
                            skipped += n
                        }
                        headerSkipped = true
                        return bytesRead - FAKE_HEADER_SIZE
                    }

                    return bytesRead
                }

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    // For streaming (LENGTH_UNSET), skip header lazily on first read
                    if (isSegment && !headerSkipped) {
                        val skipBuffer = ByteArray(FAKE_HEADER_SIZE)
                        var skipped = 0
                        while (skipped < FAKE_HEADER_SIZE) {
                            val n = currentDataSource?.read(skipBuffer, skipped, FAKE_HEADER_SIZE - skipped) ?: -1
                            if (n == -1) break
                            skipped += n
                        }
                        headerSkipped = true
                    }
                    return currentDataSource?.read(buffer, offset, length) ?: -1
                }

                override fun getUri(): Uri? = currentDataSource?.getUri()
                override fun getResponseHeaders(): Map<String, List<String>> =
                    currentDataSource?.getResponseHeaders() ?: emptyMap()
                override fun close() { currentDataSource?.close() }
            }
        }
    }

    private fun prepareMediaSource() {

        if (animeStreamLink.value == null) return

        var mediaSource: MediaSource
        val headerMap = mutableMapOf<String, String>()

        animeStreamLink.value!!.extraHeaders?.forEach {
            headerMap[it.key] = it.value
        }

        val httpDataSourceFactory: DataSource.Factory =
            CustomOkHttpDataSourceFactory(
                userAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_1_2 like Mac OS X)",
                defaultRequestProperties = headerMap,
                connectTimeoutMs = 20000,
                readTimeoutMs = 20000
            )

        var dataSourceFactory: DataSource.Factory = httpDataSourceFactory

        if (animeStreamLink.value!!.rawPlaylist != null) {

            val m3u8Content = animeStreamLink.value!!.rawPlaylist!!

            dataSourceFactory =
                createCombinedDataSourceFactory(m3u8Content, httpDataSourceFactory)
        }

        if (isVideoCacheEnabled) {

            val cacheFactory = CacheDataSource.Factory().apply {
                setCache(simpleCache!!)
                setUpstreamDataSourceFactory(dataSourceFactory)
            }

            dataSourceFactory = cacheFactory
        }

        val mediaItem = MediaItem.fromUri(animeStreamLink.value!!.link)

        mediaSource =
            if (animeStreamLink.value!!.isHls) {

                HlsMediaSource.Factory(dataSourceFactory)
                    .setAllowChunklessPreparation(true)
                    .createMediaSource(mediaItem)

            } else {

                ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(mediaItem)
            }

        if (animeStreamLink.value!!.subsLink.isNotBlank()) {

            showSubsBtn.postValue(true)

            val subtitleMediaSource =
                SingleSampleMediaSource.Factory(httpDataSourceFactory)
                    .createMediaSource(
                        MediaItem.SubtitleConfiguration.Builder(
                            Uri.parse(animeStreamLink.value!!.subsLink)
                        ).apply {

                            if (animeStreamLink.value!!.subsLink.contains("srt"))
                                setMimeType(MimeTypes.APPLICATION_SUBRIP)
                            else
                                setMimeType(MimeTypes.TEXT_VTT)

                            setLanguage("en")
                            setSelectionFlags(C.SELECTION_FLAG_DEFAULT)

                        }.build(),
                        C.TIME_UNSET
                    )

            mediaSource = MergingMediaSource(mediaSource, subtitleMediaSource)

        } else {
            showSubsBtn.postValue(false)
        }

        setMediaSource(mediaSource)
    }
}

@UnstableApi
class CustomOkHttpDataSourceFactory(
    private val userAgent: String,
    private val defaultRequestProperties: Map<String, String> = emptyMap(),
    private val connectTimeoutMs: Int = 20000,
    private val readTimeoutMs: Int = 20000
) : DataSource.Factory {

    private val okHttpClient: OkHttpClient by lazy {

        val connectionSpec =
            ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                .tlsVersions(TlsVersion.TLS_1_2, TlsVersion.TLS_1_3)
                .cipherSuites(
                    CipherSuite.TLS_AES_128_GCM_SHA256,
                    CipherSuite.TLS_AES_256_GCM_SHA384,
                    CipherSuite.TLS_CHACHA20_POLY1305_SHA256
                )
                .build()

        OkHttpClient.Builder()
            .connectionSpecs(listOf(connectionSpec))
            .connectTimeout(connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .build()
    }

    override fun createDataSource(): DataSource {

        return OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(defaultRequestProperties)
            .createDataSource()
    }
}