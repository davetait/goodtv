package com.keyspaceapps.goodtv

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/** Disables the debug prefilled source (tests start from zero sources), real TMDB calls and guide downloads. */
class GoodTvTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle?) {
        GoodTvApp.skipDebugSource = true
        GoodTvApp.disableTmdb = true
        GoodTvApp.disableOpenSubtitles = true
        GoodTvApp.disableGuideDownloads = true
        super.onCreate(arguments)
    }
}
