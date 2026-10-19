package com.keyspaceapps.goodtv

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/** Disables the debug prefilled source so instrumented tests start from zero sources. */
class GoodTvTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle?) {
        GoodTvApp.skipDebugSource = true
        super.onCreate(arguments)
    }
}
