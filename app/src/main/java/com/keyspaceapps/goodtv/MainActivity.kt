package com.keyspaceapps.goodtv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.keyspaceapps.goodtv.ui.nav.GoodTvNavigation
import com.keyspaceapps.goodtv.ui.theme.GoodTvTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GoodTvTheme { GoodTvNavigation() }
        }
    }

    override fun onStart() {
        super.onStart()
        // TV processes live for days; refresh stale sources every time the app comes forward.
        appGraph().repository.refreshStale()
    }
}
