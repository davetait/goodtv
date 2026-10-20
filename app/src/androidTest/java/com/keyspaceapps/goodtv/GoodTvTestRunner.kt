package com.keyspaceapps.goodtv

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/** Disables the debug prefilled source (tests start from zero sources) and real TMDB calls. */
class GoodTvTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle?) {
        GoodTvApp.skipDebugSource = true
        GoodTvApp.disableTmdb = true
        super.onCreate(arguments)
    }
}
