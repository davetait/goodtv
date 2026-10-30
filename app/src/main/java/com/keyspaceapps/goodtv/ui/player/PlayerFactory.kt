package com.keyspaceapps.goodtv.ui.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.annotation.VisibleForTesting
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.keyspaceapps.goodtv.USER_AGENT
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicInteger

@OptIn(UnstableApi::class)
object PlayerFactory {
    const val SEEK_BACK_MS = 10_000L
    const val SEEK_FORWARD_MS = 30_000L

    /**
     * ExoPlayer whose HTTP source follows http→https redirects (IPTV panels and the fake server
     * both do that) and identifies as GoodTV, since some panels reject the default ExoPlayer agent.
     * [traffic] hears every byte downloaded (for the info box).
     */
    fun create(context: Context, traffic: TransferListener? = null): ExoPlayer {
        created.incrementAndGet()
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
        val dataSource = DefaultDataSource.Factory(context, http).setTransferListener(traffic)
        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setSeekBackIncrementMs(SEEK_BACK_MS)
            .setSeekForwardIncrementMs(SEEK_FORWARD_MS)
            .build()
    }

    /** Every player made here, for instrumented tests that check a hand-off opened nothing new. */
    @VisibleForTesting val created = AtomicInteger()

    private val switches = WeakHashMap<ExoPlayer, TrafficSwitch>()

    /**
     * A player that can be handed to another screen: its downloads go through a [TrafficSwitch] ([trafficOf])
     * that whoever holds it can point at their own meter.
     */
    fun createHandable(context: Context): ExoPlayer {
        val switch = TrafficSwitch()
        return create(context, switch).also { synchronized(switches) { switches[it] = switch } }
    }

    /** The traffic switch of a [createHandable] player; null for others. */
    fun trafficOf(player: ExoPlayer): TrafficSwitch? = synchronized(switches) { switches[player] }
}

/** Passes a player's transfers to [target], which can change while it plays (null: nobody listens). */
@OptIn(UnstableApi::class)
class TrafficSwitch : TransferListener {
    @Volatile var target: TransferListener? = null

    override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        target?.onTransferInitializing(source, dataSpec, isNetwork)
    }
    override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        target?.onTransferStart(source, dataSpec, isNetwork)
    }
    override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
        target?.onBytesTransferred(source, dataSpec, isNetwork, bytesTransferred)
    }
    override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        target?.onTransferEnd(source, dataSpec, isNetwork)
    }
}
