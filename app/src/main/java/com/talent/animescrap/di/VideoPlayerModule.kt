package com.talent.animescrap.di

import android.app.Application
import androidx.annotation.OptIn
    import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.scopes.ViewModelScoped

@Module
@InstallIn(ViewModelComponent::class)
object VideoPlayerModule {

    @OptIn(UnstableApi::class) @Provides
    @ViewModelScoped
    fun provideVideoPlayer(app: Application): ExoPlayer {

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,   // 50_000
                DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,   // 50_000
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,          // 2_500
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS // 5_000
            )
            .build()


        return ExoPlayer.Builder(app)
            .setSeekForwardIncrementMs(10000)
            .setSeekBackIncrementMs(10000)
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .build()
    }

}