package com.keyspaceapps.goodtv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.keyspaceapps.goodtv.model.Channel
import com.keyspaceapps.goodtv.model.SampleChannels
import com.keyspaceapps.goodtv.ui.HomeScreen
import com.keyspaceapps.goodtv.ui.PlayerScreen
import com.keyspaceapps.goodtv.ui.theme.GoodTvTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GoodTvTheme {
                GoodTvApp(channels = SampleChannels.all)
            }
        }
    }
}

/**
 * Two-screen app state: channel list, or player for the chosen channel.
 * Deliberately no navigation library yet; add one when there is a third screen.
 */
@Composable
fun GoodTvApp(channels: List<Channel>) {
    var selectedChannelId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = channels.firstOrNull { it.id == selectedChannelId }

    if (selected == null) {
        HomeScreen(
            channels = channels,
            onChannelSelected = { selectedChannelId = it.id },
        )
    } else {
        PlayerScreen(
            channel = selected,
            onBack = { selectedChannelId = null },
        )
    }
}
