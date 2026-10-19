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
}

fun Context.appGraph(): AppGraph = (applicationContext as GoodTvApp).graph
