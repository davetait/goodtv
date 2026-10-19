package com.keyspaceapps.goodtv

import android.app.Application
import android.content.Context

class GoodTvApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }

    companion object {
        /** Set by the instrumented test runner before the Application is created. */
        @JvmStatic
        var skipDebugSource: Boolean = false
    }
}

fun Context.appGraph(): AppGraph = (applicationContext as GoodTvApp).graph
