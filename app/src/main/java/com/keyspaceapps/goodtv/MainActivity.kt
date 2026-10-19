package com.keyspaceapps.goodtv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.keyspaceapps.goodtv.domain.PlayableItem
import com.keyspaceapps.goodtv.ui.HomeScreen
import com.keyspaceapps.goodtv.ui.PlayerScreen
import com.keyspaceapps.goodtv.ui.theme.GoodTvTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GoodTvTheme {
                GoodTvApp(channels = SampleStreams.all)
            }
        }
    }
}

/**
 * Two-screen app state: channel list, or player for the chosen channel.
 * Deliberately no navigation library yet; add one when there is a third screen.
 */
@Composable
fun GoodTvApp(channels: List<PlayableItem>) {
    var selectedUrl by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = channels.firstOrNull { it.url == selectedUrl }

    if (selected == null) {
        HomeScreen(
            channels = channels,
            onChannelSelected = { selectedUrl = it.url },
        )
    } else {
        PlayerScreen(
            item = selected,
            onBack = { selectedUrl = null },
        )
    }
}

/** Temporary until the library screens land: public HLS test streams. */
object SampleStreams {
    val all: List<PlayableItem> = listOf(
        PlayableItem("Big Buck Bunny (Mux test stream)", "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8", isLive = true),
        PlayableItem("Apple BipBop 4x3", "https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_4x3/bipbop_4x3_variant.m3u8", isLive = true),
        PlayableItem("Tears of Steel (Unified Streaming)", "https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.m3u8", isLive = true),
    )
}
